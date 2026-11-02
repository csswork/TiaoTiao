package com.haibo.tiaotiao.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** List composition only reads the memory cache; package-manager calls run on IO. */
class AppLabels(context: Context) {
    private val appContext=context.applicationContext
    private val names=ConcurrentHashMap<String,String>().apply {put(appContext.packageName,"TiaoTiao 演示")}
    fun cached(packageName: String): String?=names[packageName]
    suspend fun load(packageName: String): String=withContext(Dispatchers.IO) {
        names.computeIfAbsent(packageName) { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationInfo(pkg,0).loadLabel(appContext.packageManager).toString()
            }.getOrDefault(pkg)
        }
    }
}
