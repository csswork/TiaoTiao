package com.haibo.tiaotiao.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.data.AdJump
import com.haibo.tiaotiao.touch.TouchConnection

/** Explains a launch ad that opens other apps by itself and what the user can do about it. */
@Composable internal fun AdJumpCard(jump: AdJump,onOpenTouch: () -> Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as TiaoApplication
    val channel by app.enhancedTouch.state.collectAsState()
    val settings by app.repository.settings.collectAsState()
    var label by remember(jump.packageName) {mutableStateOf(app.appLabels.cached(jump.packageName) ?: jump.packageName)}
    LaunchedEffect(jump.packageName) {label=app.appLabels.load(jump.packageName)}
    val prevented=settings.enhancedTouch && channel==TouchConnection.READY
    val ink=Color(0xFF5A3B12)
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF1DC)),
        border=BorderStroke(1.dp,Color(0xFFE9C58F))) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("「$label」的开屏广告会自己跳转",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=ink)
            Text("这是“摇一摇 / 翻转”类互动广告：它读取手机的运动传感器，手机轻微晃动、甚至平放时，不点也会打开广告页" +
                (if(jump.lastTarget.isNotBlank()) "或拉起「${jump.lastTarget}」" else "或其他应用") + "。这不是 TiaoTiao 点错了位置。",
                style=MaterialTheme.typography.bodyMedium,color=ink)
            Text("TiaoTiao 已拦截 ${jump.count} 次。可以这样处理：",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,color=ink)
            Text(if(prevented) "1. 已生效：增强点击已连接，「$label」开屏的 10 秒内会暂停它的传感器，从源头阻止跳转。"
                else "1. 推荐：开启增强点击后，TiaoTiao 会在「$label」开屏的 10 秒内暂停它的传感器，从源头阻止跳转。",
                style=MaterialTheme.typography.bodyMedium,color=ink)
            if(!prevented) Button(onClick=onOpenTouch,modifier=Modifier.fillMaxWidth()) {Text("去开启增强点击")}
            Text("2. 未开启时，TiaoTiao 会在系统询问“是否允许打开”时自动选择“拒绝”并退出广告页，但广告页可能会闪现一下。",
                style=MaterialTheme.typography.bodyMedium,color=ink)
            Text("3. 在系统设置顶部搜索“关联启动”，禁止「$label」打开其他应用；也可以在它的应用信息页检查相关权限。",
                style=MaterialTheme.typography.bodyMedium,color=ink)
            OutlinedButton(onClick={
                runCatching {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${jump.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            },modifier=Modifier.fillMaxWidth()) {Text("打开「$label」应用信息")}
            Text("4. 部分应用在自己的设置里提供“摇一摇 / 扭动跳转”开关，关闭后同样有效。",style=MaterialTheme.typography.bodyMedium,color=ink)
            TextButton(onClick={app.repository.adJumps.dismiss(jump.packageName)}) {Text("知道了")}
        }
    }
}
