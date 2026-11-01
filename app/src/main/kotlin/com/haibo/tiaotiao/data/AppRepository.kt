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

@Serializable
data class AppSettings(
    val enabled: Boolean = false,
    val cloudEnabled: Boolean = false,
    val packages: Set<String> = emptySet(),
    val model: String = "jev-latest",
    val syncUrl: String = "",
    val syncEtag: String = "",
)
@Serializable data class LogEntry(val at: Long, val packageName: String, val message: String)
@Serializable data class StoredSample(val ruleId: String, val source: String, val screen: ScreenSnapshot)

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val rulesFile = AtomicFile(File(context.filesDir, "rules.json"))
    private val logsFile = AtomicFile(File(context.filesDir, "logs.json"))
    private val samplesDir = File(context.filesDir, "samples").apply { mkdirs() }
    val vault = SecretVault(context)
    private val _settings = MutableStateFlow(runCatching { ruleJson.decodeFromString<AppSettings>(prefs.getString("settings", "")!!) }.getOrDefault(AppSettings()))
    val settings = _settings.asStateFlow()
    private val _rules = MutableStateFlow(runCatching { RuleCodec.decode(String(rulesFile.readFully(), Charsets.UTF_8)).rules }.getOrElse {
        // Retain any damaged source for recovery; never execute a partially decoded rule set.
        if (rulesFile.baseFile.exists()) rulesFile.baseFile.copyTo(File(context.filesDir, "rules.recovery.json"), overwrite = true)
        emptyList()
    })
    val rules = _rules.asStateFlow()
    private val _logs = MutableStateFlow(runCatching { ruleJson.decodeFromString<List<LogEntry>>(String(logsFile.readFully(), Charsets.UTF_8)) }.getOrDefault(emptyList()))
    val logs = _logs.asStateFlow()
    val connected = MutableStateFlow(false)
    val sampleCount = MutableStateFlow(samplesDir.listFiles()?.size ?: 0)

    @Synchronized fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit().putString("settings", ruleJson.encodeToString(updated)).apply()
        _settings.value = updated
    }
    @Synchronized fun log(pkg: String, message: String) {
        val entries = (listOf(LogEntry(System.currentTimeMillis(), pkg, message)) + _logs.value).take(150)
        write(logsFile, ruleJson.encodeToString(entries))
        _logs.value = entries
    }
    @Synchronized fun clearLogs() { write(logsFile, "[]"); _logs.value = emptyList() }
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
