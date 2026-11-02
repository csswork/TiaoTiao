package com.haibo.tiaotiao

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.JevProvider
import com.haibo.tiaotiao.data.*
import com.haibo.tiaotiao.network.JevCategoryProtocol
import com.haibo.tiaotiao.network.JevClient
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppClassificationFlowTest {
    @Test fun predictionsPersistAndReuseWithoutRequestsAndErrorsKeepGoodCache() {
        assumeTrue("仅在独立模拟器运行", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val repository = AppRepository(context)
        repository.vault.set("jevKey", "fake-category-key")
        repository.updateSettings { it.copy(cloudEnabled = true, jevProvider = JevProvider.VERCEL_AI_GATEWAY, model = "typesafe-ai/jev") }
        val calls = AtomicInteger()
        val reject = AtomicBoolean()
        val batches = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val client = JevClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val apps = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("state").jsonObject.getValue("apps").jsonArray
            batches += apps.size
            val response = buildJsonObject { putJsonObject("answers") {
                apps.forEach { entry ->
                    val app = entry.jsonObject
                    val category = if (app.getValue("package").jsonPrimitive.content.endsWith("bank")) AppCategory.FINANCE else AppCategory.LIFE
                    putJsonObject(app.getValue("id").jsonPrimitive.content) {
                        put("type", "choice"); put("choice", category.name); put("confidence", 1)
                        putJsonObject("probabilities") { JevCategoryProtocol.descriptions.keys.forEach { put(it.name, if (it == category) 1 else 0) } }
                    }
                }
            } }.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (reject.get()) 401 else 200)
                .message("test").body((if (reject.get()) "private-fake-key-body" else response).toResponseBody()).build()
        }.build())
        var apps = listOf(InstalledApp("Example Bank", "com.example.bank"), InstalledApp("Example Tickets", "com.example.tickets"))
        val cache = AppCategoryCache(context.filesDir)
        var worker = AppClassification(context, repository, client, { apps }, cache)
        try {
            waitUntil { worker.state.value.apps.size == 2 && !worker.state.value.running && worker.state.value.apps.all { it.category != AppCategory.PENDING } }
            assertEquals(1, calls.get())
            assertEquals(listOf(AppCategory.FINANCE, AppCategory.LIFE), worker.state.value.apps.map { it.category })
            worker.close()
            worker = AppClassification(context, repository, client, { apps }, cache)
            waitUntil { worker.state.value.apps.size == 2 && !worker.state.value.running && worker.state.value.apps.all { it.category != AppCategory.PENDING } }
            assertEquals("重新打开应用不能重复请求已缓存分类", 1, calls.get())
            repository.updateSettings { it.copy(cloudEnabled = false) }
            apps = apps.map { if (it.packageName.endsWith("bank")) it.copy(name = "Renamed Bank") else it }
            worker.refresh(retry = true)
            waitUntil { !worker.state.value.loading && !worker.state.value.running && worker.state.value.apps.any { it.name == "Renamed Bank" && it.category == AppCategory.PENDING } }
            assertEquals(1, calls.get())
            repository.updateSettings { it.copy(cloudEnabled = true) }
            waitUntil { worker.state.value.apps.any { it.name == "Renamed Bank" && it.category == AppCategory.FINANCE } && !worker.state.value.running }
            assertEquals(listOf(2, 1), batches.toList())
            reject.set(true)
            worker.refresh(force = true, retry = true)
            waitUntil { worker.state.value.failed }
            assertTrue(worker.state.value.apps.all { it.category != AppCategory.PENDING })
            assertFalse(worker.state.value.message.contains("fake-key"))
            assertFalse(worker.state.value.message.contains("private-"))
            assertEquals(3, calls.get())
        } finally { worker.close() }
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val namespace = "classification-test-${UUID.randomUUID()}"
        private val folder = File(base.cacheDir, namespace).apply { mkdirs() }
        override fun getFilesDir(): File = folder
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$namespace-$name", mode)
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(50) }
        fail("等待分类状态超时")
    }
}
