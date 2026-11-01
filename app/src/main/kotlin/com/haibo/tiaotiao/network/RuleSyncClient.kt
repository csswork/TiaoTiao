package com.haibo.tiaotiao.network

import com.haibo.tiaotiao.core.RuleCodec
import com.haibo.tiaotiao.data.AppRepository
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** A user-configured HTTPS/WebDAV JSON resource, with optimistic concurrency, no silent overwrite. */
class RuleSyncClient(private val repository: AppRepository) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private fun request(): Request.Builder {
        val url = repository.settings.value.syncUrl.trim().toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "请使用不含凭据的 HTTPS JSON 文件地址" }
        val auth = repository.vault.get("syncAuthorization")
        require(auth.all { it.code in 32..126 }) { "同步凭据格式无效" }
        return Request.Builder().url(url).apply { if (auth.isNotBlank()) header("Authorization", auth) }
    }
    suspend fun download(): Int {
        val url = repository.settings.value.syncUrl
        return client.newCall(request().get().build()).await().use { response ->
            require(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
            val bytes = requireNotNull(response.body).byteStream().readLimited(RuleCodec.MAX_BYTES)
            require(bytes.size <= RuleCodec.MAX_BYTES) { "远端规则文件超过 1 MB" }
            val count = repository.importRules(String(bytes, Charsets.UTF_8))
            val etag = response.header("ETag").orEmpty().takeIf { it.startsWith('"') && it.endsWith('"') }.orEmpty()
            repository.updateSettings { if (it.syncUrl == url) it.copy(syncEtag = etag) else it }
            count
        }
    }
    suspend fun upload() {
        val settings = repository.settings.value
        val request = request().apply {
            if (settings.syncEtag.isBlank()) header("If-None-Match", "*") else header("If-Match", settings.syncEtag)
        }.put(repository.exportVerified().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).await().use { response ->
            require(response.isSuccessful) { if (response.code == 412) "远端已有文件或已更新，请先下载核对；未覆盖远端内容" else "上传失败（HTTP ${response.code}）" }
            val etag = response.header("ETag").orEmpty().takeIf { it.startsWith('"') && it.endsWith('"') }.orEmpty()
            repository.updateSettings { if (it.syncUrl == settings.syncUrl) it.copy(syncEtag = etag) else it }
        }
    }
}
