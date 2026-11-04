package com.haibo.tiaotiao.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.data.LogEntry

/** Outcomes a user cares about; the rest (timings, tokens, per-frame reads) stays under "全部诊断". */
private val important=listOf("检查通过","自动启用","方案已缓存","已确认","恢复候选","自行","跳转","拒绝","已返回","暂停","停止","失败","未完成",
    "未开启","已连接","断开","自动恢复","切换","未通过","被拒绝","已跳过")
/** Routine per-launch notes that match a keyword above but carry no news. */
private val routine=listOf("暂停其传感器","未晋升","不计为验证成功")
internal fun LogEntry.important()=important.any {it in message} && routine.none {it in message}

@Composable internal fun ProcessingLogPage(repository: AppRepository, busy: Boolean, onClear: () -> Unit) {
    val logs by repository.logs.collectAsStateWithLifecycle()
    val app=LocalContext.current.applicationContext as TiaoApplication
    var all by rememberSaveable {mutableStateOf(false)}
    val shown=remember(logs,all) {if(all) logs else logs.filter {it.important()}}
    val labels=remember {mutableStateMapOf<String,String>()}
    LaunchedEffect(shown) {shown.map {it.packageName}.filter {it.isNotBlank() && it !in labels}.distinct().take(40).forEach {labels[it]=app.appLabels.load(it)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=4.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=!all,onClick={all=false},label={Text("重要事件")})
            FilterChip(selected=all,onClick={all=true},label={Text("全部诊断 ${logs.size}")})
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onClear,enabled=!busy && logs.isNotEmpty()) {Text("清空")}
        }}
        if(shown.isEmpty()) item {Text(if(all) "暂无记录" else "暂无重要事件。跳过、学习和异常会显示在这里。",
            Modifier.padding(vertical=36.dp),style=MaterialTheme.typography.bodyMedium,color=Color.Gray)}
        items(shown,key={it.id},contentType={"log"}) {entry ->
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(entry.packageName.isBlank()) "TiaoTiao" else labels[entry.packageName] ?: app.appLabels.cached(entry.packageName) ?: entry.packageName,
                            Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,maxLines=1)
                        Text(relativeTime(entry.at),style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                    }
                    Text(entry.message,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
