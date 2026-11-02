package com.haibo.tiaotiao.data

import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable enum class AppCategory(val title: String) {
    ALL("全部类别"), PENDING("待识别"), SOCIAL("社交通讯"), SHOPPING("购物消费"), VIDEO("视频娱乐"),
    AUDIO("音乐音频"), NEWS("资讯阅读"), LIFE("生活服务"), TRAVEL("出行导航"),
    FINANCE("金融理财"), TOOLS("效率工具"), PHOTO("拍摄美化"), GAME("游戏"), OTHER("其他")
}

data class InstalledApp(val name: String, val packageName: String, val category: AppCategory = AppCategory.PENDING)

object InstalledApps {
    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val manager = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = manager.queryIntentActivities(intent, 0)
        activities.filter { it.activityInfo.packageName != appContext.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { activity ->
                val pkg = activity.activityInfo.packageName
                val name = runCatching { activity.loadLabel(manager).toString() }.getOrDefault(pkg)
                InstalledApp(name, pkg)
            }.sortedBy { it.name.lowercase() }
    }
}
