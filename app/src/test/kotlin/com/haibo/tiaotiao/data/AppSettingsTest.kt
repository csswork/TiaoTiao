package com.haibo.tiaotiao.data

import com.haibo.tiaotiao.core.ruleJson
import com.haibo.tiaotiao.core.VisionProvider
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class AppSettingsTest {
    @Test fun `authentication and access refusals stop repeated calls until retested`() {
        for(code in listOf(401,403,404,451)) assertTrue(ModelConnection(ConnectionState.FAILED,"请求失败（HTTP $code）").blocksRequests())
        assertFalse(ModelConnection(ConnectionState.FAILED,"请求失败（HTTP 500）").blocksRequests())
        assertFalse(ModelConnection(ConnectionState.CHECKING,"正在测试").blocksRequests())
        assertFalse(ModelConnection(ConnectionState.CONNECTED,"已连接").blocksRequests())
    }
    @Test fun `old settings decode without confusing node and image providers`() {
        val saved=ruleJson.decodeFromString<AppSettings>("""{"visionModel":"typesafe-ai/jev-1.14.0"}""")
        assertEquals(VisionProvider.VERCEL_AI_GATEWAY,saved.visionProvider)
        assertEquals(0L,saved.visionRevision)
        assertTrue(saved.showCheckBubble)
    }
    @Test fun `old automatic experiment preferences migrate without losing user choice`() {
        assertFalse(ruleJson.decodeFromString<AppSettings>("""{"smartDamai":false}""").automaticLearning)
        assertTrue(ruleJson.decodeFromString<AppSettings>("""{"smartDamai":true}""").automaticLearning)
        assertTrue(ruleJson.encodeToString(AppSettings()).contains("automaticLearning"))
        assertFalse(ruleJson.encodeToString(AppSettings()).contains("smartDamai"))
    }
}
