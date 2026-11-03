package com.haibo.tiaotiao.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.touch.TouchConnection

@Composable internal fun EnhancedTouchCard() {
    val context=LocalContext.current
    val app=context.applicationContext as TiaoApplication
    val settings by app.repository.settings.collectAsState()
    val state by app.enhancedTouch.state.collectAsState()
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event ->if(event==Lifecycle.Event.ON_RESUME) app.enhancedTouch.refresh()}
        owner.lifecycle.addObserver(observer);app.enhancedTouch.refresh()
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("增强点击",style=MaterialTheme.typography.titleMedium)
        Text("用于已经识别正确、自动点击却没有响应的广告。开启后优先通过 Shizuku 执行系统触控。")
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("优先使用增强点击",Modifier.weight(1f))
            Switch(checked=settings.enhancedTouch,onCheckedChange={value ->app.repository.updateSettings {it.copy(enhancedTouch=value)}})
        }
        Text(state.text,style=MaterialTheme.typography.bodyMedium)
        when(state) {
            TouchConnection.MISSING -> OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/download/")))}) {Text("安装 Shizuku")}
            TouchConnection.STOPPED -> OutlinedButton(onClick={context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {context.startActivity(it)}}) {Text("打开 Shizuku")}
            TouchConnection.NEEDS_PERMISSION -> Button(onClick={app.repository.updateSettings {it.copy(enhancedTouch=true)};app.enhancedTouch.authorize()}) {Text("授权增强点击")}
            TouchConnection.FAILED -> OutlinedButton(onClick={app.enhancedTouch.refresh()}) {Text("重新连接")}
            else -> Unit
        }
        Text("需要先安装、启动并授权 Shizuku，无需 Root。手机重启后通常需要重新启动 Shizuku。未就绪时继续使用普通无障碍；已发起但失败的触控不会切换通道重复点击。",style=MaterialTheme.typography.bodySmall)
    }}
}
