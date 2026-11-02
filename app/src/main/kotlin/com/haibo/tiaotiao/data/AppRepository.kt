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

@Serializable
data class AppSettings(
    val enabled: Boolean = false,
    val cloudEnabled: Boolean = false,
    val packages: Set<String> = emptySet(),
    val model: String = "jev-latest",
    val jevProvider: JevProvider = JevProvider.TYPE_SAFE,
    val syncUrl: String = "",
    val syncEtag: String = "",
    val smartDamai: Boolean = true,
    val visionModel: String = "google/gemini-2.5-flash-lite",
)
@Serializable data class LogEntry(val at: Long, val packageName: String, val message: String, val id: String = UUID.randomUUID().toString())
@Serializable data class StoredSample(val ruleId: String, val source: String, val screen: ScreenSnapshot)
@Serializable enum class ConnectionState { UNTESTED, CHECKING, CONNECTED, FAILED }
@Serializable data class ModelConnection(
    val state: ConnectionState = ConnectionState.UNTESTED,
    val message: String = "尚未测试模型连接",
    val checkedAt: Long = 0,
)

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val baselinePrefs = context.getSharedPreferences("visual_baselines", Context.MODE_PRIVATE)
    fun visualBaseline(scene: String): Long = baselinePrefs.getLong(scene,0)
    fun recordVisualBaseline(scene: String, duration: Long) {
        if (duration !in 1000..15_000) return
        val old=visualBaseline(scene)
        baselinePrefs.edit().putLong(scene,if (old>0) minOf(old,duration) else duration).apply()
    }
    private val rulesFile = AtomicFile(File(context.filesDir, "rules.json"))
    private val logsFile = AtomicFile(File(context.filesDir, "logs.json"))
    private val samplesDir = File(context.filesDir, "samples").apply { mkdirs() }
    val vault = SecretVault(context)
    private val visualFile = AtomicFile(File(context.filesDir, "visual_rules.json"))
    private val _visualRules = MutableStateFlow(runCatching {
        VisualPolicy.decode(String(visualFile.readFully(), Charsets.UTF_8)).rules
    }.getOrElse {
        if (visualFile.baseFile.exists()) visualFile.baseFile.copyTo(File(context.filesDir,"visual_rules.recovery.json"),overwrite=true)
        emptyList()
    })
    val visualRules = _visualRules.asStateFlow()
    @Synchronized fun putVisual(rule: VisualRule) {
        VisualPolicy.validate(rule)
        val next = _visualRules.value.filterNot { it.id == rule.id } + rule
        require(next.size <= 100)
        write(visualFile, ruleJson.encodeToString(VisualBundle(rules = next)))
        _visualRules.value = next
    }
    @Synchronized fun removeVisual(id: String) {
        val next = _visualRules.value.filterNot { it.id == id }
        write(visualFile, ruleJson.encodeToString(VisualBundle(rules = next))); _visualRules.value = next
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
    private val _settings = MutableStateFlow(runCatching { ruleJson.decodeFromString<AppSettings>(prefs.getString("settings", "")!!) }.getOrDefault(AppSettings()))
    val settings = _settings.asStateFlow()
    private var modelConfigGeneration = 0L
    private val _modelConnection = MutableStateFlow(runCatching {
        ruleJson.decodeFromString<ModelConnection>(prefs.getString("modelConnection", "")!!)
            .let { if (it.state == ConnectionState.CHECKING) ModelConnection() else it }
    }.getOrDefault(ModelConnection()))
    val modelConnection = _modelConnection.asStateFlow()
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
    val sampleCount = MutableStateFlow(samplesDir.listFiles()?.size ?: 0)

    @Synchronized fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated.jevProvider != _settings.value.jevProvider || updated.model != _settings.value.model) invalidateModelConnection()
        prefs.edit().putString("settings", ruleJson.encodeToString(updated)).apply()
        _settings.value = updated
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
        val entries = (listOf(LogEntry(System.currentTimeMillis(), pkg, message)) + _logs.value).take(150)
        _logs.value = entries
        logWrites.trySend(entries)
    }
    @Synchronized fun clearLogs() { _logs.value = emptyList();logWrites.trySend(emptyList()) }
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
