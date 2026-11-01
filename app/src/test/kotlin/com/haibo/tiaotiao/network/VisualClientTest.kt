package com.haibo.tiaotiao.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class VisualClientTest {
    @Test fun `vision routes masked images to fixed gateway and parses bounded target`() = runBlocking {
        var request: Request? = null
        val answer="""{"advertisement":true,"confidence":0.99,"label":"skip","left":0.8,"top":0.04,"right":0.96,"bottom":0.09}"""
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            request=chain.request()
            val content=buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content",answer) } } } }.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result=VisualClient(http).recognize(byteArrayOf(1,2,3),"fake-key","google/gemini-2.5-flash-lite")
        assertNotNull(result.target())
        assertEquals("https://ai-gateway.vercel.sh/v1/chat/completions",request!!.url.toString())
        val buffer=okio.Buffer();request!!.body!!.writeTo(buffer);val body=buffer.readUtf8()
        assertFalse(body.contains("fake-key"));assertTrue(body.contains("data:image/jpeg;base64,AQID"))
    }
    @Test fun `provider error bodies and keys are never exposed`() = runBlocking {
        val http=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(401).message("Unauthorized").body("private-error-fake-key".toResponseBody()).build() }.build()
        try { VisualClient(http).recognize(byteArrayOf(1),"fake-key","google/gemini-2.5-flash-lite");fail() }
        catch (e: IllegalArgumentException) { assertEquals("Vercel 视觉请求失败（HTTP 401）",e.message) }
    }
    @Test fun `uncertain boxes and landing pages cannot authorize a rule`() {
        assertNull(VisualDecision(true,.8,"skip",.8f,.04f,.96f,.09f).target())
        assertNull(VisualDecision(true,.99,"purchase",.8f,.04f,.96f,.09f).target())
        assertNull(VisualDecision(true,.99,"skip",0f,0f,1f,1f).target())
        assertFalse(VisualVerdict(true,true,true,false,.99).accepted())
        assertFalse(VisualVerdict(true,true,false,true,.99).accepted())
        assertFalse(VisualVerdict(true,true,false,false,.8).accepted())
        assertTrue(VisualVerdict(true,true,false,false,.99).accepted())
    }
}
