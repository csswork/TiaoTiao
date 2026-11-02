package com.haibo.tiaotiao.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haibo.tiaotiao.data.AppRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Log emissions invalidate this page only, rather than every screen in MainActivity. */
@Composable internal fun ProcessingLogPage(repository: AppRepository, busy: Boolean, onClear: () -> Unit) {
    val logs by repository.logs.collectAsStateWithLifecycle()
    val formatter=remember {DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Text("处理记录",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            TextButton(onClick=onClear,enabled=!busy) {Text("清空")}
        }}
        item {Text("记录保留最近 150 条。智能试用会自动检查结果；原有普通规则继续使用人工确认流程。",style=MaterialTheme.typography.bodyMedium)}
        if(logs.isEmpty()) item {Text("暂无记录",Modifier.padding(vertical=36.dp),style=MaterialTheme.typography.titleMedium)}
        items(logs,key={it.id},contentType={"log"}) {entry ->
            val timestamp=remember(entry.at,formatter) {formatter.format(Instant.ofEpochMilli(entry.at))}
            SectionCard(entry.message) {
                Text(entry.packageName.ifBlank {"TiaoTiao 服务"},style=MaterialTheme.typography.bodySmall)
                Text(timestamp,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
