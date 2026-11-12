package com.haibo.tiaotiao.data

import android.content.Context
import android.graphics.BitmapFactory
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.network.VisualClient
import com.haibo.tiaotiao.network.JevClient
import com.haibo.tiaotiao.network.modelErrorMessage
import com.haibo.tiaotiao.service.VisualFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable private data class PendingImage(val key: String, val screen: ScreenSnapshot, val createdAt: Long, val model: String,
    val attempts: Int = 0, val completed: Boolean = false, val engineVersion: Int = 1, val contextHash: String="",
    val provider: VisionProvider=VisionProvider.VERCEL_AI_GATEWAY,val signals: VisualSignals?=null)

/** Learning survives leaving the app's splash session. Saved images are OCR-masked, bounded and temporary. */
class VisualLearner(context: Context, private val repository: AppRepository,
    private val jevFactory: () -> JevClient = { JevClient() },
    private val visionFactory: () -> VisualClient = { VisualClient() }) {
    private val folder = File(context.filesDir,"visual_pending").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val permits = Semaphore(2)
    private val nodePermits=Semaphore(2)
    private val client by lazy { visionFactory() }
    private val jev by lazy { jevFactory() }
    private val finished = context.getSharedPreferences("visual_cache",Context.MODE_PRIVATE)
    private fun selectedCloud(pkg: String) = repository.settings.value.let {it.enabled && it.automaticLearning && it.cloudEnabled && pkg in it.packages}
    fun nodeAllowed(pkg: String) = allowed(pkg) && repository.settings.value.visionProvider!=VisionProvider.DEEPSEEK_OFFICIAL
    fun allowed(pkg: String) = selectedCloud(pkg) && repository.visionRoute()!=null
    /** Measured on 44 labeled launch screens: Jev located every skip control and never called a home page an ad, but
     * reports 0.66–0.88 for real ads; DeepSeek reports 0.95–0.99 for everything, including one home-page miss. */
    private fun minConfidence(provider: VisionProvider,model: String)=if(jev(provider,model)) .6 else .9
    /** Jev's result reviews were right at 0.75–0.92 on real launches; the local "target still visible" and
     * "screen changed" checks below still veto a home verdict on their own. */
    private fun reviewConfidence(provider: VisionProvider?,model: String)=if(provider!=null && jev(provider,model)) .7 else .9
    private fun jev(provider: VisionProvider,model: String)=provider==VisionProvider.JEV_OFFICIAL || model==com.haibo.tiaotiao.network.JevImageProtocol.MODEL
    private val officialSources=listOf(VisionProvider.JEV_OFFICIAL,VisionProvider.DEEPSEEK_OFFICIAL).map {"${it.name}:${it.defaultModel}"}.toSet()
    private var lastReprobe=0L
    private var lastWarm=0L
    /** Called when a selected app starts: the ad appears a second or two later, by when the connection is ready. */
    fun warm(pkg: String) {
        if(!allowed(pkg) || System.currentTimeMillis()-lastWarm<20_000) return
        lastWarm=System.currentTimeMillis()
        repository.visionRoute()?.let {provider -> runCatching {client.warm(provider)}}
    }
    /** A blocked provider (typically HTTP 451 while the proxy is off) is re-tested in the background every 5 minutes. */
    private fun reprobeBlocked() {
        if(!repository.visionConnection.value.blocksRequests() || System.currentTimeMillis()-lastReprobe<300_000) return
        lastReprobe=System.currentTimeMillis()
        scope.launch {runCatching {com.haibo.tiaotiao.network.testVisionConnection(repository)}}
    }
    fun matchesBackend(rule: VisualRule) = repository.settings.value.let {s -> rule.active() || rule.source==VisualExecution.LOCAL_SOURCE ||
        if(rule.nodeTarget!=null) nodeAllowed(rule.packageName) && rule.source=="jev:${s.jevProvider}:${s.model}"
        else rule.source=="${s.visionProvider.name}:${s.visionModel}" ||
            // Proposals learned through the fallback provider stay usable after the selection recovers, and vice versa.
            (rule.source in officialSources && s.visionProvider.name+":"+s.visionModel in officialSources) ||
            (s.visionProvider==VisionProvider.VERCEL_AI_GATEWAY && rule.source=="vercel:${s.visionModel}")}
    private fun cached(key: String) = System.currentTimeMillis()-finished.getLong(key,0)<finished.getLong("ttl-$key",600_000)
    private fun recoveryStamp(screen: ScreenSnapshot)=repository.visualRules.value.filter {AdRecognition.matchesScene(it,screen) && (it.state==VisualRuleState.PAUSED || it.awaitingLocalReview)}
        .sortedBy {it.id}.joinToString {"${it.id}:${it.automaticFailures}:${it.retryAfter}:${it.learningRevision}"}
    init {
        scope.launch(start=CoroutineStart.UNDISPATCHED) { repository.settings.map {it.copy(showCheckBubble=false,enhancedTouch=false,syncUrl="",syncEtag="")}.distinctUntilChanged().collect { s ->
            val obsolete=jobs.values.toList()
            obsolete.forEach {it.cancel()};obsolete.joinAll()
            if (!s.enabled || !s.automaticLearning || !s.cloudEnabled)
                Unit
            else resume()
        } }
    }
    /** Starts on the first bounded node snapshot, without waiting for OCR or a literal skip label. */
    fun inspectNodes(screen: ScreenSnapshot): Boolean {
        if (!nodeAllowed(screen.packageName) || AdRecognition.candidates(screen).isEmpty()) return false
        val settings=repository.settings.value
        val key="node-"+digest("${AdRecognition.nodeScene(screen)}|${settings.jevProvider}|${settings.model}|${recoveryStamp(screen)}")
        if (jobs.containsKey(key) || cached(key) ||
            System.currentTimeMillis()<finished.getLong("retry-$key",0)) return false
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try { nodePermits.withPermit {
                if (!nodeAllowed(screen.packageName)) return@withPermit
                val credential=repository.analysisKey(settings.jevProvider,settings.model) ?: return@withPermit
                repository.reportDetection(screen.packageName,"Jev 已提前分析开屏控件，包含无文字图标与关闭控件")
                val target=jev.inspect(screen,credential,settings.model,settings.jevProvider)
                val currentSettings=repository.settings.value
                if (!nodeAllowed(screen.packageName) || currentSettings.model!=settings.model || currentSettings.jevProvider!=settings.jevProvider) return@withPermit
                if (target!=null) {
                    val result=saveProposal(VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,
                        scene=AdRecognition.scene(screen),sceneVersion=2,target=target.bounds,targetHash="0".repeat(64),tapMethod=TapMethod.ACCESSIBILITY,
                        nodeTarget=TargetSelector(target.path,target.resourceId,target.className,target.label,target.bounds),
                        nodeScene=AdRecognition.nodeScene(screen),
                        label=if(SignalText.hasSkip(target.label)) "skip" else "close_ad",
                        source="jev:${settings.jevProvider}:${settings.model}",createdAt=System.currentTimeMillis()))
                    repository.reportDetection(screen.packageName,result.message)
                } else repository.reportDetection(screen.packageName,"Jev 未确认安全关闭目标，继续由端侧与视觉识别检查")
                finished.edit().putLong(key,System.currentTimeMillis()).remove("retry-$key").apply()
            } } catch(c: CancellationException) { throw c }
            catch(e: Exception) {
                repository.recordVisionFailure(settings,e.message.orEmpty())
                finished.edit().putLong("retry-$key",System.currentTimeMillis()+5000).apply()
                repository.reportDetection(screen.packageName,modelErrorMessage(e))
            } finally { jobs.remove(key) }
        }
        if(jobs.putIfAbsent(key,job)!=null) {job.cancel();return false}
        job.start();return true
    }
    private data class ProposalResult(val rule: VisualRule?,val message: String)
    private fun saveProposal(rule: VisualRule): ProposalResult = synchronized(repository) {
        val sceneRules=repository.visualRules.value.filter { VisualProposals.sameScene(it,rule) }
        if(sceneRules.any {VisualPolicy.userPaused(it) && VisualProposals.sameTarget(it,rule)}) return@synchronized ProposalResult(null,"目标已有用户停用记录，未保存新方案")
        val old=sceneRules.firstOrNull {VisualProposals.repeatedSample(it,rule) && (it.source==rule.source || it.awaitingLocalReview || it.active())}
        val replacement=VisualPolicy.replaceProposal(old,rule,System.currentTimeMillis())
        if(replacement==null) return@synchronized if(old!=null && old.state!=VisualRuleState.PAUSED)
            ProposalResult(old,"已有匹配方案，继续本地检查") else ProposalResult(null,"方案正在等待重试，尚未恢复点击")
        repository.putVisual(replacement)
        ProposalResult(replacement,if(old?.state==VisualRuleState.PAUSED) "AI 已重新识别并恢复候选，下次匹配时重新检查" else "视觉方案已缓存；当前或下次匹配时自动尝试")
    }
    fun resume() {
        folder.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            var pending = runCatching { ruleJson.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            if (pending!=null && allowed(pending.screen.packageName) && (pending.engineVersion!=3 || pending.model!=repository.settings.value.visionModel || pending.provider!=repository.settings.value.visionProvider)) {
                pending=pending.copy(engineVersion=3,attempts=0,completed=false,model=repository.settings.value.visionModel,provider=repository.settings.value.visionProvider)
                file.writeText(ruleJson.encodeToString(pending))
            }
            if (pending == null || System.currentTimeMillis()-pending.createdAt > 86_400_000) {
                File(folder,file.nameWithoutExtension+".jpg").delete();file.delete()
            } else if (!pending.completed && pending.attempts < 3 && allowed(pending.screen.packageName)) launch(pending)
        }
    }
    suspend fun enqueue(frame: VisualFrame): Boolean = withContext(Dispatchers.IO) { enqueueWorker(frame) }
    private fun enqueueWorker(frame: VisualFrame): Boolean {
        reprobeBlocked()
        if (!allowed(frame.screen.packageName)) return false
        folder.listFiles().orEmpty().filter { it.extension=="json" && !jobs.containsKey(it.nameWithoutExtension) }.forEach { file ->
            val old=runCatching { ruleJson.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            if (old==null || System.currentTimeMillis()-old.createdAt>86_400_000) {
                File(folder,file.nameWithoutExtension+".jpg").delete();file.delete()
            }
        }
        val provider=repository.visionRoute() ?: return false
        val model=repository.visionModel(provider)
        // OCR boxes jitter between frames; a coarse grid keeps one screen from being sent again every capture.
        val skips=frame.signals.skips.joinToString(";") {"${(it.left*25).toInt()},${(it.top*50).toInt()}"}
        val key = digest("${AdRecognition.scene(frame.screen)}|${frame.patch(Area(.05f,.12f,.95f,.88f))}|$skips|$provider|$model|${repository.settings.value.visionRevision}|${recoveryStamp(frame.screen)}")
        if (jobs.containsKey(key) || cached(key) ||
            System.currentTimeMillis()<finished.getLong("retry-$key",0)) return false
        val records=folder.listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.lastModified() }
        if (records.size>=8) {
            val old=records.firstOrNull { !jobs.containsKey(it.nameWithoutExtension) } ?: return false
            File(folder,old.nameWithoutExtension+".jpg").delete();old.delete()
        }
        val pending = PendingImage(key,frame.screen,System.currentTimeMillis(),model,engineVersion=3,
            contextHash=frame.patch(Area(.05f,.12f,.95f,.88f)),provider=provider,signals=frame.signals)
        File(folder,"$key.jpg").writeBytes(frame.jpeg)
        File(folder,"$key.json").writeText(ruleJson.encodeToString(pending))
        repository.reportDetection(frame.screen.packageName,"已自动采样，视觉 AI 正在后台学习；页面结束后继续分析")
        launch(pending); return true
    }
    private fun launch(p: PendingImage) {
        if (jobs.containsKey(p.key) || !File(folder,"${p.key}.jpg").exists()) return
        val config=repository.settings.value
        var used: VisionProvider?=null
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try { permits.withPermit {
                if (!allowed(p.screen.packageName)) return@withPermit
                val image = File(folder,"${p.key}.jpg").readBytes()
                val started = System.nanoTime()
                // Routed at call time: a sample queued before a block goes to the fallback instead of waiting.
                val provider=repository.visionRoute() ?: return@withPermit
                val model=repository.visionModel(provider)
                val credential=repository.routeKey(provider,config) ?: return@withPermit
                used=provider
                val decision = client.recognize(image,credential,model,provider,p.signals)
                decision.inputTokens?.let {repository.log(p.screen.packageName,"${provider.displayName} 图片识别：${decision.elapsedMs} ms，输入 $it tokens")}
                if (!allowed(p.screen.packageName) || !repository.sameVisionRevision(config)) return@withPermit
                val area = decision.target(minConfidence(provider,model))
                val elapsed = (System.nanoTime()-started)/1_000_000
                if (area == null) {
                    repository.reportDetection(p.screen.packageName,"视觉 AI 未确认跳过目标（${elapsed} ms，广告=${decision.advertisement}，置信=${decision.confidence}），已缓存样本")
                }
                else {
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image,0,image.size))
                    val frame = VisualFrame(p.screen,bitmap,VisualSignals(false,null,emptyList(),false),image,0)
                    val hash = frame.use { it.patch(area) }
                    val rule = VisualRule(packageName=p.screen.packageName,versionCode=p.screen.versionCode,scene=AdRecognition.scene(p.screen),sceneVersion=2,
                        target=area,targetHash=hash,label=decision.label,source="${provider.name}:$model",createdAt=System.currentTimeMillis(),contextHash=p.contextHash,tapMethod=TapMethod.GESTURE)
                    val result=saveProposal(rule)
                    result.rule?.let {repository.savePreview(it,image,it.target,PreviewKind.RECOGNITION)}
                    repository.reportDetection(p.screen.packageName,"${result.message}（${elapsed} ms）")
                }
                val contradicted=p.signals?.let {it.advertisement && it.skips.isNotEmpty()}==true
                // Retry soon only when the model saw an ad but placed no usable control; a clear "no ad" holds for 15 s.
                val ttl=if(area!=null) 600_000L else if(decision.advertisement || contradicted) 700L else 15_000L
                finished.edit().putLong(p.key,System.currentTimeMillis()).putLong("ttl-${p.key}",ttl).remove("retry-${p.key}").apply()
                if (area != null) {
                    File(folder,"${p.key}.json").delete();File(folder,"${p.key}.jpg").delete()
                } else File(folder,"${p.key}.json").writeText(ruleJson.encodeToString(p.copy(completed=true)))
            } }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                used?.let {provider ->
                    repository.recordVisionFailure(config,e.message.orEmpty(),provider)
                    if(e is java.io.IOException) repository.noteVisionNetworkFailure(provider)
                }
                // The selection failed and the other provider is configured: route this sample there right away.
                val quick=used==config.visionProvider && repository.fallbackVision()!=null && repository.visionRoute()!=used
                finished.edit().putLong("retry-${p.key}",System.currentTimeMillis()+30_000).apply()
                val safe = e.message?.takeIf { it.matches(Regex("(?:Vercel 视觉|Jev 图片|DeepSeek 官方直连图片)请求失败（HTTP \\d{3}）")) ||
                    it.matches(Regex("视觉响应不完整（finish=[a-z_]+，chars=\\d+）")) } ?: "网络或响应结构错误（${e.javaClass.simpleName}）"
                repository.reportDetection(p.screen.packageName,"视觉学习未完成：$safe；已缓存待重试")
                val retry = p.copy(attempts=p.attempts+1)
                File(folder,"${p.key}.json").writeText(ruleJson.encodeToString(retry))
                if (retry.attempts < 3) scope.launch { delay(if(quick) 0 else 30_000); if (allowed(p.screen.packageName)) launch(retry) }
            }
            finally { jobs.remove(p.key) }
        }
        if (jobs.putIfAbsent(p.key,job)==null) job.start() else job.cancel()
    }
    fun clearPending() {
        jobs.values.forEach { it.cancel() }
        folder.listFiles().orEmpty().forEach { it.delete() }
        finished.edit().clear().apply()
    }
    fun close() {scope.cancel()}
    suspend fun closeAndJoin() {scope.coroutineContext[Job]?.cancelAndJoin()}
    fun observeBaseline(rule: VisualRule, session: String, images: List<ByteArray>, duration: Long): Boolean {
        if(!allowed(rule.packageName) || duration !in 1000..15_000) return false
        val key="baseline-$session"
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {permits.withPermit {
                val config=repository.settings.value
                if(!matchesBackend(rule)) return@withPermit
                val provider=repository.visionRoute() ?: return@withPermit
                val model=repository.visionModel(provider)
                val credential=repository.routeKey(provider,config) ?: return@withPermit
                val verdict=client.verify(images,credential,model,attempted=false,provider=provider)
                verdict.inputTokens?.let {repository.log(rule.packageName,"${provider.displayName} 自然结束复核：${verdict.elapsedMs} ms，输入 $it tokens")}
                if(!allowed(rule.packageName) || !repository.sameVisionRevision(config)) return@withPermit
                synchronized(repository) {
                    val current=repository.visualRules.value.find {it.id==rule.id && it.behavior()==rule.behavior()} ?: return@synchronized
                    if(current.state==VisualRuleState.PAUSED) return@synchronized
                    if(verdict.accepted(reviewConfidence(provider,model))) {
                        repository.recordVisualBaseline(current.behavior(),duration)
                        repository.reportDetection(rule.packageName,"AI 已确认自然结束，记录保守显示时长 ${duration} ms；下次自动验证关闭效果")
                    } else repository.reportDetection(rule.packageName,"AI 尚未确认自然结束，继续观察；不计为跳过成功")
                }
            }} catch(c: CancellationException) {throw c}
            catch(_: Exception) {repository.reportDetection(rule.packageName,"自然结束复核未完成，保留方案等待下次观察")}
            finally {jobs.remove(key)}
        }
        if(jobs.putIfAbsent(key,job)!=null) {job.cancel();return false}
        job.start();return true
    }
    fun verify(rule: VisualRule, session: String, images: List<ByteArray>, early: Boolean, sameApp: Boolean, homeScene: String, homeHash: String, basis: String,
        visualChange: Boolean=true,originalTargetStillVisible: Boolean=false,localClosed: Boolean=false) {
        val config=repository.settings.value
        val key="verify-$session"
        var used: VisionProvider?=null
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                if (!allowed(rule.packageName) || !matchesBackend(rule)) return@launch
                val verdict = permits.withPermit {
                    if(!allowed(rule.packageName) || !repository.sameVisionRevision(config)) return@launch
                    val provider=repository.visionRoute() ?: return@launch
                    val credential=repository.routeKey(provider,config) ?: return@launch
                    used=provider
                    try {client.verify(images,credential,repository.visionModel(provider),provider=provider)}
                    catch(c: CancellationException) {throw c}
                    catch(e: Exception) {
                        // One result review is worth a second provider: retry once on the fallback when the selection fails.
                        repository.recordVisionFailure(config,e.message.orEmpty(),provider)
                        if(e is java.io.IOException) repository.noteVisionNetworkFailure(provider)
                        val fallback=repository.visionRoute()?.takeIf {it!=provider} ?: throw e
                        used=fallback
                        client.verify(images,repository.routeKey(fallback,config) ?: throw e,repository.visionModel(fallback),provider=fallback)
                    }
                }
                verdict.inputTokens?.let {repository.log(rule.packageName,"${used?.displayName} 点击结果复核：${verdict.elapsedMs} ms，输入 $it tokens")}
                repository.log(rule.packageName,"AI 结果复核：首页=${verdict.normalHome}，广告消失=${verdict.advertisementGone}，详情页=${verdict.landingPage}，系统提示=${verdict.systemPrompt}，置信=${verdict.confidence}，时序=$early")
                if (!allowed(rule.packageName) || !repository.sameVisionRevision(config)) return@launch
                val minimum=reviewConfidence(used,used?.let {repository.visionModel(it)}.orEmpty())
                val evidence=ReviewEvidence(verdict.certain(minimum),verdict.accepted(minimum),originalTargetStillVisible,visualChange,localClosed)
                if(evidence.certain && !evidence.accepted && localClosed) {
                    // The model contradicted a clearly closed screen (a lodging app's home reuses the ad's banner, so the
                    // review called it a detail page every time). Count this launch on local evidence; two such launches
                    // promote the rule like two agreed reviews, after which local checks replace the AI review.
                    val updated=repository.updateVisual(rule.id,rule.behavior()) {current ->
                        if(current.state==VisualRuleState.PAUSED) null else
                            VisualPolicy.record(current,session,early,true,sameApp,System.currentTimeMillis(),basis,local=true)
                                .copy(homeHash=homeHash.ifEmpty {current.homeHash})
                    } ?: return@launch
                    repository.reportDetection(rule.packageName,if(updated.active()) "本地证据两次确认广告已关闭，智能规则已启用（AI 判断有分歧）"
                        else "AI 判断广告未关闭，但画面已进入应用首页且原跳过按钮消失；按本地证据记录（${updated.successfulSessions()}/2）")
                    return@launch
                }
                if(evidence.outcome()==ReviewOutcome.IGNORED) {
                    repository.reportDetection(rule.packageName,when {
                        !evidence.certain -> "AI 结果不确定，保留现有方案；不增加成功或失败次数"
                        evidence.accepted && originalTargetStillVisible -> "原跳过按钮仍在，未计为成功；忽略 AI 首页误判"
                        evidence.accepted -> "前后画面未变化，未计为跳过成功；AI 首页判断不能单独作为依据"
                        // Observed on a lodging app: the model called its search home a detail page and paused a working rule.
                        else -> "AI 判断广告未关闭，但画面已进入应用首页且原跳过按钮消失；不计为失败，保留方案"
                    })
                    return@launch
                }
                val updated = repository.updateVisual(rule.id,rule.behavior()) {current ->
                    if(current.state==VisualRuleState.PAUSED) null else
                        VisualPolicy.record(current,session,early,verdict.accepted(minimum),sameApp,System.currentTimeMillis(),basis)
                            .copy(homeScene=homeScene,homeHash=if (verdict.accepted(minimum) && sameApp && homeHash.isNotEmpty()) homeHash else current.homeHash)
                } ?: return@launch
                repository.reportDetection(rule.packageName,if (updated.active()) "AI 与时序检查通过，智能规则已自动启用" else updated.lastResult)
            } catch(c: CancellationException) {throw c}
            catch (e: Exception) {used?.let {repository.recordVisionFailure(config,e.message.orEmpty(),it)};repository.reportDetection(rule.packageName,"自动结果检查未完成（${e.javaClass.simpleName}），未晋升为已验证规则")}
            finally {jobs.remove(key)}
        }
        if(jobs.putIfAbsent(key,job)==null) job.start() else job.cancel()
    }
}
