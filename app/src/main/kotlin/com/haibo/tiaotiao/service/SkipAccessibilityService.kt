package com.haibo.tiaotiao.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.network.JevClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.lang.ref.WeakReference

class SkipAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var serviceRef = WeakReference<SkipAccessibilityService>(null)
        val instance: SkipAccessibilityService? get() = serviceRef.get()
    }
    private val repository get() = (application as TiaoApplication).repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = SessionGate()
    private val jev = JevClient()
    private var scanJob: Job? = null
    private var overlay: LinearLayout? = null
    private var overlayRuleId: String? = null
    private var overlayIsResult = false
    private var expireJob: Job? = null

    override fun onServiceConnected() {
        serviceRef = WeakReference(this)
        repository.connected.value = true
        repository.log("", "无障碍服务已连接")
        scope.launch {
            repository.settings.collectLatest { settings ->
                scanJob?.cancel()
                gate.reset()
                hideOverlay()
                if (settings.enabled) scheduleScan()
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !repository.settings.value.enabled) return
        // Foreground changes must cancel a request immediately rather than waiting for its result.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && pkg != packageName && pkg !in repository.settings.value.packages) {
                scanJob?.cancel(); gate.observe(null, SystemClock.elapsedRealtime()); hideOverlay()
                return
            }
        }
        scheduleScan()
    }
    private fun scheduleScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            delay(180)
            try { scan() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { repository.log(gate.packageName.orEmpty(), "检测未完成，已结束本轮；可重新打开应用重试") }
        }
    }
    fun restartSession() {
        scanJob?.cancel(); gate.reset(); hideOverlay()
    }
    private fun observe(): Observation? {
        if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val applicationWindow = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.isActive }).firstOrNull { it.isFocused || it.isActive }
        val root = applicationWindow?.root ?: rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != packageName && pkg !in repository.settings.value.packages) {
            @Suppress("DEPRECATION") root.recycle()
            return null
        }
        val version = runCatching {
            @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(pkg, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrElse { @Suppress("DEPRECATION") root.recycle(); return null }
        val metrics = resources.displayMetrics
        return ScreenReader.read(root, version, metrics.widthPixels, metrics.heightPixels)
    }
    private fun isDemo(screen: ScreenSnapshot) = screen.packageName == packageName && screen.nodes.any { it.resourceId.endsWith(":id/demo_skip") }
    private fun eligible(screen: ScreenSnapshot) = (screen.packageName in repository.settings.value.packages && screen.packageName != packageName) || isDemo(screen)

    private suspend fun scan() {
        if (!repository.settings.value.enabled) return
        val observation = observe()
        if (observation == null) {
            gate.observe(null, SystemClock.elapsedRealtime())
            // A transient null root during a successful page transition must not discard the result question.
            if (!overlayIsResult || !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) hideOverlay()
            return
        }
        val screen = observation.use { it.screen }
        if (!eligible(screen)) {
            gate.observe(null, SystemClock.elapsedRealtime())
            val resultPackage = repository.rules.value.find { it.id == overlayRuleId }?.packageName
            if (!overlayIsResult || resultPackage != screen.packageName) hideOverlay()
            return
        }
        val now = SystemClock.elapsedRealtime()
        gate.observe(screen.packageName, now)
        if (!gate.isOpen(screen.packageName, now)) return
        val fingerprint = screen.fingerprint()
        val rules = repository.rules.value
        val approved = RuleEngine.autoMatch(rules, screen)
        if (approved != null) { dispatch(approved, false); return }
        val existing = rules.filter { it.packageName == screen.packageName && it.screenFingerprint == fingerprint }
        // Disabled/ambiguous rules never trigger new AI proposals behind the user's back.
        if (existing.any { it.status == RuleStatus.DISABLED } || existing.count { it.isVerified() } > 1) return
        existing.firstOrNull { it.status == RuleStatus.TRIALLED }?.let { showResult(it); return }
        existing.firstOrNull { it.status == RuleStatus.PENDING || it.status == RuleStatus.IMPORTED }?.let {
            if (RuleEngine.locate(it, screen) != null) showProposal(it)
            return
        }
        if (screen.candidates().none { SignalText.hasSkip(it.label) }) return
        val demo = isDemo(screen)
        val settings = repository.settings.value
        if (!demo && (!settings.cloudEnabled || repository.vault.get("jevKey").isBlank())) return
        if (!gate.claimRequest(fingerprint)) return
        val candidate = if (demo) screen.candidates().singleOrNull { SignalText.hasSkip(it.label) } else try {
            withContext(Dispatchers.IO) { jev.select(screen, repository.vault.get("jevKey"), settings.model) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { repository.log(screen.packageName, error.message?.takeIf { it.startsWith("Jev") || it.startsWith("请") }.orEmpty().ifBlank { "Jev 分析失败，本轮未执行任何操作" }); return }
        if (candidate == null) { repository.log(screen.packageName, "未确认跳过目标，本轮不处理"); return }
        val rule = RuleEngine.compile(screen, candidate, if (demo) "demo-local" else settings.model, System.currentTimeMillis())
        repository.put(rule)
        repository.addSample(rule, screen)
        repository.log(screen.packageName, if (demo) "演示方案待试跳；未请求云端模型" else "Jev 候选方案已保存，等待试跳与人工确认")
        val fresh = observe()?.use { it.screen }
        if (fresh != null && gate.isOpen(screen.packageName, SystemClock.elapsedRealtime()) && RuleEngine.locate(rule, fresh) != null) showProposal(rule)
        else repository.log(screen.packageName, "页面已变化或检测超时；候选保留，下次打开时试跳")
    }
    private fun dispatch(proposal: SkipRule, trial: Boolean) {
        hideOverlay()
        val current = repository.rules.value.find { it.id == proposal.id } ?: return
        if (current.behaviorDigest() != proposal.behaviorDigest() || !repository.settings.value.enabled) return
        if (!trial && !current.isVerified()) return
        if (trial && current.status !in setOf(RuleStatus.PENDING, RuleStatus.IMPORTED)) return
        val observation = observe() ?: return
        observation.use {
            if (!eligible(it.screen) || !gate.isOpen(it.screen.packageName, SystemClock.elapsedRealtime())) return
            val target = RuleEngine.locate(current, it.screen)
            if (target == null) { repository.log(current.packageName, "页面或目标已变化，取消点击"); return }
            gate.dispatched()
            val accepted = it.handles[target.path]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (trial && accepted) {
                val updated = RuleEngine.recordTrial(current, true, System.currentTimeMillis())
                repository.put(updated)
                repository.log(current.packageName, "试跳已发起，等待你确认实际结果")
                showResult(updated)
            } else repository.log(current.packageName, if (accepted) "已按验证规则发起跳过点击" else "系统未接受点击；未重试")
        }
    }
    private fun showProposal(rule: SkipRule) {
        if (overlayRuleId == rule.id && !overlayIsResult) return
        showPanel(rule, false, "发现新的跳过方案", "本次试跳后，请确认实际结果。", listOf(
            "试跳" to { dispatch(rule, true) },
            "忽略" to { repository.put(rule.copy(status = RuleStatus.DISABLED)); hideOverlay(); gate.dispatched() },
        ))
    }
    private fun showResult(rule: SkipRule) {
        if (overlayRuleId == rule.id && overlayIsResult) return
        showPanel(rule, true, "刚才是否正确跳过广告？", "确认成功后，下次相同界面自动跳过。", listOf(
            "确实跳过" to { confirm(rule, true) }, "没有跳过" to { confirm(rule, false) }, "稍后确认" to { hideOverlay() },
        ))
    }
    private fun confirm(rule: SkipRule, success: Boolean) {
        runCatching { repository.confirm(rule.id, success) }.onFailure { repository.log(rule.packageName, "确认记录过期或规则变化，请重新试跳") }
        hideOverlay()
    }
    private fun showPanel(rule: SkipRule, result: Boolean, title: String, text: String, actions: List<Pair<String, () -> Unit>>) {
        hideOverlay()
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            setBackgroundColor(Color.rgb(239, 247, 242))
            addView(TextView(context).apply { this.text = title; textSize = 16f; setTextColor(Color.rgb(23, 54, 42)) })
            addView(TextView(context).apply { this.text = text; textSize = 12f; setTextColor(Color.DKGRAY) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                actions.forEach { (label, action) -> addView(Button(context).apply {
                    this.text = label; textSize = 13f; setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            })
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        runCatching { getSystemService(WindowManager::class.java).addView(panel, params) }.onSuccess {
            overlay = panel; overlayRuleId = rule.id; overlayIsResult = result
            expireJob = scope.launch { delay(if (result) 30_000 else 8_000); hideOverlay() }
        }.onFailure { repository.log(rule.packageName, "确认浮层不可用，请到规则页查看") }
    }
    private fun hideOverlay() {
        expireJob?.cancel(); expireJob = null
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; overlayRuleId = null; overlayIsResult = false
    }
    override fun onInterrupt() { scanJob?.cancel(); gate.reset(); hideOverlay() }
    override fun onDestroy() {
        hideOverlay(); scope.cancel(); repository.connected.value = false
        if (instance === this) serviceRef = WeakReference(null)
        super.onDestroy()
    }
}
