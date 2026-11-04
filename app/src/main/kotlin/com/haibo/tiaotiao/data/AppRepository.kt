package com.haibo.tiaotiao.data

import android.content.Context
import android.util.AtomicFile
import com.haibo.tiaotiao.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class AppSettings(
    val enabled: Boolean = false,
    val cloudEnabled: Boolean = false,
    val packages: Set<String> = emptySet(),
    val model: String = "jev-latest",
    val jevProvider: JevProvider = JevProvider.TYPE_SAFE,
    val syncUrl: String = "",
    val syncEtag: String = "",
    @kotlinx.serialization.json.JsonNames("smartDamai") val automaticLearning: Boolean = true,
    val visionModel: String = "typesafe-ai/jev-1.14.0",
    val visionProvider: VisionProvider = VisionProvider.VERCEL_AI_GATEWAY,
    val visionRevision: Long = 0,
    val showCheckBubble: Boolean = true,
    val enhancedTouch: Boolean = false,
)
@Serializable data class LogEntry(val at: Long, val packageName: String, val message: String, val id: String = UUID.randomUUID().toString())
@Serializable data class StoredSample(val ruleId: String, val source: String, val screen: ScreenSnapshot)
@Serializable enum class ConnectionState { UNTESTED, CHECKING, CONNECTED, FAILED }
@Serializable data class ModelConnection(
    val state: ConnectionState = ConnectionState.UNTESTED,
    val message: String = "尚未测试模型连接",
    val checkedAt: Long = 0,
) {
    fun blocksRequests()=state==ConnectionState.FAILED && Regex("HTTP (?:401|403|404|451)\\b").containsMatchIn(message)
}

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val baselinePrefs = context.getSharedPreferences("visual_baselines", Context.MODE_PRIVATE)
    fun visualBaseline(scene: String): Long = baselinePrefs.getLong(scene,0)
    fun baselineObservations(scene: String) = baselinePrefs.getInt("observed-$scene",0)
    fun recordBaselineObservation(scene: String) {baselinePrefs.edit().putInt("observed-$scene",baselineObservations(scene)+1).apply()}
    fun recordVisualBaseline(scene: String, duration: Long) {
        if (duration !in 1000..15_000) return
        val old=visualBaseline(scene)
        baselinePrefs.edit().putLong(scene,if (old>0) minOf(old,duration) else duration).apply()
    }
    private val rulesFile = AtomicFile(File(context.filesDir, "rules.json"))
    private val logsFile = AtomicFile(File(context.filesDir, "logs.json"))
    private val samplesDir = File(context.filesDir, "samples").apply { mkdirs() }
    val vault = SecretVault(context)
    val previews=VisualPreviewStore(context.filesDir)
    private val visualFile = AtomicFile(File(context.filesDir, "visual_rules.json"))
    private val _visualRules = MutableStateFlow(runCatching {
        VisualPolicy.decode(String(visualFile.readFully(), Charsets.UTF_8)).rules.map(VisualPolicy::migrateExecution)
    }.getOrElse {
        if (visualFile.baseFile.exists()) visualFile.baseFile.copyTo(File(context.filesDir,"visual_rules.recovery.json"),overwrite=true)
        emptyList()
    })
    val visualRules = _visualRules.asStateFlow()
    @Synchronized fun confirmVisual(rule: VisualRule) {
        val current=_visualRules.value.find {it.id==rule.id && it.behavior()==rule.behavior()} ?: error("方案已改变，请重新查看后确认")
        putVisual(VisualPolicy.confirm(current,System.currentTimeMillis()))
    }
    @Synchronized fun putVisual(rule: VisualRule) {
        VisualPolicy.validate(rule)
        val next = _visualRules.value.filterNot { it.id == rule.id } + rule
        require(next.size <= VisualPolicy.MAX_RULES) {"智能规则已达上限，请清理后重试"}
        write(visualFile, ruleJson.encodeToString(VisualBundle(rules = next)))
        _visualRules.value = next
    }
    @Synchronized fun updateVisual(id: String, behavior: String, transform: (VisualRule) -> VisualRule?): VisualRule? {
        val current=_visualRules.value.find {it.id==id && it.behavior()==behavior} ?: return null
        val updated=transform(current) ?: return null
        putVisual(updated)
        return updated
    }
    @Synchronized fun removeVisual(id: String) {
        val next = _visualRules.value.filterNot { it.id == id }
        write(visualFile, ruleJson.encodeToString(VisualBundle(rules = next))); _visualRules.value = next
        previews.remove(id)
    }
    /** Only masked JPEGs produced by the capture pipeline enter the persistent preview store. */
    suspend fun savePreview(rule: VisualRule,jpeg: ByteArray,area: Area,kind: PreviewKind,contextHash: String=rule.contextHash) = withContext(Dispatchers.IO) {
        try {synchronized(this@AppRepository) {
            if(_visualRules.value.none {it.id==rule.id && it.behavior()==rule.behavior()}) return@synchronized
            previews.save(rule.id,jpeg,area,kind,contextHash)
        }} catch(c: CancellationException) {throw c}
        catch(_: Exception) {log(rule.packageName,"截图未能保存，方案与结果检查继续")}
    }
    suspend fun adjustTarget(rule: VisualRule,preview: VisualPreview,x: Float,y: Float): String = withContext(Dispatchers.IO) {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {"点击位置超出图片"}
        val area=Area((x-.012f).coerceIn(0f,.976f),(y-.006f).coerceIn(0f,.988f),
            (x-.012f).coerceIn(0f,.976f)+.024f,(y-.006f).coerceIn(0f,.988f)+.012f)
        val jpeg=previews.file(preview).readBytes()
        val hash=previews.patch(jpeg,area)
        synchronized(this@AppRepository) {
            val owner=_visualRules.value.find {it.id==preview.ruleId}
            check(owner!=null && (owner.id==rule.id || VisualProposals.sameTarget(owner,rule)) &&
                preview in previews.entries.value && _visualRules.value.any {it.id==rule.id && it.behavior()==rule.behavior()}) {"方案已改变，请重新打开"}
            val s=settings.value
            val context=preview.contextHash.ifBlank {owner.contextHash}
            putVisual(rule.copy(target=area,targetHash=hash,nodeTarget=null,nodeScene="",tapMethod=TapMethod.GESTURE,
                contextHash=context,
                userAdjusted=true,source="${s.visionProvider.name}:${s.visionModel}",state=VisualRuleState.CANDIDATE,
                learningRevision=rule.learningRevision+1,
                manualConfirmation=null,
                evidence=emptyList(),homeHash="",homeScene="",automaticFailures=0,retryAfter=0,awaitingLocalReview=false,
                lastResult="点击位置已手动调整，等待重新检查"))
            runCatching {previews.save(rule.id,jpeg,area,PreviewKind.MANUAL,context)}
                .onFailure {log(rule.packageName,"点击位置已保存，调整预览图片未能保存")}
        }
        "点击位置已保存，旧验证记录已清除"
    }
    fun exportVisual() = ruleJson.encodeToString(VisualBundle(rules = _visualRules.value.filter { it.active() }))
    @Synchronized fun importVisual(text: String): Int {
        var count = 0
        VisualPolicy.import(text).forEach { r ->
            if (_visualRules.value.none { it.behavior() == r.behavior() }) {
                putVisual(if (_visualRules.value.any { it.id == r.id }) r.copy(id = UUID.randomUUID().toString()) else r); count++
            }
        }
        return count
    }
    private fun loadSettings(): AppSettings {
        val saved=runCatching {ruleJson.decodeFromString<AppSettings>(prefs.getString("settings","")!!)}.getOrDefault(AppSettings())
        var updated=if(prefs.getBoolean("jevImageMigration1",false)) saved else saved.copy(
            model=if(saved.jevProvider==JevProvider.VERCEL_AI_GATEWAY && saved.model=="typesafe-ai/jev") "typesafe-ai/jev-1.14.0" else saved.model,
            visionModel=if(saved.visionModel=="google/gemini-2.5-flash-lite") "typesafe-ai/jev-1.14.0" else saved.visionModel)
        if(!prefs.getBoolean("deepseekOfficialMigration1",false)) updated=updated.copy(
            visionProvider=VisionProvider.DEEPSEEK_OFFICIAL,visionModel="deepseek-flash",visionRevision=updated.visionRevision+1)
        if(!prefs.getBoolean("jevOfficialMigration1",false)) updated=updated.copy(
            jevProvider=JevProvider.TYPE_SAFE,model="jev-latest",visionProvider=VisionProvider.JEV_OFFICIAL,
            visionModel="jev-latest",visionRevision=updated.visionRevision+1)
        if(updated.jevProvider!=saved.jevProvider || updated.model!=saved.model || updated.visionProvider!=saved.visionProvider || updated.visionModel!=saved.visionModel)
            prefs.edit().remove("modelConnection").remove("visionConnection").apply()
        if(updated.visionModel!=saved.visionModel || updated.visionProvider!=saved.visionProvider) {
            val migrated=_visualRules.value.map {if(it.state==VisualRuleState.CANDIDATE)
                it.copy(awaitingLocalReview=true,evidence=emptyList(),homeScene="",homeHash="",lastResult="图片模型已切换，等待重新识别") else it}
            write(visualFile,ruleJson.encodeToString(VisualBundle(rules=migrated)));_visualRules.value=migrated
            File(context.filesDir,"visual_pending").listFiles().orEmpty().forEach {it.delete()}
            context.getSharedPreferences("visual_cache",Context.MODE_PRIVATE).edit().clear().apply()
        }
        prefs.edit().putString("settings",ruleJson.encodeToString(updated)).putBoolean("jevImageMigration1",true)
            .putBoolean("deepseekOfficialMigration1",true).putBoolean("jevOfficialMigration1",true).apply()
        return updated
    }
    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()
    private var modelConfigGeneration = 0L
    private val _modelConnection = MutableStateFlow(runCatching {
        ruleJson.decodeFromString<ModelConnection>(prefs.getString("modelConnection", "")!!)
            .let { if (it.state == ConnectionState.CHECKING) ModelConnection() else it }
    }.getOrDefault(ModelConnection()))
    val modelConnection = _modelConnection.asStateFlow()
    private var visionCheckGeneration=0L
    private val _visionConnection=MutableStateFlow(runCatching {
        ruleJson.decodeFromString<ModelConnection>(prefs.getString("visionConnection","")!!)
            .let {if(it.state==ConnectionState.CHECKING) ModelConnection() else it}
    }.getOrDefault(ModelConnection()))
    val visionConnection=_visionConnection.asStateFlow()
    /** Only the selected provider's own failure blocks it; a failing fallback request leaves the selection's state alone. */
    @Synchronized fun recordVisionFailure(config: AppSettings,error: String,provider: VisionProvider=config.visionProvider) {
        if(provider!=config.visionProvider) return
        val code=Regex("HTTP (401|403|404|451)\\b").find(error)?.groupValues?.get(1) ?: return
        if(sameVision(config) && _visionConnection.value.state!=ConnectionState.CHECKING)
            saveVisionConnection(ModelConnection(ConnectionState.FAILED,"${config.visionProvider.displayName} 请求未通过（HTTP $code），请检查密钥或网络后重新测试",System.currentTimeMillis()))
    }
    val detection = MutableStateFlow<LogEntry?>(null)
    private val _rules = MutableStateFlow(runCatching { RuleCodec.decode(String(rulesFile.readFully(), Charsets.UTF_8)).rules }.getOrElse {
        // Retain any damaged source for recovery; never execute a partially decoded rule set.
        if (rulesFile.baseFile.exists()) rulesFile.baseFile.copyTo(File(context.filesDir, "rules.recovery.json"), overwrite = true)
        emptyList()
    })
    val rules = _rules.asStateFlow()
    private val _logs = MutableStateFlow(runCatching { ruleJson.decodeFromString<List<LogEntry>>(String(logsFile.readFully(), Charsets.UTF_8)) }.getOrDefault(emptyList()))
    val logs = _logs.asStateFlow()
    private val logWrites=Channel<List<LogEntry>>(Channel.CONFLATED)
    init {
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            for(entries in logWrites) {
                runCatching {write(logsFile,ruleJson.encodeToString(entries))}
                    .onFailure {android.util.Log.w("TiaoTiao","Diagnostic log could not be saved: ${it.javaClass.simpleName}")}
            }
        }
    }
    val connected = MutableStateFlow(false)
    val adJumps = AdJumps(context)
    val touchRefusals = TouchRefusals(context)
    val sampleCount = MutableStateFlow(samplesDir.listFiles()?.size ?: 0)

    @Synchronized fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated.jevProvider != _settings.value.jevProvider || updated.model != _settings.value.model) invalidateModelConnection()
        if(!sameVision(updated)) {
            visionCheckGeneration++;saveVisionConnection(ModelConnection())
        }
        prefs.edit().putString("settings", ruleJson.encodeToString(updated)).apply()
        _settings.value = updated
    }
    /** Keep provider/model and its credential atomic while asynchronous analysis is running. */
    @Synchronized fun configureAi(provider: JevProvider, model: String, key: String) {
        provider.validateModel(model)
        if(key.isNotBlank()) vault.set("jevKey",key)
        updateSettings {s ->
            if(s.visionProvider==VisionProvider.DEEPSEEK_OFFICIAL) s.copy(jevProvider=provider,model=model)
            else {
                val vision=if(provider==JevProvider.TYPE_SAFE) VisionProvider.JEV_OFFICIAL else VisionProvider.VERCEL_AI_GATEWAY
                s.copy(jevProvider=provider,model=model,visionProvider=vision,visionModel=vision.defaultModel,visionRevision=s.visionRevision+1)
            }
        }
        invalidateModelConnection()
    }
    @Synchronized fun analysisKey(provider: JevProvider, model: String, vision: Boolean=false): String? {
        val s=_settings.value
        if(!s.enabled || !s.automaticLearning || !s.cloudEnabled || s.jevProvider!=provider ||
            (if(vision) s.visionModel else s.model)!=model) return null
        return vault.get("jevKey").takeIf {it.isNotBlank()}
    }
    fun visionKeyAvailable() = settings.value.let {s -> when(s.visionProvider) {
        VisionProvider.JEV_OFFICIAL -> s.jevProvider==JevProvider.TYPE_SAFE && vault.contains("jevKey")
        VisionProvider.DEEPSEEK_OFFICIAL -> vault.contains("deepseekKey")
        VisionProvider.VERCEL_AI_GATEWAY -> s.jevProvider==JevProvider.VERCEL_AI_GATEWAY && vault.contains("jevKey")
    }}
    /** A transient network failure of the selected provider routes requests to the other one for a minute. */
    @Volatile private var primaryCooldownUntil=0L
    fun noteVisionNetworkFailure(provider: VisionProvider) {
        if(provider==settings.value.visionProvider && fallbackVision()!=null) primaryCooldownUntil=System.currentTimeMillis()+60_000
    }
    /** The other official image provider, when its own key is saved. Vercel has no counterpart. */
    fun fallbackVision(): VisionProvider? = settings.value.let {s -> when(s.visionProvider) {
        VisionProvider.JEV_OFFICIAL -> VisionProvider.DEEPSEEK_OFFICIAL.takeIf {vault.contains("deepseekKey")}
        VisionProvider.DEEPSEEK_OFFICIAL -> VisionProvider.JEV_OFFICIAL.takeIf {s.jevProvider==JevProvider.TYPE_SAFE && vault.contains("jevKey")}
        VisionProvider.VERCEL_AI_GATEWAY -> null
    }}
    /** Provider to call now: the selection, or the fallback while the selection is blocked (HTTP 451 without a proxy,
     * a rejected key) or cooling down after a network failure. Null when neither can be used. */
    fun visionRoute(): VisionProvider? {
        val primaryUsable=visionKeyAvailable() && !_visionConnection.value.blocksRequests() && System.currentTimeMillis()>=primaryCooldownUntil
        return if(primaryUsable) settings.value.visionProvider else fallbackVision() ?: settings.value.visionProvider.takeIf {
            visionKeyAvailable() && !_visionConnection.value.blocksRequests()}
    }
    fun visionModel(provider: VisionProvider)=settings.value.let {if(provider==it.visionProvider) it.visionModel else provider.defaultModel}
    fun sameVisionRevision(config: AppSettings)=settings.value.visionRevision==config.visionRevision
    /** Key for a routed provider, under the same enable/cloud gates as visionKey. */
    @Synchronized fun routeKey(provider: VisionProvider,config: AppSettings): String? {
        val s=settings.value
        if(!sameVisionRevision(config) || !s.enabled || !s.cloudEnabled || !s.automaticLearning) return null
        return when(provider) {
            VisionProvider.JEV_OFFICIAL -> if(s.jevProvider==JevProvider.TYPE_SAFE) vault.get("jevKey") else ""
            VisionProvider.DEEPSEEK_OFFICIAL -> vault.get("deepseekKey")
            VisionProvider.VERCEL_AI_GATEWAY -> if(s.visionProvider==provider && s.jevProvider==JevProvider.VERCEL_AI_GATEWAY) vault.get("jevKey") else ""
        }.takeIf {it.isNotBlank()}
    }
    fun sameVision(config: AppSettings) = settings.value.let {it.visionProvider==config.visionProvider &&
        it.visionModel==config.visionModel && it.visionRevision==config.visionRevision}
    @Synchronized fun visionKey(config: AppSettings,probe: Boolean=false): String? {
        val s=settings.value
        if(!sameVision(config) || (!probe && (!s.enabled || !s.cloudEnabled || !s.automaticLearning))) return null
        return when(s.visionProvider) {
            VisionProvider.JEV_OFFICIAL -> if(s.jevProvider==JevProvider.TYPE_SAFE) vault.get("jevKey") else ""
            VisionProvider.DEEPSEEK_OFFICIAL -> vault.get("deepseekKey")
            VisionProvider.VERCEL_AI_GATEWAY -> if(s.jevProvider==JevProvider.VERCEL_AI_GATEWAY) vault.get("jevKey") else ""
        }.takeIf {it.isNotBlank()}
    }
    @Synchronized fun configureVision(provider: VisionProvider,model: String,key: String) {
        provider.validateModel(model)
        if(provider==VisionProvider.DEEPSEEK_OFFICIAL) {
            if(key.isNotBlank()) {
                require(key.all {it.code in 33..126}) {"DeepSeek 官方 Key 格式无效"}
                vault.set("deepseekKey",key)
            }
            require(vault.contains("deepseekKey")) {"请填写 DeepSeek 官方 Key"}
        } else {
            require(key.isBlank()) {"Jev 图片分析沿用上方已保存的 Jev Key"}
            val expected=if(provider==VisionProvider.JEV_OFFICIAL) JevProvider.TYPE_SAFE else JevProvider.VERCEL_AI_GATEWAY
            require(settings.value.jevProvider==expected && vault.contains("jevKey")) {"请先配置对应的 Jev 服务与 Key"}
        }
        updateSettings {it.copy(visionProvider=provider,visionModel=model,visionRevision=it.visionRevision+1)}
    }
    @Synchronized fun clearDeepseekKey() {
        vault.set("deepseekKey","")
        updateSettings {it.copy(visionRevision=it.visionRevision+1)}
    }
    @Synchronized fun beginVisionCheck(): Long {
        visionCheckGeneration++;saveVisionConnection(ModelConnection(ConnectionState.CHECKING,"正在测试图片模型连接…"))
        return visionCheckGeneration
    }
    @Synchronized fun finishVisionCheck(generation: Long,state: ConnectionState,message: String) {
        if(generation==visionCheckGeneration) saveVisionConnection(ModelConnection(state,message,System.currentTimeMillis()))
    }
    private fun saveVisionConnection(value: ModelConnection) {
        prefs.edit().putString("visionConnection",ruleJson.encodeToString(value)).apply();_visionConnection.value=value
    }
    @Synchronized fun clearAiCredentials() {
        vault.set("jevKey","")
        updateSettings {if(it.visionProvider!=VisionProvider.DEEPSEEK_OFFICIAL)
            it.copy(cloudEnabled=false,visionRevision=it.visionRevision+1) else it}
        invalidateModelConnection()
    }
    @Synchronized fun invalidateModelConnection() {
        modelConfigGeneration++
        saveModelConnection(ModelConnection())
    }
    @Synchronized fun beginModelCheck(): Long {
        modelConfigGeneration++
        saveModelConnection(ModelConnection(ConnectionState.CHECKING, "正在测试模型连接…"))
        return modelConfigGeneration
    }
    @Synchronized fun finishModelCheck(generation: Long, state: ConnectionState, message: String) {
        if (generation == modelConfigGeneration) saveModelConnection(ModelConnection(state, message, System.currentTimeMillis()))
    }
    @Synchronized fun recordModelResponse(success: Boolean, message: String) {
        if (_modelConnection.value.state != ConnectionState.CHECKING) saveModelConnection(ModelConnection(
            if (success) ConnectionState.CONNECTED else ConnectionState.FAILED, message, System.currentTimeMillis()))
    }
    @Synchronized private fun saveModelConnection(value: ModelConnection) {
        prefs.edit().putString("modelConnection", ruleJson.encodeToString(value)).apply()
        _modelConnection.value = value
    }
    fun reportDetection(pkg: String, message: String) {
        detection.value = LogEntry(System.currentTimeMillis(), pkg, message)
        log(pkg, message)
    }
    @Synchronized fun log(pkg: String, message: String) {
        // One launch writes 10–30 entries; keep enough to diagnose a whole test round after the fact.
        val entries = (listOf(LogEntry(System.currentTimeMillis(), pkg, message)) + _logs.value).take(1000)
        _logs.value = entries
        logWrites.trySend(entries)
    }
    @Synchronized fun clearLogs() { _logs.value = emptyList();logWrites.trySend(emptyList()) }
    /** Caller first stops scans and joins learning jobs so old results cannot reappear. */
    @Synchronized fun clearResults() {
        write(rulesFile,ruleJson.encodeToString(RuleBundle(exportedAt=System.currentTimeMillis(),rules=emptyList())))
        write(visualFile,ruleJson.encodeToString(VisualBundle(rules=emptyList())))
        _rules.value=emptyList();_visualRules.value=emptyList()
        samplesDir.listFiles().orEmpty().forEach {it.deleteRecursively()};sampleCount.value=0
        baselinePrefs.edit().clear().apply()
        listOf("rules.recovery.json","visual_rules.recovery.json").forEach {File(context.filesDir,it).delete()}
        File(context.filesDir,"last_execution").deleteRecursively()
        previews.clear();adJumps.clear();touchRefusals.clear()
        detection.value=null;clearLogs()
    }
    @Synchronized fun put(rule: SkipRule) {
        RuleCodec.validate(rule)
        val next = _rules.value.filterNot { it.id == rule.id } + rule
        require(next.size <= 500) { "最多保存 500 条规则，请清理后重试" }
        save(next)
    }
    @Synchronized fun remove(id: String) {
        save(_rules.value.filterNot { it.id == id })
        File(samplesDir, "$id.json").delete()
        sampleCount.value = samplesDir.listFiles()?.size ?: 0
    }
    @Synchronized fun addSample(rule: SkipRule, screen: ScreenSnapshot) {
        val files = samplesDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        files.take((files.size - 99).coerceAtLeast(0)).forEach { it.delete() }
        write(AtomicFile(File(samplesDir, "${rule.id}.json")), ruleJson.encodeToString(StoredSample(rule.id, rule.source, screen)))
        sampleCount.value = samplesDir.listFiles()?.size ?: 0
    }
    @Synchronized fun clearSamples() {
        samplesDir.listFiles().orEmpty().forEach { it.delete() }
        sampleCount.value = 0
    }
    fun exportVerified(): String = RuleCodec.encode(_rules.value.filter { it.isVerified() }, System.currentTimeMillis())
    @Synchronized fun importRules(text: String): Int {
        val incoming = RuleCodec.importRules(text)
        val next = _rules.value.toMutableList()
        var count = 0
        incoming.forEach { rule ->
            if (next.none { it.behaviorDigest() == rule.behaviorDigest() }) {
                next += if (next.any { it.id == rule.id }) rule.copy(id = UUID.randomUUID().toString()) else rule
                count++
            }
        }
        require(next.size <= 500) { "导入后规则超过 500 条，请先清理" }
        save(next)
        return count
    }
    @Synchronized fun confirm(id: String, success: Boolean) {
        val rule = requireNotNull(_rules.value.find { it.id == id })
        put(RuleEngine.confirm(rule, success, System.currentTimeMillis()))
        log(rule.packageName, if (success) "人工确认：已验证规则" else "人工反馈：规则已停用")
    }
    @Synchronized private fun save(rules: List<SkipRule>) {
        write(rulesFile, RuleCodec.encode(rules, System.currentTimeMillis()))
        _rules.value = rules
    }
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
