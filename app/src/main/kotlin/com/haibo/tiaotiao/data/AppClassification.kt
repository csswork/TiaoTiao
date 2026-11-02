package com.haibo.tiaotiao.data

import android.content.Context
import com.haibo.tiaotiao.core.JevProvider
import com.haibo.tiaotiao.network.JevCategoryProtocol
import com.haibo.tiaotiao.network.JevClient
import com.haibo.tiaotiao.network.modelErrorMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class AppClassificationState(
    val apps: List<InstalledApp> = emptyList(), val loading: Boolean = true, val running: Boolean = false,
    val message: String = "正在读取应用列表", val canClassify: Boolean = false, val failed: Boolean = false,
)
private data class ClassificationConfig(val cloud: Boolean, val provider: JevProvider, val model: String)
private fun AppSettings.classificationConfig() = ClassificationConfig(cloudEnabled, jevProvider, model)

/** Application-scoped jobs survive navigation. Each catalog/config generation uses one sequential batch queue. */
class AppClassification(
    context: Context, private val repository: AppRepository,
    client: JevClient? = null,
    private val loadApps: suspend () -> List<InstalledApp> = { InstalledApps.load(context) },
    private val cache: AppCategoryCache = AppCategoryCache(context.filesDir),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api by lazy { client ?: JevClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(4, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()) }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppClassificationState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var activeConfig: ClassificationConfig? = null
    @Volatile private var retryAfter = 0L
    init {
        scope.launch {
            repository.settings.map { it.classificationConfig() }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            repository.modelConnection.drop(1).filter { it.state == ConnectionState.CONNECTED }.collect { refresh(retry = true) }
        }
    }

    @Synchronized fun refresh(force: Boolean = false, retry: Boolean = false) {
        val config = repository.settings.value.classificationConfig()
        if (job?.isActive == true && activeConfig == config && !force && !retry) return
        job?.cancel()
        if (activeConfig != config || retry || force) retryAfter = 0
        activeConfig = config
        job = scope.launch { mutex.withLock { classify(config, force, retry) } }
    }

    private suspend fun classify(config: ClassificationConfig, force: Boolean, retry: Boolean) {
        _state.update { it.copy(loading = it.apps.isEmpty(), running = false, failed = false) }
        var catalog: List<InstalledApp> = emptyList()
        var records = mutableMapOf<String,AppCategoryRecord>()
        var canClassify = false
        fun publish(message: String, running: Boolean = false, failed: Boolean = false) {
            val classified = catalog.map { app ->
                val record = records[AppCategoryCacheCodec.key(app, config.provider, config.model)]
                app.copy(category = record?.category ?: AppCategory.PENDING)
            }
            _state.value = AppClassificationState(classified, false, running, message, canClassify, failed)
        }
        try {
            catalog = loadApps()
            records = cache.load()
            currentCoroutineContext().ensureActive()
            val key = if (config.cloud) repository.vault.get("jevKey") else ""
            canClassify = config.cloud && key.isNotBlank()
            val cached = catalog.count { AppCategoryCacheCodec.key(it, config.provider, config.model) in records }
            val pending = catalog.filter { force || AppCategoryCacheCodec.key(it, config.provider, config.model) !in records }
            if (pending.isEmpty()) { publish("Jev 分类已缓存 · ${catalog.size} 个应用"); return }
            if (!canClassify) {
                publish(if (!config.cloud) "开启云端分析后，Jev 将自动识别分类（已缓存 $cached/${catalog.size}）"
                    else "配置 Jev 服务后自动识别分类（已缓存 $cached/${catalog.size}）")
                return
            }
            if (!force && !retry && System.currentTimeMillis() < retryAfter) {
                publish("Jev 分类暂未完成，稍后可重试；已有结果已保留", failed = true)
                return
            }
            var completed = 0
            publish("Jev 正在识别分类 0/${pending.size}", running = true)
            for (batch in pending.chunked(JevCategoryProtocol.BATCH_SIZE)) {
                val predictions = api.classifyApps(batch, key, config.model, config.provider)
                currentCoroutineContext().ensureActive()
                if (repository.settings.value.classificationConfig() != config) return
                val now = System.currentTimeMillis()
                predictions.forEach { prediction ->
                    val record = AppCategoryRecord(prediction.app.packageName, prediction.app.name.take(120), prediction.category,
                        prediction.confidence, config.provider, config.model, now)
                    records[record.key()] = record
                }
                cache.save(records.values)
                completed += batch.size
                publish("Jev 正在识别分类 $completed/${pending.size}", running = true)
            }
            retryAfter = 0
            publish("Jev 分类已缓存 · ${catalog.size} 个应用")
            repository.log("", "Jev 应用分类完成：本次 ${pending.size} 个，缓存 ${catalog.size} 个")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            retryAfter = System.currentTimeMillis() + 60_000
            val reason = modelErrorMessage(error).replace("本次未执行点击", "未更新分类")
            publish("Jev 分类未完成：$reason", failed = true)
            repository.log("", "Jev 应用分类未完成：$reason；保留已有缓存")
        }
    }

    fun close() { scope.cancel() }
}
