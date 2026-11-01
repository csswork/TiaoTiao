package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.*
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class JevClientTest {
    private val node = ScreenNode("0.1", "com.example.app:id/skip", "Button", "跳过", true, Area(.8f, .02f, .99f, .08f))
    private val screen = ScreenSnapshot("com.example.app", 1, 1080, 2400, listOf(node), 0, 1)
    private val success = """{"answers":{"splash":{"type":"noul","noul":0.99},"target":{"type":"choice","choice":"0.1","confidence":0.99,"probabilities":{"0.1":0.99,"none":0.01}}}}"""

    @Test fun `gateway selection retains TypeSafe semantics but sends the key only to Vercel`() = runBlocking {
        var request: Request? = null
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(success.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertEquals(node, client.select(screen, "test-gateway-key", "typesafe-ai/jev", JevProvider.VERCEL_AI_GATEWAY))
        assertEquals("ai-gateway.vercel.sh", request!!.url.host)
        assertEquals("/typesafe/v1/systemone", request!!.url.encodedPath)
        assertEquals("Bearer test-gateway-key", request!!.header("Authorization"))
    }
    @Test fun `direct TypeSafe route remains backward compatible`() = runBlocking {
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("api.typesafe.ai", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(success.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertEquals(node, client.select(screen, "test-direct-key", "jev-latest"))
    }
    @Test fun `authentication errors identify the selected service without leaking the response body`() = runBlocking {
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("sensitive-provider-body-should-never-appear".toResponseBody()).build()
        }.build())
        try {
            client.select(screen, "test-gateway-key", "typesafe-ai/jev", JevProvider.VERCEL_AI_GATEWAY)
            fail("Expected authentication failure")
        } catch (error: IllegalArgumentException) {
            val message = modelErrorMessage(error)
            assertTrue(message.contains("Vercel AI Gateway"))
            assertTrue(message.contains("鉴权失败"))
            assertFalse(message.contains("sensitive-provider-body"))
            assertFalse(message.contains("test-gateway-key"))
        }
    }
    @Test fun `network errors are actionable and do not echo credentials`() {
        val message = modelErrorMessage(UnknownHostException("test-secret"))
        assertTrue(message.contains("网络"))
        assertFalse(message.contains("test-secret"))
    }
    @Test fun `connection test uses a fixed request and validates the returned Noul`() = runBlocking {
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"answers":{"connection":{"type":"noul","noul":1}}}""".toResponseBody()).build()
        }.build())
        assertTrue(client.probe("test-key", "typesafe-ai/jev", JevProvider.VERCEL_AI_GATEWAY) >= 0)
    }
}
