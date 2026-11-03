package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.*
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import javax.net.ssl.SSLException
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.data.ConnectionState
import com.haibo.tiaotiao.data.InstalledApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class JevClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .callTimeout(7, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).build()) {

    suspend fun select(screen: ScreenSnapshot, key: String, model: String, provider: JevProvider = JevProvider.TYPE_SAFE): ScreenNode? =
        withContext(Dispatchers.IO) { JevProtocol.parse(send(JevProtocol.request(screen, model), key, model, provider), screen) }

    suspend fun probe(key: String, model: String, provider: JevProvider): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        JevProtocol.validateProbe(send(JevProtocol.probeRequest(model), key, model, provider))
        (System.nanoTime() - started) / 1_000_000
    }

    suspend fun inspect(screen: ScreenSnapshot, key: String, model: String, provider: JevProvider): ScreenNode? =
        withContext(Dispatchers.IO) { JevProtocol.parse(send(JevProtocol.request(screen,model,true),key,model,provider),screen,true) }

    suspend fun classifyApps(apps: List<InstalledApp>, key: String, model: String, provider: JevProvider): List<CategoryPrediction> =
        withContext(Dispatchers.IO) { JevCategoryProtocol.parse(send(JevCategoryProtocol.request(apps, model), key, model, provider), apps) }

    private suspend fun send(body: String, key: String, model: String, provider: JevProvider): String {
        require(key.isNotBlank()) { "请在设置中填写 Jev API Key" }
        require(key.all { it.code in 33..126 }) { "Jev API Key 格式无效" }
        provider.validateModel(model)
        val request = Request.Builder().url(provider.endpoint)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).await().use { response ->
            require(response.isSuccessful) { when (response.code) {
                401, 403 -> "${provider.displayName} 鉴权失败：请确认密钥来自所选服务，且具备 Jev 模型权限"
                402 -> "${provider.displayName} 额度不足，请检查余额或预算"
                400, 404 -> "${provider.displayName} 请求或模型名不正确，建议使用 ${provider.defaultModel}"
                429 -> "Jev 请求受限，请稍后重试"
                else -> "${provider.displayName} 请求失败（HTTP ${response.code}）"
            } }
            val body = response.body
            val bytes = body.byteStream().readLimited(262_144)
            require(bytes.size <= 262_144) { "Jev 响应超出大小限制" }
            String(bytes, Charsets.UTF_8)
        }
    }
}

/** Never include arbitrary provider response bodies or request headers in UI/log messages. */
fun modelErrorMessage(error: Exception): String = when (error) {
    is UnknownHostException -> "无法解析模型服务地址，请检查手机网络或代理"
    is SocketTimeoutException -> "模型请求超时，请检查手机网络后重试"
    is SSLException -> "模型安全连接失败，请检查网络与手机时间"
    is IOException -> "模型网络请求失败，请检查手机网络或代理"
    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { text ->
        listOf("请", "Jev", "DeepSeek 官方", "图片服务配置已改变", "Vercel AI Gateway", "TypeSafe 直连").any(text::startsWith)
    } ?: "模型响应格式不正确，本次未执行点击"
    else -> "模型分析失败，本次未执行点击"
}

suspend fun testModelConnection(repository: AppRepository): String {
    val config = repository.settings.value
    val generation = repository.beginModelCheck()
    return try {
        val millis = JevClient().probe(repository.vault.get("jevKey"), config.model, config.jevProvider)
        val message = "${config.jevProvider.displayName} 连接成功（${millis} ms）"
        repository.finishModelCheck(generation, ConnectionState.CONNECTED, message)
        repository.log("", message)
        message
    } catch (cancelled: CancellationException) {
        repository.finishModelCheck(generation, ConnectionState.UNTESTED, "连接测试已取消，请重试")
        throw cancelled
    } catch (error: Exception) {
        val message = modelErrorMessage(error)
        repository.finishModelCheck(generation, ConnectionState.FAILED, message)
        repository.log("", message)
        message
    }
}
