package com.haibo.tiaotiao.data

import android.content.Context
import android.graphics.BitmapFactory
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.network.VisualClient
import com.haibo.tiaotiao.service.VisualFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable private data class PendingImage(val key: String, val screen: ScreenSnapshot, val createdAt: Long, val model: String,
    val attempts: Int = 0, val completed: Boolean = false, val engineVersion: Int = 1)

/** Learning survives leaving the app's splash session. Saved images are OCR-masked, bounded and temporary. */
class VisualLearner(context: Context, private val repository: AppRepository) {
    private val folder = File(context.filesDir,"visual_pending").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val permits = Semaphore(2)
    private val client = VisualClient()
    private val finished = context.getSharedPreferences("visual_cache",Context.MODE_PRIVATE)
    fun allowed(pkg: String) = repository.settings.value.let { it.enabled && it.smartDamai && it.cloudEnabled &&
        it.jevProvider == JevProvider.VERCEL_AI_GATEWAY && pkg == "cn.damai" && pkg in it.packages }
    init {
        scope.launch { repository.settings.collect { s ->
            if (!s.enabled || !s.smartDamai || !s.cloudEnabled || s.jevProvider != JevProvider.VERCEL_AI_GATEWAY)
                jobs.values.forEach { it.cancel() }
            else resume()
        } }
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
    fun enqueue(frame: VisualFrame): Boolean {
        if (!allowed(frame.screen.packageName)) return false
        folder.listFiles().orEmpty().filter { it.extension=="json" && !jobs.containsKey(it.nameWithoutExtension) }.forEach { file ->
            val old=runCatching { ruleJson.decodeFromString<PendingImage>(file.readText()) }.getOrNull()
            if (old==null || System.currentTimeMillis()-old.createdAt>86_400_000) {
                File(folder,file.nameWithoutExtension+".jpg").delete();file.delete()
            }
        }
        val model = repository.settings.value.visionModel
        val key = digest("${frame.screen.fingerprint()}|${digest(java.util.Base64.getEncoder().encodeToString(frame.jpeg))}|$model")
        if (jobs.containsKey(key) || System.currentTimeMillis()-finished.getLong(key,0)<600_000) return false
        val records=folder.listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.lastModified() }
        if (records.size>=8) {
            val old=records.firstOrNull { !jobs.containsKey(it.nameWithoutExtension) } ?: return false
            File(folder,old.nameWithoutExtension+".jpg").delete();old.delete()
        }
        val pending = PendingImage(key,frame.screen,System.currentTimeMillis(),model,engineVersion=2)
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
                val decision = client.recognize(image,repository.vault.get("jevKey"),p.model)
                if (!allowed(p.screen.packageName)) return@withPermit
                val area = decision.target()
                val elapsed = (System.nanoTime()-started)/1_000_000
                if (area == null) repository.reportDetection(p.screen.packageName,"视觉 AI 未确认跳过目标（${elapsed} ms，广告=${decision.advertisement}，置信=${decision.confidence}），已缓存样本")
                else {
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image,0,image.size))
                    val frame = VisualFrame(p.screen,bitmap,VisualSignals(false,null,emptyList(),false),image,0)
                    val hash = frame.use { it.patch(area) }
                    val rule = VisualRule(packageName=p.screen.packageName,versionCode=p.screen.versionCode,scene=p.screen.fingerprint(),
                        target=area,targetHash=hash,label=decision.label,source="vercel:${p.model}",createdAt=System.currentTimeMillis())
                    val imported=repository.visualRules.value.firstOrNull { it.awaitingLocalReview && it.packageName==rule.packageName && it.scene==rule.scene }
                    if (imported!=null) repository.putVisual(rule.copy(id=imported.id))
                    if (imported==null && repository.visualRules.value.none { it.behavior()==rule.behavior() ||
                        (it.packageName==rule.packageName && it.scene==rule.scene && it.target.near(rule.target)) }) repository.putVisual(rule)
                    repository.reportDetection(p.screen.packageName,"视觉方案已缓存（${elapsed} ms）；当前或下次匹配时自动尝试，无需人工确认")
                }
                finished.edit().putLong(p.key,System.currentTimeMillis()).apply()
                if (area != null) {
                    File(folder,"${p.key}.json").delete();File(folder,"${p.key}.jpg").delete()
                } else File(folder,"${p.key}.json").writeText(ruleJson.encodeToString(p.copy(completed=true)))
            } }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                finished.edit().putLong(p.key,System.currentTimeMillis()).apply()
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
    fun verify(rule: VisualRule, session: String, images: List<ByteArray>, early: Boolean, sameApp: Boolean, homeScene: String, basis: String) {
        scope.launch {
            try {
                if (!allowed(rule.packageName)) return@launch
                val verdict = client.verify(images,repository.vault.get("jevKey"),repository.settings.value.visionModel)
                repository.log(rule.packageName,"AI 结果复核：首页=${verdict.normalHome}，广告消失=${verdict.advertisementGone}，详情页=${verdict.landingPage}，系统提示=${verdict.systemPrompt}，时序=$early")
                if (!allowed(rule.packageName)) return@launch
                val current = repository.visualRules.value.find { it.id==rule.id && it.behavior()==rule.behavior() } ?: return@launch
                if (current.state==VisualRuleState.PAUSED) return@launch
                val updated = VisualPolicy.record(current,session,early,verdict.accepted(),sameApp,System.currentTimeMillis(),basis).copy(homeScene=homeScene)
                repository.putVisual(updated)
                repository.reportDetection(rule.packageName,if (updated.active()) "AI 与时序检查通过，智能规则已自动启用" else updated.lastResult)
            } catch (_: Exception) { repository.reportDetection(rule.packageName,"自动结果检查未完成，未晋升为已验证规则") }
        }
    }
}
