package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.JevProvider
import com.haibo.tiaotiao.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class JevCategoryProtocolTest {
    private val apps = listOf(InstalledApp("Example Bank", "com.example.bank", AppCategory.TOOLS),
        InstalledApp("Example Tickets", "com.example.tickets", AppCategory.SHOPPING))
    private fun response(confidence: Double = .99, probability: Double = .95): String = buildJsonObject {
        putJsonObject("answers") {
            apps.forEachIndexed { index, _ -> putJsonObject("app_$index") {
                val category = if (index == 0) AppCategory.FINANCE else AppCategory.LIFE
                put("type", "choice"); put("choice", category.name); put("confidence", confidence)
                putJsonObject("probabilities") { JevCategoryProtocol.descriptions.keys.forEach {
                    put(it.name, if (it == category) probability else (1 - probability) / (JevCategoryProtocol.descriptions.size - 1))
                } }
            } }
        }
    }.toString()

    @Test fun batchesUseConfiguredJevRouteAndOnlyAppIdentityMetadata() = runBlocking {
        var request: Request? = null
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toResponseBody()).build()
        }.build())
        val results = client.classifyApps(apps, "fake-category-key", "typesafe-ai/jev", JevProvider.VERCEL_AI_GATEWAY)
        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/systemone", request!!.url.toString())
        assertEquals("Bearer fake-category-key", request!!.header("Authorization"))
        val buffer = okio.Buffer(); request!!.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertFalse(body.contains("fake-category-key"))
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(2, root.getValue("questions").jsonObject.size)
        val metadata = root.getValue("state").jsonObject.getValue("apps").jsonArray
        assertTrue(metadata.all { it.jsonObject.keys == setOf("id", "name", "package") })
        assertEquals(listOf(AppCategory.FINANCE, AppCategory.LIFE), results.map { it.category })
        assertEquals(apps, results.map { it.app }) // Old local categories never decide the returned classification.
    }

    @Test fun uncertainPredictionsDoNotForceAConfidentCategory() {
        assertTrue(JevCategoryProtocol.parse(response(.1, .25), apps).all { it.category == AppCategory.OTHER })
    }

    @Test fun unknownCategoriesMissingAppsAndInvalidProbabilitiesAreRejected() {
        val valid = Json.parseToJsonElement(response()).jsonObject
        val answers = valid.getValue("answers").jsonObject
        fun altered(answer: JsonObject) = buildJsonObject { put("answers", JsonObject(answers + ("app_0" to answer))) }.toString()
        val first = answers.getValue("app_0").jsonObject
        assertRejected(altered(JsonObject(first + ("choice" to JsonPrimitive("ALL")))))
        assertRejected(buildJsonObject { put("answers", JsonObject(answers - "app_1")) }.toString())
        assertRejected(altered(JsonObject(first + ("confidence" to JsonPrimitive("NaN")))))
        val missingDistribution = buildJsonObject { put("FINANCE", 1) }
        assertRejected(altered(JsonObject(first + ("probabilities" to missingDistribution))))
        assertRejected(altered(JsonObject(first + ("probabilities" to buildJsonObject {
            JevCategoryProtocol.descriptions.keys.forEach { put(it.name, .5) }
        }))))
    }

    @Test fun requestBatchSizeAndIdentityAreBounded() {
        try { JevCategoryProtocol.request(emptyList(), "typesafe-ai/jev"); fail() } catch (_: IllegalArgumentException) {}
        try { JevCategoryProtocol.request(List(2) { apps.first() }, "typesafe-ai/jev"); fail() } catch (_: IllegalArgumentException) {}
        try { JevCategoryProtocol.request(List(9) { InstalledApp("App $it", "com.example.app$it") }, "typesafe-ai/jev"); fail() } catch (_: IllegalArgumentException) {}
    }

    private fun assertRejected(text: String) {
        try { JevCategoryProtocol.parse(text, apps); fail("Invalid classification response accepted") } catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }
}
