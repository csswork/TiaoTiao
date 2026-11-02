package com.haibo.tiaotiao.data

import com.haibo.tiaotiao.core.JevProvider
import com.haibo.tiaotiao.core.ruleJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AppCategoryCacheTest {
    private val app = InstalledApp("Example Bank", "com.example.bank")
    private val record = AppCategoryRecord(app.packageName, app.name, AppCategory.FINANCE, .99,
        JevProvider.VERCEL_AI_GATEWAY, "typesafe-ai/jev", 1)

    @Test fun cachedPredictionsAreBoundToAppNameProviderModelAndTaxonomy() {
        assertEquals(record.key(), AppCategoryCacheCodec.key(app, record.provider, record.model))
        assertNotEquals(record.key(), AppCategoryCacheCodec.key(app.copy(name = "New purpose"), record.provider, record.model))
        assertNotEquals(record.key(), AppCategoryCacheCodec.key(app, JevProvider.TYPE_SAFE, "jev-latest"))
        assertNotEquals(record.key(), AppCategoryCacheCodec.key(app, record.provider, "typesafe-ai/jev-preview"))
        assertNotEquals(record.key(), record.copy(taxonomyVersion = 2).key())
        assertEquals(listOf(record), AppCategoryCacheCodec.decode(ruleJson.encodeToString(AppCategoryBundle(records = listOf(record)))))
    }

    @Test fun invalidCacheCannotPretendThatPendingIsAnAiPrediction() {
        for (invalid in listOf(record.copy(category = AppCategory.PENDING), record.copy(category = AppCategory.ALL),
            record.copy(confidence = -1.0), record.copy(packageName = "invalid"))) {
            try { AppCategoryCacheCodec.decode(ruleJson.encodeToString(AppCategoryBundle(records = listOf(invalid)))); fail() }
            catch (_: IllegalArgumentException) {}
        }
        try { AppCategoryCacheCodec.decode(ruleJson.encodeToString(AppCategoryBundle(records = listOf(record, record)))); fail() }
        catch (_: IllegalArgumentException) {}
    }
}
