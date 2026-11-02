package com.haibo.tiaotiao.data

import com.haibo.tiaotiao.core.ruleJson
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class AppSettingsTest {
    @Test fun `old automatic experiment preferences migrate without losing user choice`() {
        assertFalse(ruleJson.decodeFromString<AppSettings>("""{"smartDamai":false}""").automaticLearning)
        assertTrue(ruleJson.decodeFromString<AppSettings>("""{"smartDamai":true}""").automaticLearning)
        assertTrue(ruleJson.encodeToString(AppSettings()).contains("automaticLearning"))
        assertFalse(ruleJson.encodeToString(AppSettings()).contains("smartDamai"))
    }
}
