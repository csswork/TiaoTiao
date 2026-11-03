package com.haibo.tiaotiao.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.touch.*
import kotlinx.coroutines.launch

@Composable internal fun EnhancedTouchCard() {
    val context=LocalContext.current
    val app=context.applicationContext as TiaoApplication
    val settings by app.repository.settings.collectAsState()
    val wireless by app.wirelessBootstrap.state.collectAsState()
    val channel by app.enhancedTouch.state.collectAsState()
    val health by app.enhancedTouch.health.collectAsState()
    val events by app.touchJournal.events.collectAsState()
    val lastAction=events.firstOrNull {it.type in setOf("DISPATCH","ACTION","PRECHECK")}
    val lastResult=lastAction?.let {action ->events.firstOrNull {it.type=="RESULT" && it.ruleId==action.ruleId && it.at>=action.at}}
    LaunchedEffect(Unit) {app.enhancedTouch.probe("settings_opened")}
    val scope=rememberCoroutineScope()
    var stopping by remember {mutableStateOf(false)}
    var connectPort by remember {mutableStateOf("")}
    val busy=wireless.phase in setOf(WirelessPhase.PAIRING,WirelessPhase.STARTING)
    val ready=channel==TouchConnection.READY && app.enhancedTouch.ownedReady
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("增强点击 · 已内置",style=MaterialTheme.typography.titleMedium)
        Text("为已识别但点击无响应的广告提供系统触控。无需安装其他应用。")
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("优先使用增强点击",Modifier.weight(1f))
            Switch(checked=settings.enhancedTouch,onCheckedChange={value ->app.repository.updateSettings {it.copy(enhancedTouch=value)}})
        }
        Surface(color=when {ready ->MaterialTheme.colorScheme.primaryContainer;wireless.phase==WirelessPhase.FAILED ->MaterialTheme.colorScheme.errorContainer;else ->MaterialTheme.colorScheme.surfaceVariant},
            shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(when {ready ->"增强点击服务已连接";busy ->"增强点击正在启动";channel==TouchConnection.FAILED ->"增强点击连接或权限异常";wireless.phase==WirelessPhase.FAILED ->"增强点击启动未完成";else ->"增强点击未启动"},style=MaterialTheme.typography.titleSmall)
                Text(if(ready) {
                    if(health.permission=="granted") "服务响应正常，系统触控权限可用。" else "本机服务已连接，正在检查触控权限。"
                } else wireless.message,style=MaterialTheme.typography.bodySmall)
                Text("最近点击：${lastAction?.let {touchReason(it.reason)} ?: "尚无记录"}",style=MaterialTheme.typography.bodySmall)
                Text("广告结果：${lastResult?.let {touchReason(it.reason)} ?: "尚未确认本次广告关闭"}",style=MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick={scope.launch {app.enhancedTouch.probe("user_check")}}) {Text("检查服务连接")}
        if(Build.VERSION.SDK_INT>=30) {
            if(ready) OutlinedButton(onClick={scope.launch {
                stopping=true
                try {app.enhancedTouch.stopOwned()} finally {stopping=false}
            }},enabled=!stopping) {Text("停止增强点击")}
            else if(wireless.paired) Button(onClick={app.wirelessBootstrap.beginDiscovery();app.wirelessBootstrap.startPaired(connectPort.toIntOrNull())},enabled=!busy && !stopping) {Text(if(busy) "正在启动…" else "启动增强点击")}
            Button(onClick={
                if(app.wirelessBootstrap.beginAssistedPairing()) {
                    runCatching {context.startActivity(WirelessBootstrap.settingsIntent(context))}.onFailure {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },enabled=!busy && !stopping) {Text(if(wireless.paired) "重新配对" else "开始配对")}
            Text("打开系统“使用配对码配对设备”后保持弹窗显示，TiaoTiao 会读取本次配对码并在后台完成配对。无需返回本页。",style=MaterialTheme.typography.bodySmall)
            Text("无法自动读取时，直接使用系统页面底部浮层的“手动输入”和数字键盘，不会打开输入法。",style=MaterialTheme.typography.bodySmall)
            if(wireless.assisting) TextButton(onClick={app.wirelessBootstrap.cancelAssistance()}) {Text("结束本次配对辅助")}
            if(wireless.paired && wireless.phase==WirelessPhase.FAILED) OutlinedTextField(value=connectPort,onValueChange={connectPort=it.filter {n ->n in '0'..'9'}.take(5)},
                label={Text("连接端口（自动发现失败时填写）")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        } else Text("内置无线启动需要 Android 11 及以上。",style=MaterialTheme.typography.bodySmall)
        Text("每次辅助最长2分钟，仅处理系统配对弹窗。配对码不保存、不上传。手机重启后需重新启动服务。",style=MaterialTheme.typography.bodySmall)
    }}
}
