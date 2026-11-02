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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable private data class PendingImage(val key: String, val screen: ScreenSnapshot, val createdAt: Long, val model: String,
    val attempts: Int = 0, val completed: Boolean = false, val engineVersion: Int = 1, val contextHash: String="")

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
    fun nodeAllowed(pkg: String) = repository.settings.value.let { it.enabled && it.automaticLearning && it.cloudEnabled && pkg in it.packages }
    fun allowed(pkg: String) = nodeAllowed(pkg) && repository.settings.value.jevProvider==JevProvider.VERCEL_AI_GATEWAY
    init {
        scope.launch { repository.settings.collect { s ->
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
        val key="node-"+digest("${AdRecognition.nodeScene(screen)}|${settings.jevProvider}|${settings.model}")
        if (jobs.containsKey(key) || System.currentTimeMillis()-finished.getLong(key,0)<600_000 ||
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
                    saveProposal(VisualRule(packageName=screen.packageName,versionCode=screen.versionCode,
                        scene=AdRecognition.scene(screen),sceneVersion=2,target=target.bounds,targetHash="0".repeat(64),tapMethod=TapMethod.ACCESSIBILITY,
                        nodeTarget=TargetSelector(target.path,target.resourceId,target.className,target.label,target.bounds),
                        nodeScene=AdRecognition.nodeScene(screen),
                        label=if(SignalText.hasSkip(target.label)) "skip" else "close_ad",
                        source="jev:${settings.jevProvider}:${settings.model}",createdAt=System.currentTimeMillis()))
                    repository.reportDetection(screen.packageName,"Jev 关闭方案已缓存；当前或下次匹配并取得广告证据后自动尝试")
                } else repository.reportDetection(screen.packageName,"Jev 未确认安全关闭目标，继续由端侧与视觉识别检查")
                finished.edit().putLong(key,System.currentTimeMillis()).remove("retry-$key").apply()
            } } catch(c: CancellationException) { throw c }
            catch(e: Exception) {
                finished.edit().putLong("retry-$key",System.currentTimeMillis()+5000).apply()
                repository.reportDetection(screen.packageName,modelErrorMessage(e))
            } finally { jobs.remove(key) }
        }
        if(jobs.putIfAbsent(key,job)!=null) {job.cancel();return false}
        job.start();return true
    }
    private fun saveProposal(rule: VisualRule) = synchronized(repository) {
        val sceneRules=repository.visualRules.value.filter { it.packageName==rule.packageName && it.versionCode==rule.versionCode && it.scene==rule.scene }
        if(sceneRules.any {it.state==VisualRuleState.PAUSED && it.lastResult.startsWith("用户")}) return
        val old=sceneRules.firstOrNull {it.target.near(rule.target) && (
            (rule.nodeTarget!=null && it.nodeTarget==null && !it.active()) ||
            (rule.nodeTarget!=null && it.nodeScene==rule.nodeScene) ||
            (rule.nodeTarget==null && it.contextHash==rule.contextHash))}
        if(old?.active()==true || (old!=null && old.state!=VisualRuleState.PAUSED && !old.awaitingLocalReview &&
            (rule.nodeTarget==null || old.nodeTarget!=null))) return
        if(old!=null && old.automaticFailures>=2) return
        repository.putVisual(if(old==null) rule else rule.copy(id=old.id,automaticFailures=old.automaticFailures))
    }
    fun resume() {
        folder.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            var pending = runCatching { ruleJson.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            if (pending!=null && allowed(pending.screen.packageName) && (pending.engineVersion!=2 || pending.model!=repository.settings.value.visionModel)) {
                pending=pending.copy(engineVersion=2,attempts=0,completed=false,model=repository.settings.value.visionModel)
                file.writeText(ruleJson.encodeToString(pending))
            }
            if (pending == null || System.currentTimeMillis()-pending.createdAt > 86_400_000) {
                File(folder,file.nameWithoutExtension+".jpg").delete();file.delete()
            } else if (!pending.completed && pending.attempts < 3 && allowed(pending.screen.packageName)) launch(pending)
        }
    }
    suspend fun enqueue(frame: VisualFrame): Boolean = withContext(Dispatchers.IO) { enqueueWorker(frame) }
    private fun enqueueWorker(frame: VisualFrame): Boolean {
        if (!allowed(frame.screen.packageName)) return false
        folder.listFiles().orEmpty().filter { it.extension=="json" && !jobs.containsKey(it.nameWithoutExtension) }.forEach { file ->
            val old=runCatching { ruleJson.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            if (old==null || System.currentTimeMillis()-old.createdAt>86_400_000) {
                File(folder,file.nameWithoutExtension+".jpg").delete();file.delete()
            }
        }
        val model = repository.settings.value.visionModel
        val key = digest("${AdRecognition.scene(frame.screen)}|${frame.patch(Area(.05f,.12f,.95f,.88f))}|${frame.signals.skips}|$model")
        if (jobs.containsKey(key) || System.currentTimeMillis()-finished.getLong(key,0)<600_000 ||
            System.currentTimeMillis()<finished.getLong("retry-$key",0)) return false
        val records=folder.listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.lastModified() }
        if (records.size>=8) {
            val old=records.firstOrNull { !jobs.containsKey(it.nameWithoutExtension) } ?: return false
            File(folder,old.nameWithoutExtension+".jpg").delete();old.delete()
        }
        val pending = PendingImage(key,frame.screen,System.currentTimeMillis(),model,engineVersion=2,
            contextHash=frame.patch(Area(.05f,.12f,.95f,.88f)))
        File(folder,"$key.jpg").writeBytes(frame.jpeg)
        File(folder,"$key.json").writeText(ruleJson.encodeToString(pending))
        repository.reportDetection(frame.screen.packageName,"已自动采样，视觉 AI 正在后台学习；页面结束后继续分析")
        launch(pending); return true
    }
    private fun launch(p: PendingImage) {
        if (jobs.containsKey(p.key) || !File(folder,"${p.key}.jpg").exists()) return
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try { permits.withPermit {
                if (!allowed(p.screen.packageName)) return@withPermit
                val image = File(folder,"${p.key}.jpg").readBytes()
                val started = System.nanoTime()
                val credential=repository.analysisKey(JevProvider.VERCEL_AI_GATEWAY,p.model,true) ?: return@withPermit
                val decision = client.recognize(image,credential,p.model)
                if (!allowed(p.screen.packageName) || repository.settings.value.visionModel!=p.model) return@withPermit
                val area = decision.target()
                val elapsed = (System.nanoTime()-started)/1_000_000
                if (area == null) repository.reportDetection(p.screen.packageName,"视觉 AI 未确认跳过目标（${elapsed} ms，广告=${decision.advertisement}，置信=${decision.confidence}），已缓存样本")
                else {
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image,0,image.size))
                    val frame = VisualFrame(p.screen,bitmap,VisualSignals(false,null,emptyList(),false),image,0)
                    val hash = frame.use { it.patch(area) }
                    val rule = VisualRule(packageName=p.screen.packageName,versionCode=p.screen.versionCode,scene=AdRecognition.scene(p.screen),sceneVersion=2,
                        target=area,targetHash=hash,label=decision.label,source="vercel:${p.model}",createdAt=System.currentTimeMillis(),contextHash=p.contextHash,tapMethod=TapMethod.GESTURE)
                    saveProposal(rule)
                    repository.reportDetection(p.screen.packageName,"视觉方案已缓存（${elapsed} ms）；当前或下次匹配时自动尝试，无需人工确认")
                }
                finished.edit().putLong(p.key,System.currentTimeMillis()).remove("retry-${p.key}").apply()
                if (area != null) {
                    File(folder,"${p.key}.json").delete();File(folder,"${p.key}.jpg").delete()
                } else File(folder,"${p.key}.json").writeText(ruleJson.encodeToString(p.copy(completed=true)))
            } }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                finished.edit().putLong("retry-${p.key}",System.currentTimeMillis()+30_000).apply()
                val safe = e.message?.takeIf { it.matches(Regex("Vercel 视觉请求失败（HTTP \\d{3}）")) ||
                    it.matches(Regex("视觉响应不完整（finish=[a-z_]+，chars=\\d+）")) } ?: "网络或响应结构错误（${e.javaClass.simpleName}）"
                repository.reportDetection(p.screen.packageName,"视觉学习未完成：$safe；已缓存待重试")
                val retry = p.copy(attempts=p.attempts+1)
                File(folder,"${p.key}.json").writeText(ruleJson.encodeToString(retry))
                if (retry.attempts < 3) scope.launch { delay(30_000); if (allowed(p.screen.packageName)) launch(retry) }
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
    fun verify(rule: VisualRule, session: String, images: List<ByteArray>, early: Boolean, sameApp: Boolean, homeScene: String, homeHash: String, basis: String) {
        scope.launch {
            try {
                if (!allowed(rule.packageName)) return@launch
                val model=repository.settings.value.visionModel
                val credential=repository.analysisKey(JevProvider.VERCEL_AI_GATEWAY,model,true) ?: return@launch
                val verdict = client.verify(images,credential,model)
                repository.log(rule.packageName,"AI 结果复核：首页=${verdict.normalHome}，广告消失=${verdict.advertisementGone}，详情页=${verdict.landingPage}，系统提示=${verdict.systemPrompt}，置信=${verdict.confidence}，时序=$early")
                if (!allowed(rule.packageName) || repository.settings.value.visionModel!=model) return@launch
                val updated = repository.updateVisual(rule.id,rule.behavior()) {current ->
                    if(current.state==VisualRuleState.PAUSED) null else
                        VisualPolicy.record(current,session,early,verdict.accepted(),sameApp,System.currentTimeMillis(),basis)
                            .copy(homeScene=homeScene,homeHash=if (early && verdict.accepted() && sameApp && homeHash.isNotEmpty()) homeHash else current.homeHash)
                } ?: return@launch
                repository.reportDetection(rule.packageName,if (updated.active()) "AI 与时序检查通过，智能规则已自动启用" else updated.lastResult)
            } catch (_: Exception) { repository.reportDetection(rule.packageName,"自动结果检查未完成，未晋升为已验证规则") }
        }
    }
}
