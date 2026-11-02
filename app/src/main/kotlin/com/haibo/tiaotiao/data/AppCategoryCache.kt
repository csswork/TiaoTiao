package com.haibo.tiaotiao.data

import android.util.AtomicFile
import com.haibo.tiaotiao.core.JevProvider
import com.haibo.tiaotiao.core.digest
import com.haibo.tiaotiao.core.ruleJson
import com.haibo.tiaotiao.network.JevCategoryProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable data class AppCategoryRecord(
    val packageName: String, val appName: String, val category: AppCategory, val confidence: Double,
    val provider: JevProvider, val model: String, val at: Long,
    val taxonomyVersion: Int = JevCategoryProtocol.TAXONOMY_VERSION,
) {
    fun key() = digest("$packageName|$appName|$provider|$model|$taxonomyVersion")
}
@Serializable data class AppCategoryBundle(val format: String = "TiaoTiaoAppCategories", val schemaVersion: Int = 1,
    val records: List<AppCategoryRecord>)

object AppCategoryCacheCodec {
    const val MAX_RECORDS = 5000
    const val MAX_BYTES = 4_194_304
    fun key(app: InstalledApp, provider: JevProvider, model: String) =
        digest("${app.packageName}|${app.name.take(120)}|$provider|$model|${JevCategoryProtocol.TAXONOMY_VERSION}")
    fun decode(text: String): List<AppCategoryRecord> {
        require(text.toByteArray().size <= MAX_BYTES)
        val bundle = ruleJson.decodeFromString<AppCategoryBundle>(text)
        require(bundle.format == "TiaoTiaoAppCategories" && bundle.schemaVersion == 1 && bundle.records.size <= MAX_RECORDS)
        bundle.records.forEach { record ->
            require(record.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
            require(record.appName.length <= 120 && record.category in JevCategoryProtocol.descriptions)
            require(record.confidence.isFinite() && record.confidence in 0.0..1.0 && record.at >= 0)
            require(record.taxonomyVersion in 1..1000)
            record.provider.validateModel(record.model)
        }
        require(bundle.records.map { it.key() }.distinct().size == bundle.records.size)
        return bundle.records
    }
}

class AppCategoryCache(directory: File) {
    private val file = AtomicFile(File(directory, "app_categories.json"))
    fun load(): MutableMap<String,AppCategoryRecord> = runCatching {
        require(file.baseFile.length() <= AppCategoryCacheCodec.MAX_BYTES)
        AppCategoryCacheCodec.decode(String(file.readFully(), Charsets.UTF_8)).associateBy { it.key() }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    fun save(records: Collection<AppCategoryRecord>) {
        val kept = records.sortedByDescending { it.at }.take(AppCategoryCacheCodec.MAX_RECORDS)
        val text = ruleJson.encodeToString(AppCategoryBundle(records = kept))
        AppCategoryCacheCodec.decode(text)
        val output = file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
}
