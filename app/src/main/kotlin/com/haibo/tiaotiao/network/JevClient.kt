package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.*
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class JevClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(7, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).build()
    suspend fun select(screen: ScreenSnapshot, key: String, model: String): ScreenNode? {
        require(key.isNotBlank()) { "请在设置中填写 Jev API Key" }
        require(key.all { it.code in 33..126 }) { "Jev API Key 格式无效" }
        require(model.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "模型名称格式无效" }
        val request = Request.Builder().url("https://api.typesafe.ai/v1/systemone")
            .header("Authorization", "Bearer $key")
            .post(JevProtocol.request(screen, model).toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).await().use { response ->
            require(response.isSuccessful) { when(response.code) {
                401, 403 -> "Jev 鉴权失败，请检查 API Key 与模型权限"
                429 -> "Jev 请求受限，请稍后重试"
                else -> "Jev 服务请求失败（HTTP ${response.code}）"
            } }
            val body = response.body
            val bytes = body.byteStream().readLimited(262_144)
            require(bytes.size <= 262_144) { "Jev 响应超出大小限制" }
            JevProtocol.parse(String(bytes, Charsets.UTF_8), screen)
        }
    }
}
