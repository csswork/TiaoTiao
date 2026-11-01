package com.haibo.tiaotiao.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JevProviderTest {
    @Test fun `gateway credentials use only the documented gateway endpoint`() {
        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/systemone", JevProvider.VERCEL_AI_GATEWAY.endpoint)
        assertNotEquals(JevProvider.TYPE_SAFE.endpoint, JevProvider.VERCEL_AI_GATEWAY.endpoint)
        assertEquals("typesafe-ai/jev", JevProvider.VERCEL_AI_GATEWAY.migrateModel("jev-latest"))
        assertEquals("typesafe-ai/jev-1.14.0", JevProvider.VERCEL_AI_GATEWAY.migrateModel("jev-1.14.0"))
        assertEquals("jev-latest", JevProvider.TYPE_SAFE.migrateModel("typesafe-ai/jev"))
    }
    @Test fun `provider and model names cannot be silently mixed`() {
        JevProvider.VERCEL_AI_GATEWAY.validateModel("typesafe-ai/jev")
        JevProvider.TYPE_SAFE.validateModel("jev-latest")
        assertThrows(IllegalArgumentException::class.java) { JevProvider.TYPE_SAFE.validateModel("typesafe-ai/jev") }
        assertThrows(IllegalArgumentException::class.java) { JevProvider.VERCEL_AI_GATEWAY.validateModel("jev-latest") }
        assertThrows(IllegalArgumentException::class.java) { JevProvider.VERCEL_AI_GATEWAY.validateModel("other/model") }
    }
    @Test fun `connection probe contains only a fixed fixture and validates its response`() {
        val request = Json.parseToJsonElement(JevProtocol.probeRequest("typesafe-ai/jev")).jsonObject
        assertEquals("typesafe-ai/jev", request["model"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("controls"))
        assertFalse(request["state"]!!.jsonPrimitive.content.contains("package"))
        JevProtocol.validateProbe("""{"answers":{"connection":{"type":"noul","noul":0.99}},"provider_metadata":{"gateway":{}}}""")
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.validateProbe("""{"answers":{"connection":{"type":"noul","noul":0.1}}}""") }
        assertThrows(IllegalStateException::class.java) { JevProtocol.validateProbe("""{"answers":{}}""") }
    }
    @Test fun `common ad skip labels remain distinct from login and tutorial skips`() {
        listOf("跳过广告", "跳过广告 (5s)", "5秒 | 跳过", "跳过｜3", "关闭广告", "Skip ad 3s", "跳过 3 秒").forEach {
            assertTrue("Should accept: $it", SignalText.isSkip(it))
            assertEquals("跳过", SignalText.sanitize(it))
        }
        listOf("跳过登录", "跳过教程", "跳过视频", "打开广告", "立即购买", "跳过广告后购买").forEach {
            assertFalse("Should reject: $it", SignalText.isSkip(it))
        }
    }
}
