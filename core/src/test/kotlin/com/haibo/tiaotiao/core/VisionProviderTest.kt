package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class VisionProviderTest {
    @Test fun `Jev official images follow the latest alias on the official endpoint`() {
        val provider=VisionProvider.JEV_OFFICIAL
        assertEquals("https://api.typesafe.ai/v1/systemone",provider.endpoint)
        assertEquals("jev-latest",provider.defaultModel)
        provider.validateModel("jev-latest")
        assertThrows(IllegalArgumentException::class.java) {provider.validateModel("typesafe-ai/jev-1.14.0")}
    }
    @Test fun `official vision is pinned to the official host and model`() {
        val provider=VisionProvider.DEEPSEEK_OFFICIAL
        assertEquals("https://api.deepseek.com/chat/completions",provider.endpoint)
        provider.validateModel("deepseek-flash")
        assertThrows(IllegalArgumentException::class.java) {provider.validateModel("deepseek/deepseek-flash")}
        assertThrows(IllegalArgumentException::class.java) {provider.validateModel("typesafe-ai/jev-1.14.0")}
    }
    @Test fun `gateway cannot be configured as a DeepSeek transport`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionProvider.VERCEL_AI_GATEWAY.validateModel("deepseek/deepseek-v4.1-flash-1017")
        }
        VisionProvider.VERCEL_AI_GATEWAY.validateModel("typesafe-ai/jev-1.14.0")
    }
}
