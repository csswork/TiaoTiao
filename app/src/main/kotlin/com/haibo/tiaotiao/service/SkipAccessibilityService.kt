package com.haibo.tiaotiao.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
import com.haibo.tiaotiao.network.modelErrorMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.FileDescriptor
import java.io.PrintWriter
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
    private var diagnosticPackage: String? = null
    private var eventWindowId: Int? = null
    private var targetEventPackage: String? = null
    private var targetEventAt = 0L
    private var lastReadState = "尚未读取界面"
    private var smartPilot: SmartPilot? = null
    private fun visualDemo(screen: ScreenSnapshot) = screen.packageName == packageName &&
        screen.nodes.any { it.resourceId.endsWith(":id/smart_demo_ad") }
    private fun pilot() = smartPilot ?: SmartPilot(this,
        current = { observe()?.use { it.screen } },
        open = { generation, pkg -> repository.settings.value.enabled && gate.generation == generation && gate.isOpen(pkg,SystemClock.elapsedRealtime()) },
        tap = { generation, screen, area -> smartTap(generation,screen,area) }).also { smartPilot = it }
    private val reportedReasons = mutableSetOf<String>()
    private fun report(pkg: String, reason: String, message: String) {
        if (diagnosticPackage != pkg) { diagnosticPackage = pkg; reportedReasons.clear() }
        if (reportedReasons.add(reason)) repository.reportDetection(pkg, message)
    }

    override fun onServiceConnected() {
        serviceRef = WeakReference(this)
        repository.connected.value = true
        repository.log("", "无障碍服务已连接")
        (application as TiaoApplication).visualLearner.resume()
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
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage in repository.settings.value.packages) {
            targetEventPackage = eventPackage
            targetEventAt = SystemClock.elapsedRealtime()
        }
        // Foreground changes must cancel a request immediately rather than waiting for its result.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = eventPackage
            eventWindowId = event.windowId.takeIf { it >= 0 }
            if (pkg in repository.settings.value.packages) report(pkg, "event", "收到目标应用窗口事件，等待可读取的开屏界面")
            if (pkg.isNotBlank() && pkg != packageName && pkg !in repository.settings.value.packages) {
                // Toasts/system-owned windows are not proof that the target application left the foreground.
                val available = windows
                val appWindow = selectedWindow(available)
                val root = appWindow?.root
                val actualPackage = root?.packageName?.toString()
                @Suppress("DEPRECATION") root?.recycle()
                if (actualPackage != null && actualPackage != packageName && actualPackage !in repository.settings.value.packages) {
                    smartPilot?.leaving(actualPackage)
                    scanJob?.cancel(); gate.observeWindow(actualPackage, true, SystemClock.elapsedRealtime()); hideOverlay()
                    targetEventPackage = null
                    diagnosticPackage = null; reportedReasons.clear()
                    return
                }
            }
        }
        scheduleScan()
    }
    private fun scheduleScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            // Retry temporarily missing roots/content even if the OEM sends no further content event.
            // This loop never extends the session or retries a dispatched click.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            delay(80)
            try {
                do {
                    scan()
                    val now = SystemClock.elapsedRealtime()
                    val target = targetEventPackage
                    if (target == null || (target !in repository.settings.value.packages && target != packageName) || !repository.settings.value.enabled ||
                        now >= deadline || now - targetEventAt >= 10_000 || gate.actionDispatched ||
                        (gate.packageName == target && !gate.isOpen(target, now))) break
                    delay(160)
                } while (isActive)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report(gate.packageName.orEmpty(), "error", "检测未完成，已结束本轮；可重新打开应用重试") }
        }
    }
    fun restartSession() {
        scanJob?.cancel(); gate.reset(); hideOverlay()
        targetEventPackage = null; eventWindowId = null
        diagnosticPackage = null; reportedReasons.clear()
    }
    private fun selectedWindow(available: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val id = WindowSelection.choose(available.map {
            WindowCandidate(it.id, it.type == AccessibilityWindowInfo.TYPE_APPLICATION, it.isFocused, it.isActive)
        }, eventWindowId)
        return available.firstOrNull { it.id == id }
    }
    private fun unreadable(reason: String, message: String) {
        lastReadState = message
        val target = targetEventPackage ?: return
        if (SystemClock.elapsedRealtime() - targetEventAt < 10_000) report(target, reason, message)
    }
    private fun observe(): Observation? {
        if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            gate.observeWindow(null, false, SystemClock.elapsedRealtime(), deviceLocked = true)
            unreadable("locked", "屏幕关闭或设备锁定，已暂停检测")
            return null
        }
        val availableWindows = windows
        val applicationWindow = selectedWindow(availableWindows)
        val root = applicationWindow?.root ?: rootInActiveWindow ?: run {
            unreadable("no-root", "系统尚未提供可读取的应用界面，正在短时重试（未调用 Jev）")
            return null
        }
        val pkg = root.packageName?.toString().orEmpty()
        val belongsToApplication = availableWindows.any { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (belongsToApplication) {
            val oldPackage = gate.packageName
            gate.observeWindow(pkg, true, SystemClock.elapsedRealtime())
            if (oldPackage != pkg) { diagnosticPackage = null; reportedReasons.clear() }
        }
        if (pkg != packageName && pkg !in repository.settings.value.packages) {
            targetEventPackage = null
            lastReadState = "当前为未选择的应用"
            @Suppress("DEPRECATION") root.recycle()
            return null
        }
        val version = runCatching {
            @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(pkg, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrElse {
            lastReadState = "无法读取应用版本（${it.javaClass.simpleName}），未执行点击或请求 Jev"
            report(pkg, "version-unavailable", lastReadState)
            @Suppress("DEPRECATION") root.recycle()
            return null
        }
        val metrics = resources.displayMetrics
        return ScreenReader.read(root, version, metrics.widthPixels, metrics.heightPixels).also {
            lastReadState = "已读取 ${it.screen.nodes.size} 个节点，界面完整=${it.screen.complete}"
        }
    }
    private fun isDemo(screen: ScreenSnapshot) = screen.packageName == packageName && screen.nodes.any { it.resourceId.endsWith(":id/demo_skip") }
    private fun eligible(screen: ScreenSnapshot) = (screen.packageName in repository.settings.value.packages && screen.packageName != packageName) || isDemo(screen) || visualDemo(screen)

    private suspend fun scan() {
        if (!repository.settings.value.enabled) return
        val observation = observe()
        if (observation == null) {
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
        if (gate.packageName != screen.packageName) { diagnosticPackage = null; reportedReasons.clear() }
        gate.observe(screen.packageName, now)
        if (!gate.isOpen(screen.packageName, now)) {
            if (!gate.actionDispatched) report(screen.packageName, "timeout", "本轮开屏检测已结束；未识别到目标时不会点击，可在记录页查看原因")
            return
        }
        report(screen.packageName, "begin", "开始检测开屏界面")
        if (android.os.Build.VERSION.SDK_INT >= 30 && (visualDemo(screen) ||
            (screen.packageName == "cn.damai" && repository.settings.value.smartDamai))) {
            if (visualDemo(screen)) { targetEventPackage = packageName; targetEventAt = now }
            pilot().scan(screen,gate.generation)
            return
        }
        val fingerprint = screen.fingerprint()
        val rules = repository.rules.value
        when (val local = RuleEngine.resolveVerified(rules, screen)) {
            is VerifiedSceneLookup.Matched -> {
                report(screen.packageName, "local-match", "本地已验证规则匹配成功，未请求 Jev")
                dispatch(local.rule, false); return
            }
            VerifiedSceneLookup.UnmatchedTarget -> {
                report(screen.packageName, "local-wait", "已识别本地验证界面，等待目标位置匹配；本轮不重复请求 Jev")
                return
            }
            VerifiedSceneLookup.Unknown -> Unit
        }
        val existing = rules.filter { it.packageName == screen.packageName && it.screenFingerprint == fingerprint }
        // Disabled/ambiguous rules never trigger new AI proposals behind the user's back.
        if (existing.any { it.status == RuleStatus.DISABLED } || existing.count { it.isVerified() } > 1) {
            report(screen.packageName, "blocked-rule", "当前界面的规则已停用或存在冲突，未执行点击")
            return
        }
        existing.firstOrNull { it.status == RuleStatus.TRIALLED }?.let { showResult(it); return }
        existing.firstOrNull { it.status == RuleStatus.PENDING || it.status == RuleStatus.IMPORTED }?.let {
            if (RuleEngine.locate(it, screen) != null) showProposal(it)
            return
        }
        if (!screen.complete) {
            report(screen.packageName, "incomplete", "界面节点读取达到上限，本轮未点击；请反馈该应用以便适配")
            return
        }
        if (screen.candidates().none { SignalText.hasSkip(it.label) }) {
            report(screen.packageName, "no-target", "读取 ${screen.nodes.size} 个节点、${screen.candidates().size} 个可点击控件，尚未找到文字跳过按钮；图片按钮暂不支持")
            return
        }
        if (rules.any { it.packageName == screen.packageName && it.isVerified() }) {
            report(screen.packageName, "local-miss", "已有验证规则与当前界面不匹配，将按新界面处理")
        }
        val demo = isDemo(screen)
        val settings = repository.settings.value
        if (!demo && !settings.cloudEnabled) {
            report(screen.packageName, "cloud-off", "云端分析未开启，仅匹配已验证的本地规则")
            return
        }
        if (!demo && repository.vault.get("jevKey").isBlank()) {
            report(screen.packageName, "no-key", "未填写模型密钥，无法分析新界面，请到首页完成配置")
            return
        }
        if (!gate.claimRequest(fingerprint)) return
        if (!demo) repository.reportDetection(screen.packageName, "正在通过 ${settings.jevProvider.displayName} 分析跳过目标…")
        val candidate = if (demo) screen.candidates().singleOrNull { SignalText.hasSkip(it.label) } else try {
            withContext(Dispatchers.IO) { jev.select(screen, repository.vault.get("jevKey"), settings.model, settings.jevProvider) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = modelErrorMessage(error)
            repository.recordModelResponse(false, message)
            report(screen.packageName, "model-error", message)
            return
        }
        if (!demo) repository.recordModelResponse(true, "${settings.jevProvider.displayName} 已正常响应")
        if (candidate == null) { report(screen.packageName, "uncertain", "模型已响应，但未确认合适的跳过目标，本轮不处理"); return }
        val rule = RuleEngine.compile(screen, candidate, if (demo) "demo-local" else "${settings.jevProvider.name}:${settings.model}", System.currentTimeMillis())
        repository.put(rule)
        repository.addSample(rule, screen)
        report(screen.packageName, "proposal", if (demo) "演示方案待试跳；未请求云端模型" else "Jev 候选方案已保存，等待试跳与人工确认")
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
            } else {
                repository.reportDetection(current.packageName, if (accepted) "已按验证规则发起跳过点击" else "系统未接受点击；未重试")
                if (accepted) observeClickEffect(current, gate.generation)
            }
        }
    }
    private suspend fun smartTap(generation: Long, screen: ScreenSnapshot, area: Area): Boolean {
        if (!repository.settings.value.enabled || gate.generation != generation || !gate.isOpen(screen.packageName,SystemClock.elapsedRealtime())) return false
        val observation = observe() ?: return false
        observation.use {
            val fresh=it.screen
            if (fresh.packageName != screen.packageName || fresh.windowId != screen.windowId || fresh.fingerprint() != screen.fingerprint()) return false
            if (screen.packageName != "cn.damai" && !visualDemo(screen)) return false
            val node=VisualPolicy.clickableTarget(fresh,area)
            if (node!=null) {
                gate.dispatched()
                val accepted=it.handles[node.path]?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                repository.reportDetection(screen.packageName,if (accepted) "视觉目标已关联当前控件并发起点击" else "当前控件未接受点击，停止本轮")
                return accepted
            }
        }
        gate.dispatched()
        val path = Path().apply { moveTo((area.left+area.right)/2*screen.width,(area.top+area.bottom)/2*screen.height) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,60)).build()
        return suspendCancellableCoroutine { c ->
            val accepted = dispatchGesture(gesture,object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { if (c.isActive) c.resumeWith(Result.success(true)) }
                override fun onCancelled(gestureDescription: GestureDescription) { if (c.isActive) c.resumeWith(Result.success(false)) }
            },null)
            if (!accepted && c.isActive) c.resumeWith(Result.success(false))
        }
    }
    private fun observeClickEffect(rule: SkipRule, generation: Long) {
        scope.launch {
            delay(900)
            if (!repository.settings.value.enabled || gate.generation != generation) return@launch
            val observation = observe() ?: return@launch
            observation.use {
                if (gate.generation != generation || it.screen.packageName != rule.packageName) return@use
                repository.reportDetection(rule.packageName, if (RuleEngine.locate(rule, it.screen) != null)
                    "规则命中，但点击后跳过目标仍存在；已停止重复点击，请反馈该规则"
                    else "已发起规则点击，观察到页面发生变化")
            }
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
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("TiaoTiao detection diagnostics (no credentials or screen text)")
        writer.println("enabled=${repository.settings.value.enabled}, scanning=${scanJob?.isActive == true}, session=${gate.generation}, dispatched=${gate.actionDispatched}")
        writer.println("sessionPackage=${gate.packageName}, eventPackage=$targetEventPackage, eventWindow=$eventWindowId")
        writer.println("lastRead=$lastReadState")
        smartPilot?.let { writer.println(it.summary()) }
        windows.forEach { writer.println("window=${it.id}, type=${it.type}, focused=${it.isFocused}, active=${it.isActive}") }
    }
    override fun onDestroy() {
        smartPilot?.close()
        hideOverlay(); scope.cancel(); repository.connected.value = false
        if (instance === this) serviceRef = WeakReference(null)
        super.onDestroy()
    }
}
