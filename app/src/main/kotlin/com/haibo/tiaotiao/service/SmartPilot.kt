package com.haibo.tiaotiao.service

import android.os.SystemClock
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.*
import kotlinx.coroutines.*
import java.util.UUID

/** The automatic experiment is explicitly scoped to Damai and this app's synthetic visual demo. */
class SmartPilot(private val service: SkipAccessibilityService,
    private val currentWindow: () -> ScreenSnapshot?,
    private val current: () -> ScreenSnapshot?, private val open: (Long,String) -> Boolean,
    private val tap: suspend (Long,ScreenSnapshot,Area) -> Boolean) {
    private val app get() = service.application as TiaoApplication
    private val repository get() = app.repository
    private val capture = VisualCapture(service)
    private var generation = -1L
    private var session = ""
    private var nextCapture = 0L
    private var queued = 0
    private var nextLearnAt = 0L
    private var previousSkip: Area? = null
    private var attempts = false
    private var reported = false
    private var pendingRule: VisualRule? = null
    private var tapAt = 0L
    private var adScene: String? = null
    private var adSeenAt = 0L
    private var baselineDone = false
    private var captures = 0
    private var stage = "idle"
    private var lastSignals = ""
    fun summary() = "visual generation=$generation, captures=$captures, stage=$stage, signals=$lastSignals, adScene=$adScene, baselineDone=$baselineDone, queued=$queued"
    fun leaving(owner: String) {
        val rule=pendingRule ?: return
        if (owner==rule.packageName) return
        if (SystemClock.elapsedRealtime()-tapAt<600) {
            repository.putVisual(rule.copy(state=VisualRuleState.PAUSED,lastResult="点击后前台应用变化，已暂停方案"))
            repository.reportDetection(rule.packageName,"点击后前台应用变化，智能方案已暂停")
        } else repository.reportDetection(rule.packageName,"结果检查期间离开应用，本次不计为验证成功")
        pendingRule=null
    }
    fun demo(screen: ScreenSnapshot) = screen.packageName==service.packageName && screen.nodes.any { it.resourceId.endsWith(":id/smart_demo_ad") }
    fun eligible(screen: ScreenSnapshot) = demo(screen) || (screen.packageName=="cn.damai" && repository.settings.value.smartDamai)
    suspend fun scan(screen: ScreenSnapshot, gen: Long) {
        if (gen != generation) {
            generation=gen;session=UUID.randomUUID().toString();nextCapture=0;queued=0;nextLearnAt=0;previousSkip=null;attempts=false;reported=false
            adScene=null;adSeenAt=0;baselineDone=false
            captures=0;stage="starting";lastSignals="";pendingRule=null
        }
        if (attempts || SystemClock.elapsedRealtime()<nextCapture || !open(gen,screen.packageName)) return
        nextCapture=SystemClock.elapsedRealtime()+550
        val observingBaseline = adScene!=null && !attempts && !baselineDone && screen.fingerprint()!=adScene
        if ((!screen.complete && !observingBaseline) || (!demo(screen) && screen.nodes.size>40 && !observingBaseline)) { stage="skip-large-or-incomplete-${screen.nodes.size}";return }
        val cloudReady = app.visualLearner.allowed(screen.packageName)
        val hasActive = repository.visualRules.value.any { it.packageName==screen.packageName && it.versionCode==screen.versionCode && it.scene==screen.fingerprint() && it.active() }
        if (!demo(screen) && !cloudReady && !hasActive) {
            if (!reported) { reported=true;repository.reportDetection(screen.packageName,"大麦智能学习需要开启云端分析并使用 Vercel 网关；可在首页配置") }
            return
        }
        stage="capturing"
        val frame = try { capture.capture(screen) } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            val reason=e.message?.takeIf { it.matches(Regex("截图不可用（\\d+）")) || it=="窗口比例变化，暂不使用截图坐标" } ?: e.javaClass.simpleName
            if (stage!="error-$reason") repository.reportDetection(screen.packageName,"截图或端侧识别不可用：$reason；当前未执行点击")
            stage="error-$reason"
            return
        }
        frame.use {
            captures++
            if (!open(gen,screen.packageName)) return
            val fresh = current() ?: return
            if (fresh.packageName!=screen.packageName || fresh.windowId!=screen.windowId || (!observingBaseline && fresh.fingerprint()!=screen.fingerprint())) {stage="stale-screen";return}
            if (observingBaseline && frame.signals.home && frame.signals.skips.isEmpty()) {
                val scene=adScene ?: return
                repository.recordVisualBaseline(scene,frame.at-adSeenAt);baselineDone=true
                repository.reportDetection(screen.packageName,"已自动记录未点击广告的显示时长，用于排除自然结束")
                return
            }
            if (adScene==null && frame.signals.advertisement) { adScene=screen.fingerprint();adSeenAt=frame.at }
            val signals="ad=${frame.signals.advertisement},skip=${frame.signals.skips.size},home=${frame.signals.home},timer=${frame.signals.countdown}"
            if (lastSignals!=signals) {
                lastSignals=signals;repository.reportDetection(screen.packageName,"端侧 AI 已读取画面：广告标志=${frame.signals.advertisement}，跳过目标=${frame.signals.skips.size}，首页=${frame.signals.home}")
            }
            stage="matching-${screen.fingerprint().take(8)}"
            val skip = frame.signals.skips.singleOrNull()
            val stable = skip != null && previousSkip?.near(skip)==true
            previousSkip=skip
            val matches = repository.visualRules.value.filter { it.packageName==screen.packageName && it.scene==screen.fingerprint() && it.state!=VisualRuleState.PAUSED }
                .filter { it.active() || cloudReady || demo(screen) }
                .filter { VisualPolicy.canAttempt(it,screen,frame.signals,frame.patch(it.target)) }
            val paths=matches.map { VisualPolicy.clickableTarget(screen,frame.signals.skips.singleOrNull { a -> a.near(it.target) } ?: it.target)?.path }
            var rule=matches.singleOrNull() ?: matches.takeIf { paths.isNotEmpty() && paths.all { it!=null } && paths.distinct().size==1 }
                ?.maxByOrNull { it.successfulSessions() }
            if (demo(screen) && rule==null && skip!=null && stable && frame.signals.advertisement) {
                rule=VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,scene=screen.fingerprint(),target=skip,
                    targetHash=frame.patch(skip),label="skip",source="bundled-ocr-demo",createdAt=System.currentTimeMillis())
                repository.putVisual(rule)
            }
            if (rule != null && (skip==null || stable) && SystemClock.elapsedRealtime()-frame.at<1200) {
                val baseline=repository.visualBaseline(screen.fingerprint())
                if (!demo(screen) && frame.signals.countdown==null && baseline==0L) {
                    if (adScene==null) { adScene=screen.fingerprint();adSeenAt=frame.at }
                    return // Observe one natural ending; do not infer causality from a disappearing page alone.
                }
                attempts=true
                val chosen=rule
                pendingRule=chosen;tapAt=SystemClock.elapsedRealtime()
                val clickArea=frame.signals.skips.singleOrNull { it.near(chosen.target) } ?: chosen.target
                if (!tap(gen,screen,clickArea)) {
                    pendingRule=null
                    repository.reportDetection(screen.packageName,"智能点击未被接受或界面已变化，停止本轮")
                    return
                }
                repository.reportDetection(screen.packageName,if (chosen.active()) "已复用智能规则，正在自动检查结果" else "已自动尝试缓存方案，正在检查结果；无需人工确认")
                // Wait for two consecutive changed frames; the first frame can still be a launch/exit animation.
                val afterFrames=mutableListOf<VisualFrame>()
                try {
                    for (index in 0 until 5) {
                        delay(if (index==0) 450 else 550)
                        val snapshot=currentWindow() ?: continue
                        if (snapshot.packageName!=screen.packageName) break
                        val observed=try { capture.capture(snapshot) } catch (c: CancellationException) { throw c }
                            catch (_: Exception) { continue }
                        if (frame.difference(observed)>.04 && observed.signals.skips.isEmpty()) {
                            afterFrames+=observed
                            if (afterFrames.size==2) break
                        } else {
                            observed.close();afterFrames.forEach { it.close() };afterFrames.clear()
                        }
                    }
                    pendingRule=null
                    if (afterFrames.size<2) {
                        repository.putVisual(chosen.copy(state=VisualRuleState.PAUSED,lastResult="广告或目标仍存在，已暂停"))
                        repository.reportDetection(screen.packageName,"未观察到连续两帧页面变化，智能方案已暂停")
                        return
                    }
                    val after1=afterFrames[0];val after2=afterFrames[1]
                    val remaining=frame.signals.countdown?.times(1000L) ?: (baseline-(frame.at-adSeenAt))
                    // The first confirmed home frame establishes transition time; the second only
                    // corroborates it and may arrive later because OCR work takes time.
                    val early=remaining>=2000 && after1.at-frame.at<remaining-300
                    val basis=if (frame.signals.countdown!=null) "countdown" else if (baseline>0) "observed_baseline" else "unknown"
                    if (chosen.source=="bundled-ocr-demo") {
                        repository.putVisual(VisualPolicy.record(chosen,session,early,true,true,System.currentTimeMillis(),basis))
                        repository.reportDetection(screen.packageName,"演示自动检查完成，无需云端或人工确认")
                    } else if (chosen.active() && early && ((after1.signals.home && after2.signals.home) ||
                        (chosen.homeScene.isNotEmpty() && after1.screen.fingerprint()==chosen.homeScene && after2.screen.fingerprint()==chosen.homeScene))) {
                        repository.reportDetection(screen.packageName,"智能规则本地复用及结果检查通过，未请求云端模型")
                    } else if (cloudReady) {
                        repository.reportDetection(screen.packageName,"已观察到连续页面变化，AI 正在自动复核结果")
                        val homeScene=after2.screen.fingerprint().takeIf { after2.screen.complete && it!=screen.fingerprint() }.orEmpty()
                        app.visualLearner.verify(chosen,session,listOf(frame.jpeg,after1.jpeg,after2.jpeg),early,true,homeScene,basis)
                    }
                } finally { afterFrames.forEach { it.close() } }
            } else if (rule==null && !demo(screen) && !frame.signals.home && queued<2 && SystemClock.elapsedRealtime()>=nextLearnAt) {
                if (repository.visualRules.value.any { it.packageName==screen.packageName && it.scene==screen.fingerprint() && it.state==VisualRuleState.PAUSED &&
                    (it.lastResult.startsWith("用户") || it.automaticFailures>=2) }) return
                if (app.visualLearner.enqueue(frame)) { queued++;nextLearnAt=SystemClock.elapsedRealtime()+1800 }
            }
        }
    }
    fun close() = capture.close()
}
