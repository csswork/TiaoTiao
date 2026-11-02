package com.haibo.tiaotiao.service

import android.os.SystemClock
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.*
import kotlinx.coroutines.*
import java.util.UUID

/** One bounded startup-learning pipeline for every selected application. */
class AutoLearning(private val service: SkipAccessibilityService,
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
    private var nodeQueued=0
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
    private val homeArea = Area(0f,.88f,1f,.98f)
    private fun pause(rule: VisualRule, message: String) {
        repository.updateVisual(rule.id,rule.behavior()) {current ->
            if(current.state==VisualRuleState.PAUSED && current.lastResult.startsWith("用户")) null else
                current.copy(state=VisualRuleState.PAUSED,automaticFailures=(current.automaticFailures+1).coerceAtMost(100),lastResult=message)
        }
    }
    fun summary() = "visual generation=$generation, captures=$captures, stage=$stage, signals=$lastSignals, adScene=$adScene, baselineDone=$baselineDone, queued=$queued"
    fun leaving(owner: String) {
        val rule=pendingRule ?: return
        if (owner==rule.packageName) return
        if (SystemClock.elapsedRealtime()-tapAt<600) {
            pause(rule,"点击后前台应用变化，已暂停方案")
            repository.reportDetection(rule.packageName,"点击后前台应用变化，智能方案已暂停")
        } else repository.reportDetection(rule.packageName,"结果检查期间离开应用，本次不计为验证成功")
        pendingRule=null
    }
    fun demo(screen: ScreenSnapshot) = screen.packageName==service.packageName &&
        (app.foregroundScreen==TiaoApplication.ForegroundScreen.VISUAL_DEMO || screen.nodes.any { it.resourceId.endsWith(":id/smart_demo_ad") })
    fun eligible(screen: ScreenSnapshot) = demo(screen) || AdRecognition.eligible(screen.packageName,repository.settings.value.packages,service.packageName,repository.settings.value.automaticLearning)
    private fun begin(gen: Long) {
        if (gen != generation) {
            generation=gen;session=UUID.randomUUID().toString();nextCapture=0;queued=0;nodeQueued=0;nextLearnAt=0;previousSkip=null;attempts=false;reported=false
            adScene=null;adSeenAt=0;baselineDone=false
            captures=0;stage="starting";lastSignals="";pendingRule=null
        }
    }
    fun warmUp() = capture.warmUp()
    fun inspectNodes(screen: ScreenSnapshot,gen: Long,force: Boolean=false) {
        begin(gen)
        if(queued>=3 || nodeQueued>=2 || !eligible(screen) || !open(gen,screen.packageName)) return
        if(!force && repository.visualRules.value.any { AdRecognition.matchesScene(it,screen) && it.state!=VisualRuleState.PAUSED && !it.awaitingLocalReview }) return
        if(app.visualLearner.inspectNodes(screen)) {queued++;nodeQueued++;nextLearnAt=SystemClock.elapsedRealtime()+600}
    }
    suspend fun scan(initial: ScreenSnapshot, gen: Long) {
        var screen=initial
        begin(gen)
        if(!eligible(screen)) return
        if (attempts || SystemClock.elapsedRealtime()<nextCapture || !open(gen,screen.packageName)) return
        nextCapture=SystemClock.elapsedRealtime()+550
        val observingBaseline = adScene!=null && !attempts && !baselineDone
        val nodeReady = app.visualLearner.nodeAllowed(screen.packageName) && repository.vault.contains("jevKey")
        val cloudReady = nodeReady && app.visualLearner.allowed(screen.packageName)
        val hasActive = repository.visualRules.value.any { AdRecognition.matchesScene(it,screen) && it.active() }
        if (!demo(screen) && !nodeReady && !hasActive) {
            if (!reported) { reported=true;repository.reportDetection(screen.packageName,"新界面自动学习需要配置密钥并开启云端分析；已验证规则仍可本地复用") }
            return
        }
        stage="capturing"
        val frame = try { capture.capture(screen) } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            val reason=e.message?.takeIf { it.matches(Regex("截图不可用（\\d+）")) || it=="截图请求超时" || it=="窗口比例变化，暂不使用截图坐标" } ?: e.javaClass.simpleName
            if (stage!="error-$reason") repository.reportDetection(screen.packageName,"截图或端侧识别不可用：$reason；当前未执行点击")
            stage="error-$reason"
            return
        }
        frame.use {
            captures++
            if (!open(gen,screen.packageName)) return
            val fresh = current() ?: return
            if (fresh.packageName!=screen.packageName || fresh.windowId!=screen.windowId || AdRecognition.scene(fresh)!=AdRecognition.scene(screen)) {stage="stale-screen";return}
            if(fresh.nodes.isNotEmpty()) screen=fresh
            if (observingBaseline && frame.signals.home && frame.signals.skips.isEmpty()) {
                val scene=adScene ?: return
                repository.recordVisualBaseline(scene,frame.at-adSeenAt);baselineDone=true
                repository.reportDetection(screen.packageName,"已自动记录未点击广告的显示时长，用于排除自然结束")
                return
            }
            if (adScene==null && frame.signals.advertisement) { adScene=AdRecognition.scene(screen);adSeenAt=frame.at }
            val signals="ad=${frame.signals.advertisement},skip=${frame.signals.skips.size},home=${frame.signals.home},timer=${frame.signals.countdown}"
            if (lastSignals!=signals) {
                lastSignals=signals;repository.reportDetection(screen.packageName,"端侧 AI 已读取画面：广告标志=${frame.signals.advertisement}，跳过目标=${frame.signals.skips.size}，首页=${frame.signals.home}")
            }
            stage="matching-${screen.fingerprint().take(8)}"
            val skip = frame.signals.skips.singleOrNull()
            val stable = skip != null && previousSkip?.near(skip)==true
            previousSkip=skip
            val matches = repository.visualRules.value.filter { AdRecognition.matchesScene(it,screen) && it.state!=VisualRuleState.PAUSED }
                .filter { it.active() || nodeReady || demo(screen) }
                .filter { VisualPolicy.canAttempt(it,screen,frame.signals,
                    if(it.targetHash.count {bit -> bit=='1'} in 4..60) frame.targetPatch(it.target) else "",
                    frame.patch(Area(.05f,.12f,.95f,.88f))) }
            val paths=matches.map { VisualPolicy.clickableTarget(screen,frame.signals.skips.singleOrNull { a -> a.near(it.target) } ?: it.target)?.path }
            var rule=matches.singleOrNull() ?: matches.takeIf { paths.isNotEmpty() && paths.all { it!=null } && paths.distinct().size==1 }
                ?.maxByOrNull { it.successfulSessions() }
            if(rule==null && matches.isNotEmpty() && matches.all {it.target.near(matches.first().target)}) rule=matches.maxByOrNull {it.successfulSessions()}
            if (demo(screen) && rule==null && skip!=null && stable && frame.signals.advertisement) {
                rule=VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,scene=AdRecognition.scene(screen),sceneVersion=2,target=skip,
                    targetHash=frame.patch(skip),label="skip",source="bundled-ocr-demo",createdAt=System.currentTimeMillis())
                repository.putVisual(rule)
            }
            if (rule != null && (skip==null || stable || rule.nodeTarget?.let {AdRecognition.matchesNode(it,screen)}==true) && SystemClock.elapsedRealtime()-frame.at<1200) {
                if(repository.rules.value.any {it.packageName==screen.packageName && it.versionCode==screen.versionCode &&
                    it.status==RuleStatus.DISABLED && it.target.bounds.near(rule.target)}) {
                    repository.reportDetection(screen.packageName,"当前目标已有用户停用记录，未自动点击")
                    return
                }
                if(!rule.active() && !cloudReady && !demo(screen)) {
                    if(!reported) {reported=true;repository.reportDetection(screen.packageName,"Jev 候选已缓存；自动尝试及独立结果复核需要 Vercel 视觉服务")}
                    return
                }
                val baseline=repository.visualBaseline(AdRecognition.scene(screen))
                if (!demo(screen) && frame.signals.countdown==null && baseline==0L && rule.evidence.any { it.timingBasis=="unknown" }) {
                    if (adScene==null) { adScene=AdRecognition.scene(screen);adSeenAt=frame.at }
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
                        val owner=currentWindow()
                        if(owner==null || owner.packageName!=snapshot.packageName || owner.windowId!=snapshot.windowId) {observed.close();break}
                        if (frame.difference(observed)>.04 && observed.signals.skips.isEmpty()) {
                            afterFrames+=observed
                            if (afterFrames.size==2) break
                        } else {
                            observed.close();afterFrames.forEach { it.close() };afterFrames.clear()
                        }
                    }
                    pendingRule=null
                    if (afterFrames.size<2) {
                        pause(chosen,"广告或目标仍存在，已暂停")
                        repository.reportDetection(screen.packageName,"未观察到连续两帧页面变化，智能方案已暂停")
                        return
                    }
                    val after1=afterFrames[0];val after2=afterFrames[1]
                    val beforeHash=frame.patch(homeArea);val firstHash=after1.patch(homeArea);val secondHash=after2.patch(homeArea)
                    val homeHash=VisualPolicy.homeTemplate(beforeHash,firstHash,secondHash)
                    val remaining=frame.signals.countdown?.times(1000L) ?: (baseline-(frame.at-adSeenAt))
                    // The first confirmed home frame establishes transition time; the second only
                    // corroborates it and may arrive later because OCR work takes time.
                    val early=remaining>=2000 && after1.at-frame.at<remaining-300
                    val basis=if (frame.signals.countdown!=null) "countdown" else if (baseline>0) "observed_baseline" else "unknown"
                    if (chosen.source=="bundled-ocr-demo") {
                        repository.putVisual(VisualPolicy.record(chosen,session,early,true,true,System.currentTimeMillis(),basis))
                        repository.reportDetection(screen.packageName,"演示自动检查完成，无需云端或人工确认")
                    } else if (chosen.active() && early && !after1.signals.advertisement && !after2.signals.advertisement &&
                        ((after1.signals.home && after2.signals.home) || VisualPolicy.matchesHome(chosen,beforeHash,firstHash,secondHash) ||
                        (chosen.homeScene.isNotEmpty() && after1.screen.fingerprint()==chosen.homeScene && after2.screen.fingerprint()==chosen.homeScene))) {
                        repository.reportDetection(screen.packageName,"智能规则本地复用及结果检查通过，未请求云端模型")
                    } else if (cloudReady) {
                        repository.reportDetection(screen.packageName,"已观察到连续页面变化，AI 正在自动复核结果")
                        val homeScene=after2.screen.fingerprint().takeIf { after2.screen.complete && it!=screen.fingerprint() }.orEmpty()
                        app.visualLearner.verify(chosen,session,listOf(frame.jpeg,after1.jpeg,after2.jpeg),early,true,homeScene,homeHash,basis)
                    } else {
                        if(chosen.active()) {
                            pause(chosen,"本地结果证据不足，等待重新识别")
                            repository.reportDetection(screen.packageName,"已按本地规则关闭，但结果证据不足且云端未就绪，暂时停止自动复用")
                        } else repository.reportDetection(screen.packageName,"已观察到页面变化；自动启用仍需独立 AI 结果复核，保留候选")
                    }
                } finally { afterFrames.forEach { it.close() } }
            } else if (rule==null && !demo(screen) && !frame.signals.home && queued<3 &&
                (frame.signals.advertisement || AdRecognition.candidates(screen).isNotEmpty() || frame.patch(Area(.1f,.15f,.9f,.8f)).count {it=='1'} in 4..60) &&
                SystemClock.elapsedRealtime()>=nextLearnAt) {
                if (repository.visualRules.value.any { AdRecognition.matchesScene(it,screen) && it.state==VisualRuleState.PAUSED &&
                    (it.lastResult.startsWith("用户") || it.automaticFailures>=2) }) return
                inspectNodes(screen,gen,true)
                if(queued>=3) return
                if (app.visualLearner.enqueue(frame)) { queued++;nextLearnAt=SystemClock.elapsedRealtime()+1800 }
            }
        }
    }
    fun close() = capture.close()
}
