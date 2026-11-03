package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.VisionProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class VisualClientTest {
    @Test fun `official recognition and result checks use only the official endpoint`() = runBlocking {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor {chain ->
            val request=chain.request();requests+=request
            assertEquals("https://api.deepseek.com/chat/completions",request.url.toString())
            assertEquals("Bearer fake-official-key",request.header("Authorization"))
            assertNull(request.header("TypeSafe-Danger-Zone-Images"))
            val buffer=okio.Buffer();request.body!!.writeTo(buffer)
            val body=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("deepseek-flash",body["model"]!!.jsonPrimitive.content)
            assertEquals("disabled",body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("json_object",body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains("fake-official-key"))
            val parts=body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            val captions=parts.filter {it.jsonObject["type"]!!.jsonPrimitive.content=="text"}.map {it.jsonObject["text"]!!.jsonPrimitive.content}
            val verifying=requests.size>1
            assertEquals(if(verifying) listOf("BEFORE frame","AFTER frame 1","AFTER frame 2") else listOf("CURRENT frame"),captions.drop(1))
            assertTrue(captions.first().contains("Example JSON:"))
            val images=parts.filter {it.jsonObject["type"]!!.jsonPrimitive.content=="image_url"}
            assertEquals(if(verifying) 3 else 1,images.size)
            assertTrue(images.all {it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content=="data:image/jpeg;base64,AQID"})
            val answer=if(verifying) """{"normalHome":true,"advertisementGone":true,"landingPage":false,"systemPrompt":false,"confidence":0.99}"""
                else """{"advertisement":true,"confidence":0.99,"label":"close_ad","left":0.8,"top":0.04,"right":0.96,"bottom":0.09}"""
            val response=buildJsonObject {
                putJsonObject("usage") {put("prompt_tokens",1234)}
                putJsonArray("choices") {addJsonObject {put("finish_reason","stop");putJsonObject("message") {put("content",answer)}}}
            }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client=VisualClient(http)
        val result=client.recognize(byteArrayOf(1,2,3),"fake-official-key","deepseek-flash",VisionProvider.DEEPSEEK_OFFICIAL)
        assertNotNull(result.target());assertEquals(1234L,result.inputTokens);assertNotNull(result.elapsedMs)
        val verdict=client.verify(List(3) {byteArrayOf(1,2,3)},"fake-official-key","deepseek-flash",provider=VisionProvider.DEEPSEEK_OFFICIAL)
        assertTrue(verdict.accepted());assertEquals(1234L,verdict.inputTokens)
        assertEquals(2,requests.size)
    }
    @Test fun `official errors are sanitized and never trigger gateway fallback`() = runBlocking {
        var count=0
        val http=OkHttpClient.Builder().addInterceptor {chain ->
            count++;assertEquals("api.deepseek.com",chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("secret-body-fake-official-key".toResponseBody()).build()
        }.build()
        try {
            VisualClient(http).recognize(byteArrayOf(1),"fake-official-key","deepseek-flash",VisionProvider.DEEPSEEK_OFFICIAL)
            fail()
        } catch(e: IllegalArgumentException) {assertEquals("DeepSeek 官方直连图片请求失败（HTTP 401）",e.message)}
        assertEquals(1,count)
    }
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
        assertFalse(VisualVerdict(false,false,false,false,.1).certain())
        assertTrue(VisualVerdict(false,false,false,false,.99).certain())
    }
}
