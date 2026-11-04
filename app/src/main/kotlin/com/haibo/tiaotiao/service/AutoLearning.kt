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
    private val tap: suspend (Long,ScreenSnapshot,Area,VisualRule,Boolean) -> Boolean) {
    private val app get() = service.application as TiaoApplication
    private val repository get() = app.repository
    private val capture = VisualCapture(service) {service.bubbleBounds()}
    private var generation = -1L
    private var sessionStarted=0L
    private var session = ""
    private var nextCapture = 0L
    private var queued = 0
    private var nodeQueued=0
    private var imageQueued=0
    private var nextLearnAt = 0L
    private var previousSkip: Area? = null
    private var previousExplicit: Area?=null
    private var gestureObservation: Triple<String,Area,Long>?=null
    private var attempts = false
    private var reported = false
    private var pendingRule: VisualRule? = null
    private var tapAt = 0L
    private var adScene: String? = null
    private var adSeenAt = 0L
    private var baselineDone = false
    private var baselineRule: VisualRule?=null
    private var baselineBefore: ByteArray?=null
    private var baselineReference=""
    private var baselineLastAdAt=0L
    private data class BaselineFrame(val jpeg: ByteArray,val hash: String,val at: Long)
    private var baselineAfter: BaselineFrame?=null
    private var baselineRequests=0
    private var nextBaselineAt=0L
    private var captures = 0
    private var stage = "idle"
    private var lastSignals = ""
    private val homeArea = Area(0f,.88f,1f,.98f)
    private fun pause(rule: VisualRule, message: String) {
        repository.updateVisual(rule.id,rule.behavior()) {current ->
            if(current.state==VisualRuleState.PAUSED && current.lastResult.startsWith("用户")) null else
                VisualPolicy.pauseAutomatically(current,message,System.currentTimeMillis())
        }
    }
    fun summary() = "visual generation=$generation, captures=$captures, stage=$stage, signals=$lastSignals, adScene=$adScene, baselineDone=$baselineDone, queued=$queued"
    fun leaving(owner: String) {
        val rule=pendingRule ?: return
        if (owner==rule.packageName) return
        // Transient system windows (security prompts, launcher, system UI, permission sheets) are not the tap
        // opening an ad. Only another real app taking the screen right after the touch blames the target.
        if (owner in setOf("android","com.android.systemui",service.packageName) || owner.startsWith("com.miui.") ||
            owner.startsWith("com.xiaomi.") || owner.contains("permissioncontroller") || owner.contains("launcher")) {
            repository.log(rule.packageName,"点击后短暂出现系统窗口（$owner），继续检查结果");return
        }
        if (SystemClock.elapsedRealtime()-tapAt<600) {
            pause(rule,"点击后前台切到其他应用，已暂停方案")
            repository.reportDetection(rule.packageName,"点击后前台切到「$owner」，智能方案已暂停")
        } else repository.reportDetection(rule.packageName,"结果检查期间切到「$owner」，本次不计为验证成功")
        pendingRule=null
    }
    fun demo(screen: ScreenSnapshot) = screen.packageName==service.packageName &&
        (app.foregroundScreen==TiaoApplication.ForegroundScreen.VISUAL_DEMO || screen.nodes.any { it.resourceId.endsWith(":id/smart_demo_ad") })
    fun eligible(screen: ScreenSnapshot) = demo(screen) || AdRecognition.eligible(screen.packageName,repository.settings.value.packages,service.packageName,repository.settings.value.automaticLearning)
    private fun begin(gen: Long) {
        if (gen != generation) {
            generation=gen;sessionStarted=SystemClock.elapsedRealtime();session=UUID.randomUUID().toString();nextCapture=0;queued=0;nodeQueued=0;imageQueued=0;nextLearnAt=0;previousSkip=null;previousExplicit=null;attempts=false;reported=false
            adScene=null;adSeenAt=0;baselineDone=false
            baselineRule=null;baselineBefore=null;baselineReference="";baselineAfter=null
            baselineRequests=0;nextBaselineAt=0;baselineLastAdAt=0
            captures=0;stage="starting";lastSignals="";pendingRule=null;gestureObservation=null
        }
    }
    fun warmUp() = capture.warmUp()
    private fun observeBaseline(frame: VisualFrame): Boolean {
        val rule=baselineRule ?: return false
        if(repository.visualBaseline(rule.behavior())>0) {baselineDone=true;return true}
        stage="observing-natural-ending"
        val hash=frame.patch(Area(.05f,.12f,.95f,.88f))
        val distance=baselineReference.zip(hash).count {it.first!=it.second}
        if(distance<=4) {baselineLastAdAt=frame.at;baselineAfter=null;return true}
        if(distance<10) {baselineAfter=null;return true}
        // Use the last confirmed ad frame as a lower bound, not a late home frame's timestamp.
        val duration=baselineLastAdAt-adSeenAt
        val previous=baselineAfter?.takeIf {it.hash.zip(hash).count {p -> p.first!=p.second}<=5}
        if(previous==null) {baselineAfter=BaselineFrame(frame.jpeg,hash,frame.at);return true}
        if(baselineRequests<2 && SystemClock.elapsedRealtime()>=nextBaselineAt &&
            app.visualLearner.observeBaseline(rule,session,listOf(requireNotNull(baselineBefore),previous.jpeg,frame.jpeg),duration)) {
            baselineRequests++;nextBaselineAt=SystemClock.elapsedRealtime()+2000
            repository.reportDetection(rule.packageName,"已观察到未点击的画面变化，AI 正在确认是否自然进入首页")
        }
        return true
    }
    fun inspectNodes(screen: ScreenSnapshot,gen: Long,force: Boolean=false) {
        begin(gen)
        if(queued>=4 || nodeQueued>=2 || !eligible(screen) || !open(gen,screen.packageName)) return
        if(!force && repository.visualRules.value.any { AdRecognition.matchesScene(it,screen) && it.state!=VisualRuleState.PAUSED && !it.awaitingLocalReview }) return
        if(app.visualLearner.inspectNodes(screen)) {queued++;nodeQueued++;nextLearnAt=SystemClock.elapsedRealtime()+600}
    }
    suspend fun scan(initial: ScreenSnapshot, gen: Long) {
        var screen=initial
        begin(gen)
        if(!eligible(screen)) return
        if (attempts || SystemClock.elapsedRealtime()<nextCapture || !open(gen,screen.packageName)) return
        nextCapture=SystemClock.elapsedRealtime()+550
        val nodeReady = app.visualLearner.nodeAllowed(screen.packageName) && repository.vault.contains("jevKey")
        val cloudReady = app.visualLearner.allowed(screen.packageName)
        val hasActive = repository.visualRules.value.any { AdRecognition.matchesScene(it,screen) && it.active() }
        if (!demo(screen) && !nodeReady && !cloudReady && !hasActive) {
            if (!reported) {reported=true;repository.reportDetection(screen.packageName,if(repository.visionConnection.value.blocksRequests()) repository.visionConnection.value.message
                else "新界面自动学习需要配置密钥并开启云端分析；已验证规则仍可本地复用")}
            return
        }
        stage="capturing"
        val frame = try { capture.capture(screen) } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            val reason=e.message?.takeIf { it.matches(Regex("截图不可用（\\d+）")) || it=="截图请求超时" || it=="窗口比例变化，暂不使用截图坐标" } ?: e.javaClass.simpleName
            if (stage!="error-$reason") repository.reportDetection(screen.packageName,"截图或端侧识别不可用：$reason；当前未执行点击")
            stage="error-$reason"
            service.noteCapture(reason)
            return
        }
        frame.use {
            captures++
            service.noteCapture(null)
            if (!open(gen,screen.packageName)) return
            if(service.diagnosticHeldPackage==screen.packageName) {stage="diagnostic-actions-held";return}
            val fresh = current() ?: return
            if (fresh.packageName!=screen.packageName || fresh.windowId!=screen.windowId || AdRecognition.scene(fresh)!=AdRecognition.scene(screen)) {stage="stale-screen";return}
            if(fresh.nodes.isNotEmpty()) screen=fresh
            if((frame.signals.skips.isNotEmpty() || frame.signals.advertisement) && service.claimTraceSnapshot(screen.packageName)) {
                val proposal=repository.visualRules.value.filter {AdRecognition.matchesScene(it,screen)}.maxByOrNull {it.evidence.size}
                if(proposal!=null) runCatching {
                    com.haibo.tiaotiao.data.ExecutionTrace.save(service,proposal,proposal.target,frame,emptyList(),actionSent=false)
                }
            }
            if(baselineDone || observeBaseline(frame)) return
            if (adSeenAt==0L && frame.signals.advertisement) adSeenAt=frame.at
            confirmLearnedControls(frame,screen)
            if(frame.signals.advertisement || frame.signals.skips.isNotEmpty()) service.noteLaunchAd(screen.packageName)
            val signals="ad=${frame.signals.advertisement},skip=${frame.signals.skips.size},home=${frame.signals.home},timer=${frame.signals.countdown}"
            if (lastSignals!=signals) {
                lastSignals=signals;repository.reportDetection(screen.packageName,"端侧 AI 已读取画面：广告标志=${frame.signals.advertisement}，跳过目标=${frame.signals.skips.size}，首页=${frame.signals.home}")
            }
            stage="matching-${screen.fingerprint().take(8)}"
            val skip = frame.signals.skips.singleOrNull()
            val stable = skip != null && previousSkip?.near(skip)==true
            previousSkip=skip
            val explicit=VisualExecution.explicitTarget(frame.signals,previousExplicit)
            previousExplicit=if(frame.signals.home) null else VisualExecution.singleControl(frame.signals.explicitSkips)
            val matches = repository.visualRules.value.filter { AdRecognition.matchesScene(it,screen) && it.state!=VisualRuleState.PAUSED }
                .filter { it.active() || nodeReady || cloudReady || demo(screen) }
                .filter {demo(screen) || app.visualLearner.matchesBackend(it)}
                .filter { VisualPolicy.canAttempt(it,screen,frame.signals,
                    if(it.targetHash.count {bit -> bit=='1'} in 4..60) frame.targetPatch(it.target) else "",
                    frame.patch(Area(.05f,.12f,.95f,.88f))) }
            val paths=matches.map { VisualPolicy.clickableTarget(screen,frame.signals.skips.singleOrNull { a -> a.near(it.target) } ?: it.target)?.path }
            var rule=matches.filter {it.userAdjusted}.singleOrNull() ?: matches.singleOrNull() ?: matches.takeIf { paths.isNotEmpty() && paths.all { it!=null } && paths.distinct().size==1 }
                ?.maxWithOrNull(compareBy<VisualRule> {it.successfulSessions()}.thenBy {it.evidence.size})
            if(rule==null && matches.isNotEmpty() && matches.all {it.target.near(matches.first().target)})
                rule=matches.maxWithOrNull(compareBy<VisualRule> {it.successfulSessions()}.thenBy {it.evidence.size})
            if(rule==null && !demo(screen) && cloudReady && explicit!=null) {
                val proposed=VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,scene=AdRecognition.scene(screen),sceneVersion=2,
                    target=explicit,targetHash=frame.targetPatch(explicit),contextHash=frame.patch(Area(.05f,.12f,.95f,.88f)),
                    label="skip",source=VisualExecution.LOCAL_SOURCE,createdAt=System.currentTimeMillis(),tapMethod=TapMethod.GESTURE)
                val related=repository.visualRules.value.filter {VisualProposals.sameTarget(it,proposed)}
                if(related.none {VisualPolicy.userPaused(it) || !VisualPolicy.canRelearn(it,System.currentTimeMillis())}) {
                    val old=related.firstOrNull {VisualProposals.repeatedSample(it,proposed)}
                    val fresh=VisualPolicy.replaceProposal(old,proposed.copy(automaticFailures=related.maxOfOrNull {it.automaticFailures} ?: 0),System.currentTimeMillis())
                    if(fresh!=null) {
                        repository.putVisual(fresh);rule=fresh
                        repository.savePreview(fresh,frame.jpeg,explicit,com.haibo.tiaotiao.data.PreviewKind.RECOGNITION)
                        repository.reportDetection(screen.packageName,"边角文字跳过已跨帧确认，使用当前控件位置；结果仍由 AI 检查")
                    }
                }
            }
            if (demo(screen) && rule==null && skip!=null && stable && frame.signals.advertisement) {
                val node=VisualPolicy.clickableTarget(screen,skip)
                val target=node?.bounds ?: skip
                rule=VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,scene=AdRecognition.scene(screen),sceneVersion=2,target=target,
                    targetHash=frame.patch(target),label="skip",source="bundled-ocr-demo",createdAt=System.currentTimeMillis(),
                    tapMethod=if(node==null) TapMethod.GESTURE else TapMethod.ACCESSIBILITY,
                    nodeTarget=node?.let {TargetSelector(it.path,it.resourceId,it.className,it.label,it.bounds)},
                    nodeScene=if(node==null) "" else AdRecognition.nodeScene(screen))
                repository.putVisual(rule)
            }
            if(rule!=null && adScene!=rule.behavior()) {
                if(adScene!=null || adSeenAt==0L) adSeenAt=frame.at
                adScene=rule.behavior()
            }
            val ocrTarget=rule?.takeUnless {it.userAdjusted}?.let {r -> VisualPolicy.controlTarget(r.target,frame.signals.skips)}
            val refined=if(rule?.tapMethod==TapMethod.GESTURE && !rule.userAdjusted && ocrTarget==null) try {capture.refineControl(frame,rule.target)}
                catch(c: CancellationException) {throw c} catch(_: Exception) {null} else null
            val nativeGeometry=rule?.takeIf {it.tapMethod==TapMethod.GESTURE && !it.userAdjusted}?.let {
                VisualPolicy.clickableTarget(screen,it.target)?.bounds?.takeIf {bounds -> bounds.near(it.target)}
            }
            // Active schemes already have proof. The immediate pre-touch display check below
            // supplies a second current frame; do not make cached actions wait for a third one.
            // A literal skip read at the target in this very frame is already rendered; the pre-touch recheck below
            // supplies the second frame, so only unlabeled image targets wait for another capture.
            if(rule?.tapMethod==TapMethod.GESTURE && !rule.active() &&
                VisualPolicy.controlTarget(ocrTarget ?: rule.target,frame.signals.explicitSkips)==null) {
                val area=ocrTarget ?: refined ?: nativeGeometry ?: rule.target
                val previous=gestureObservation?.takeIf {it.first==rule.behavior()}
                if(previous==null || VisualPolicy.controlTarget(previous.second,listOf(area))==null) {
                    gestureObservation=Triple(rule.behavior(),area,frame.at)
                    repository.log(screen.packageName,"图像目标已匹配，等待下一帧确认控件渲染后再触控")
                    return
                }
                if(frame.at-previous.third<350) return
            }
            if (rule != null && (skip==null || stable || ocrTarget!=null || refined!=null || rule.nodeTarget?.let {AdRecognition.matchesNode(it,screen)}==true) && SystemClock.elapsedRealtime()-frame.at<1200) {
                if(repository.rules.value.any {it.packageName==screen.packageName && it.versionCode==screen.versionCode &&
                    it.status==RuleStatus.DISABLED && it.target.bounds.near(rule.target)}) {
                    repository.reportDetection(screen.packageName,"当前目标已有用户停用记录，未自动点击")
                    return
                }
                if(!rule.active() && !cloudReady && !demo(screen)) {
                    if(!reported) {reported=true;repository.reportDetection(screen.packageName,"候选已缓存；请配置所选图片服务的密钥后继续自动验证")}
                    return
                }
                val baseline=repository.visualBaseline(rule.behavior())
                if (!demo(screen) && VisualPolicy.observeNaturalEnding(rule,frame.signals.countdown,baseline,repository.baselineObservations(rule.id),
                    VisualPolicy.controlTarget(rule.target,frame.signals.explicitSkips)!=null)) {
                    // Count the session up front: an observation that never yields a baseline must not block clicking forever.
                    repository.recordBaselineObservation(rule.id)
                    baselineRule=rule;baselineBefore=frame.jpeg;baselineReference=frame.patch(Area(.05f,.12f,.95f,.88f))
                    baselineLastAdAt=frame.at
                    repository.reportDetection(screen.packageName,"本轮自动观察自然结束，不点击；即使首页有关闭标记也由 AI 检查")
                    return // Observe one natural ending; do not infer causality from a disappearing page alone.
                }
                val chosen=rule
                val clickArea=if(chosen.tapMethod==TapMethod.ACCESSIBILITY)
                    VisualPolicy.nativeTarget(chosen,screen,chosen.target)?.bounds ?: chosen.target
                    else ocrTarget ?: refined ?: nativeGeometry ?: chosen.target
                if(repository.previews.entries.value.none {it.ruleId==chosen.id && it.kind==com.haibo.tiaotiao.data.PreviewKind.RECOGNITION})
                    repository.savePreview(chosen,frame.jpeg,chosen.target,com.haibo.tiaotiao.data.PreviewKind.RECOGNITION,frame.patch(Area(.05f,.12f,.95f,.88f)))
                if(refined!=null || nativeGeometry!=null) repository.log(screen.packageName,"已用当前局部文字或控件边界校准触控中心")
                // A node rule whose node is absent may face a different ad layout (e.g. "立即进入" where the skip was).
                // Its learned coordinates are used only when a skip/close label is visible there right now.
                if(chosen.tapMethod==TapMethod.ACCESSIBILITY && VisualPolicy.nativeTarget(chosen,screen,chosen.target)==null &&
                    VisualPolicy.controlTarget(clickArea,frame.signals.skips)==null) {
                    repository.log(screen.packageName,"学到的控件不在当前画面，原位置也未见跳过文字，未点击")
                    return
                }
                val unchanged=try {capture.targetUnchanged(frame,clickArea)} catch(c: CancellationException) {throw c} catch(_: Exception) {false}
                if(!unchanged) {
                    app.touchJournal.record("PRECHECK","target_changed",screen.packageName,chosen.id,details=mapOf("comparison" to capture.lastTargetCheck))
                    gestureObservation=null
                    repository.reportDetection(screen.packageName,"点击前重新校验未通过（${capture.lastTargetCheck}），取消旧坐标并重新检查")
                    return
                }
                attempts=true
                val trace=service.claimTrace(screen.packageName)
                pendingRule=chosen
                tapAt=SystemClock.elapsedRealtime()
                if (!tap(gen,screen,clickArea,chosen,false)) {
                    pendingRule=null
                    if(trace) withContext(NonCancellable) {runCatching {
                        com.haibo.tiaotiao.data.ExecutionTrace.save(service,chosen,clickArea,frame,emptyList(),actionSent=false)
                    }}
                    repository.reportDetection(screen.packageName,"智能点击未被接受或界面已变化，停止本轮")
                    return
                }
                repository.savePreview(chosen,frame.jpeg,clickArea,com.haibo.tiaotiao.data.PreviewKind.ACTION,frame.patch(Area(.05f,.12f,.95f,.88f)))
                var tapCount=1
                var actualArea=clickArea
                repository.reportDetection(screen.packageName,if (chosen.active()) "已复用智能规则，正在自动检查结果" else "已自动尝试缓存方案，正在检查结果；无需人工确认")
                // Wait for two consecutive changed frames; the first frame can still be a launch/exit animation.
                val afterFrames=mutableListOf<VisualFrame>()
                var changedStreak=0
                try {
                    val observationDeadline=SystemClock.elapsedRealtime()+3500
                    for (index in 0 until 6) {
                        if(SystemClock.elapsedRealtime()>=observationDeadline) break
                        delay(if (index==0) 450 else 550)
                        val snapshot=currentWindow() ?: continue
                        if (snapshot.packageName!=screen.packageName) break
                        // The splash window may already be gone while its metadata is still cached.
                        val observed=try { capture.capture(snapshot,display=true) } catch (c: CancellationException) { throw c }
                            catch (_: Exception) { continue }
                        val owner=currentWindow()
                        if(owner!=null && owner.packageName!=snapshot.packageName) {observed.close();break}
                        if(owner==null || owner.windowId!=snapshot.windowId) {observed.close();continue}
                        val retryTarget=VisualExecution.retryTarget(chosen,screen,snapshot,actualArea,observed.signals,
                            frame.difference(observed),observed.at-tapAt,tapCount,nativeFallback=
                                // Node rules touch coordinates first; their learned node's ACTION_CLICK is the one retry.
                                chosen.tapMethod==TapMethod.ACCESSIBILITY)
                        val retryUnchanged=retryTarget!=null && try {capture.targetUnchanged(observed,retryTarget)}
                            catch(c: CancellationException) {throw c} catch(_: Exception) {false}
                        if(retryTarget!=null && retryUnchanged && repository.visualRules.value.any {it.id==chosen.id && it.behavior()==chosen.behavior() && !VisualPolicy.userPaused(it)}) {
                            // Re-check the current ad instead of assuming that a completed gesture closed it.
                            tapCount++;tapAt=SystemClock.elapsedRealtime()
                            repository.reportDetection(screen.packageName,"广告与关闭目标仍未变化，重新校准后补充一次短点")
                            if(tap(gen,snapshot,retryTarget,chosen,true)) {
                                actualArea=retryTarget
                                repository.savePreview(chosen,observed.jpeg,retryTarget,com.haibo.tiaotiao.data.PreviewKind.ACTION,observed.patch(Area(.05f,.12f,.95f,.88f)))
                                afterFrames.forEach {it.close()};afterFrames.clear();changedStreak=0
                                observed.close();continue
                            }
                        }
                        val changed=frame.difference(observed)>.04 && observed.signals.skips.none {it.near(clickArea)}
                        changedStreak=if(changed) changedStreak+1 else 0
                        afterFrames+=observed
                        if(afterFrames.size>2) afterFrames.removeAt(0).close()
                        val originalSkip=afterFrames.size==2 && afterFrames.any {
                            VisualPolicy.controlTarget(clickArea,it.signals.explicitSkips)!=null }
                        val originalControl=afterFrames.any {VisualPolicy.controlTarget(clickArea,it.signals.skips)!=null}
                        if(changedStreak>=2 || (cloudReady && afterFrames.size==2 && !originalSkip && !originalControl &&
                            afterFrames.all {frame.difference(it)>.01 && !it.signals.advertisement})) break
                    }
                    pendingRule=null
                    if (afterFrames.size<2) {
                        app.touchJournal.record("RESULT","no_result_frames",screen.packageName,chosen.id)
                        // Missing screenshots say nothing about whether the target itself works.
                        repository.reportDetection(screen.packageName,"点击后未取得两帧有效截图，本次不计为验证成功；保留方案")
                        return
                    }
                    val after1=afterFrames[0];val after2=afterFrames[1]
                    val beforeHash=frame.patch(homeArea);val firstHash=after1.patch(homeArea);val secondHash=after2.patch(homeArea)
                    val changed=changedStreak>=2 || (frame.difference(after1)>.04 && frame.difference(after2)>.04 &&
                        VisualPolicy.matchesHome(chosen,beforeHash,firstHash,secondHash))
                    repository.log(screen.packageName,"结果观察：方式=${chosen.tapMethod}，连续变化=$changed，末帧广告=${after2.signals.advertisement}，原位置关闭标记=${after2.signals.skips.any {it.near(clickArea)}}，首页=${after2.signals.home}")
                    if(frame.difference(after1)>.04 && frame.difference(after2)>.04 && !after1.signals.advertisement && !after2.signals.advertisement &&
                        listOf(after1,after2).none {VisualPolicy.controlTarget(clickArea,it.signals.skips)!=null}) service.announceSkip(screen.packageName)
                    val homeHash=VisualPolicy.homeTemplate(beforeHash,firstHash,secondHash)
                    val remaining=frame.signals.countdown?.times(1000L) ?: (baseline-(frame.at-adSeenAt))
                    // The first confirmed home frame establishes transition time; the second only
                    // corroborates it and may arrive later because OCR work takes time.
                    // Without a countdown or learned duration, a literal skip visible just before the touch and the screen
                    // changing within 2.5 s of that frame is the timing evidence; AI review of the frames is still required.
                    val explicitLabel=frame.signals.countdown==null && baseline<=0 && VisualPolicy.controlTarget(clickArea,frame.signals.explicitSkips)!=null
                    val early=(remaining>=2000 && after1.at-frame.at<remaining-300) || (explicitLabel && after1.at-frame.at<2500)
                    val basis=if (frame.signals.countdown!=null) "countdown" else if (baseline>0) "observed_baseline" else if (explicitLabel) "explicit_label" else "unknown"
                    app.touchJournal.record("RESULT",if(after2.signals.skips.any {it.near(clickArea)}) "ad_still_visible" else "result_unknown",
                        screen.packageName,chosen.id,requestId=service.touchExecutions.lastOrNull {it.ruleId==chosen.id}?.requestId.orEmpty(),details=mapOf(
                            "changed" to changed.toString(),"advertisement" to after2.signals.advertisement.toString(),"home" to after2.signals.home.toString(),
                            "early" to early.toString(),"timingBasis" to basis,"firstFrameMs" to (after1.at-frame.at).toString()))
                    repository.log(screen.packageName,"时序检查：$basis，保守剩余 $remaining ms，首个结果 ${after1.at-frame.at} ms")
                    if (chosen.source=="bundled-ocr-demo") {
                        repository.putVisual(VisualPolicy.record(chosen,session,early,changed,true,System.currentTimeMillis(),basis))
                        repository.reportDetection(screen.packageName,"演示自动检查完成，无需云端或人工确认")
                    } else if (chosen.active() && changed && (early || chosen.manuallyConfirmed()) && !after1.signals.advertisement && !after2.signals.advertisement &&
                        ((after1.signals.home && after2.signals.home) || VisualPolicy.matchesHome(chosen,beforeHash,firstHash,secondHash) ||
                        (chosen.homeScene.isNotEmpty() && after1.screen.fingerprint()==chosen.homeScene && after2.screen.fingerprint()==chosen.homeScene))) {
                        app.touchJournal.record("RESULT",if(early) "ad_closed_early" else "result_unknown",screen.packageName,chosen.id,
                            requestId=service.touchExecutions.lastOrNull {it.ruleId==chosen.id}?.requestId.orEmpty())
                        repository.reportDetection(screen.packageName,if(early) "智能规则本地复用及提前关闭检查通过，未请求云端模型"
                            else "已观察到广告结束，缺少提前关闭的时序证据；保留人工确认，不将自然结束记为点击成功")
                    } else if (cloudReady) {
                        repository.reportDetection(screen.packageName,if(changed) "已观察到连续页面变化，AI 正在自动复核结果" else "本地结果不明确，AI 正在检查广告是否仍然存在")
                        val homeScene=after2.screen.fingerprint().takeIf { after2.screen.complete && it!=screen.fingerprint() }.orEmpty()
                        app.visualLearner.verify(chosen,session,listOf(frame.jpeg,after1.jpeg,after2.jpeg),early,true,homeScene,homeHash,basis,
                            visualChange=frame.difference(after1)>.01 && frame.difference(after2)>.01,
                            localClosed=frame.difference(after1)>.04 && frame.difference(after2)>.04 && (after1.signals.home || after2.signals.home) &&
                                listOf(after1,after2).none {it.signals.advertisement || VisualPolicy.controlTarget(clickArea,it.signals.skips)!=null},
                            originalTargetStillVisible=listOf(after1,after2).any {VisualPolicy.controlTarget(clickArea,it.signals.explicitSkips)!=null})
                    } else {
                        if(chosen.active() && !chosen.manuallyConfirmed()) {
                            pause(chosen,"本地结果证据不足，等待重新识别")
                            repository.reportDetection(screen.packageName,"已按本地规则关闭，但结果证据不足且云端未就绪，暂时停止自动复用")
                        } else repository.reportDetection(screen.packageName,if(chosen.manuallyConfirmed()) "本次结果证据不足，保留人工确认；当前不重复点击" else "已观察到页面变化；自动启用仍需独立 AI 结果复核，保留候选")
                    }
                } finally {
                    if(trace) withContext(NonCancellable) {
                        runCatching {com.haibo.tiaotiao.data.ExecutionTrace.save(service,chosen,actualArea,frame,afterFrames,tapCount=tapCount)}
                            .onFailure {repository.reportDetection(screen.packageName,"本次诊断样本未能保存")}
                    }
                    afterFrames.forEach { it.close() }
                }
            } else if (rule==null && !demo(screen) && !frame.signals.home && queued<4 &&
                // A frame with no ad badge, skip text or countdown gets one look; a second one only with such a hint.
                imageQueued<(if(frame.signals.advertisement || frame.signals.skips.isNotEmpty() || frame.signals.countdown!=null) 2 else 1) &&
                (frame.signals.advertisement || AdRecognition.candidates(screen).isNotEmpty() || frame.patch(Area(.1f,.15f,.9f,.8f)).count {it=='1'} in 4..60) &&
                SystemClock.elapsedRealtime()>=nextLearnAt) {
                // The first launch snapshot can precede the cached target's node/label. Give the
                // local path one more capture before spending a model call on a known scene.
                if(SystemClock.elapsedRealtime()-sessionStarted<1200 && repository.visualRules.value.any {
                    AdRecognition.matchesScene(it,screen) && it.state!=VisualRuleState.PAUSED && !it.awaitingLocalReview}) return
                val waiting=repository.visualRules.value.filter {AdRecognition.matchesScene(it,screen) && it.state==VisualRuleState.PAUSED &&
                    !VisualPolicy.canRelearn(it,System.currentTimeMillis())}.any {paused ->
                    VisualPolicy.canAttempt(paused.copy(state=VisualRuleState.CANDIDATE,awaitingLocalReview=false),screen,frame.signals,
                        if(paused.targetHash.count {it=='1'} in 4..60) frame.targetPatch(paused.target) else "",frame.patch(Area(.05f,.12f,.95f,.88f)))
                }
                if(waiting) {return}
                if(explicit==null && VisualExecution.singleControl(frame.signals.explicitSkips)!=null &&
                    SystemClock.elapsedRealtime()-sessionStarted<1600) return
                inspectNodes(screen,gen,true)
                if(queued>=4) return
                if (app.visualLearner.enqueue(frame)) { queued++;imageQueued++;nextLearnAt=SystemClock.elapsedRealtime()+900}
            }
        }
    }
    /** Full-screen OCR misses small translucent buttons over busy creatives. Where this scene already learned a
     * skip/close control, read that spot again from a magnified crop and add a confirmed label to the frame. */
    private suspend fun confirmLearnedControls(frame: VisualFrame,screen: ScreenSnapshot) {
        // Automatically paused targets are included: a confirmed label there lets a fresh local proposal recover them.
        val learned=repository.visualRules.value.filter {AdRecognition.matchesScene(it,screen) && !VisualPolicy.userPaused(it) &&
            VisualPolicy.controlTarget(it.target,frame.signals.skips)==null}.sortedByDescending {it.createdAt}
            .map {it.target}.distinctBy {"${(it.left*40).toInt()}:${(it.top*80).toInt()}"}.take(6)
        val found=learned.mapNotNull {target -> try {capture.refineControl(frame,target)} catch(c: CancellationException) {throw c} catch(_: Exception) {null}}
        if(found.isEmpty()) return
        // refineControl accepts literal skip or close text only, so these are explicit labels at a learned position.
        frame.signals=frame.signals.copy(skips=(frame.signals.skips+found).distinct(),explicitSkips=(frame.signals.explicitSkips+found).distinct())
    }
    /** Whether the current app screen shows its bottom navigation (a home page); null when it cannot be captured. */
    suspend fun homeVisible(): Boolean? {
        // Window ownership can lag right after the app is brought back to the front.
        repeat(4) {attempt ->
            if(attempt>0) delay(400)
            val screen=currentWindow() ?: return@repeat
            try {return capture.capture(screen,display=true).use {it.signals.home}} catch(c: CancellationException) {throw c} catch(_: Exception) {}
        }
        return null
    }
    fun close() = capture.close()
}
