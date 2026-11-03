package com.haibo.tiaotiao.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JevImageProtocolTest {
    @Test fun `official protocol can explicitly select the latest model`() {
        val body=Json.parseToJsonElement(JevImageProtocol.request(listOf(byteArrayOf(1)),false,model="jev-latest")).jsonObject
        assertEquals("jev-latest",body["model"]!!.jsonPrimitive.content)
    }
    @Test fun `public OCR evidence accompanies the image without arbitrary page text`() {
        val hints=com.haibo.tiaotiao.core.VisualSignals(true,5,listOf(com.haibo.tiaotiao.core.Area(.8f,.03f,.96f,.08f)),false)
        val body=Json.parseToJsonElement(JevImageProtocol.request(listOf(byteArrayOf(1)),false,model="jev-latest",hints=hints)).jsonObject
        val signals=body["state"]!!.jsonObject["observed_public_signals"]!!.jsonObject
        assertTrue(signals["advertisement_badge_detected"]!!.jsonPrimitive.boolean)
        assertEquals(5,signals["countdown_seconds"]!!.jsonPrimitive.int)
        assertEquals(1,signals["possible_close_controls"]!!.jsonArray.size)
    }
    @Test fun `images are native assets and never chat messages`() {
        val body=Json.parseToJsonElement(JevImageProtocol.request(listOf(byteArrayOf(1,2,3)),false)).jsonObject
        assertEquals("typesafe-ai/jev-1.14.0",body["model"]!!.jsonPrimitive.content)
        assertNull(body["messages"])
        val ref=body["state"]!!.jsonObject["screenshot"]!!.jsonPrimitive.content
        assertEquals("AQID",body["assets"]!!.jsonObject[ref]!!.jsonObject["data_base64"]!!.jsonPrimitive.content)
        assertEquals("box",body["questions"]!!.jsonObject["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
    @Test fun `ad probability and box confidence both gate the target`() {
        fun result(ad: Double,confidence: Double)=JevImageProtocol.decision(Json.parseToJsonElement("""{"answers":{"advertisement":{"type":"noul","noul":$ad},"target":{"type":"box","box":{"left":0.85,"top":0.04,"right":0.96,"bottom":0.09},"confidence":$confidence}}}""").jsonObject)
        assertNotNull(result(.99,.99).target())
        assertNull(result(.8,.99).target())
        assertNull(result(.99,.8).target())
    }
    @Test fun `a still visible ad cannot pass and uncertain predicates remain uncertain`() {
        fun result(home: Double,gone: Double)=JevImageProtocol.verdict(Json.parseToJsonElement("""{"answers":{"normalHome":{"type":"noul","noul":$home},"advertisementGone":{"type":"noul","noul":$gone},"landingPage":{"type":"noul","noul":0.01},"systemPrompt":{"type":"noul","noul":0.01}}}""").jsonObject)
        assertFalse(result(.01,.01).accepted())
        assertTrue(result(.01,.01).certain())
        assertFalse(result(.6,.6).certain())
        assertTrue(result(.99,.99).accepted())
    }
}
