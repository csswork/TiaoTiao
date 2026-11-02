package com.haibo.tiaotiao.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haibo.tiaotiao.data.AppCategory
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.TiaoApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ApplicationsPage(repository: AppRepository, onlySelected: Boolean, onOnlySelected: (Boolean) -> Unit,
    onConfigure: () -> Unit) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsStateWithLifecycle()
    val classifier = (context.applicationContext as TiaoApplication).appClassification
    val classification by classifier.state.collectAsStateWithLifecycle()
    val apps = classification.apps
    val loading = classification.loading
    val loadFailed = classification.failed && apps.isEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(AppCategory.ALL) }
    var showCategories by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { classifier.refresh() }
    val scoped = remember(apps, onlySelected, settings.packages, query) {
        val search = query.trim()
        apps.filter { app ->
            (!onlySelected || app.packageName in settings.packages) &&
                (app.name.contains(search, true) || app.packageName.contains(search, true))
        }
    }
    val filtered = remember(scoped, category) { scoped.filter { category == AppCategory.ALL || it.category == category } }
    val counts = remember(scoped) { scoped.groupingBy { it.category }.eachCount() }
    val available = remember(apps, category) {
        AppCategory.entries.filter { it == AppCategory.ALL || it == category || apps.any { app -> app.category == it } }
    }
    val hasFilters = category != AppCategory.ALL || query.isNotBlank()
    fun clearFilters() { category = AppCategory.ALL; query = "" }
    LaunchedEffect(onlySelected, category, query) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(if (onlySelected) "已选择的应用" else "应用列表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("选择要处理的应用，可按类别或名称查找。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索名称或包名") },
            leadingIcon = { QingIcon(QingGlyph.SEARCH,size=20.dp) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !onlySelected, onClick = { onOnlySelected(false) }, label = { Text("全部") })
            FilterChip(selected = onlySelected, onClick = { onOnlySelected(true) }, label = { Text("已选择 ${settings.packages.size}") })
            FilterChip(selected = category != AppCategory.ALL, onClick = { showCategories = true }, enabled = !loading && !loadFailed,
                label = { Text(if (category == AppCategory.ALL) "类别" else category.title) },
                trailingIcon = { QingIcon(QingGlyph.CHEVRON_DOWN,size=20.dp) },
                modifier = Modifier.semantics { contentDescription = "类别：${category.title}" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${filtered.size} 个应用", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (hasFilters) TextButton(onClick = ::clearFilters) { Text("重置") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(classification.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = Color.Gray)
            if (!loading && !classification.running) {
                if (classification.canClassify) TextButton(onClick = {
                    classifier.refresh(force = !classification.failed, retry = true)
                }) { Text(if (classification.failed) "重试" else "重新识别") }
                else if (!loadFailed && apps.any { it.category == AppCategory.PENDING }) TextButton(onClick = onConfigure) { Text("配置服务") }
            }
        }
        if (loading || classification.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 12.dp)) {
            if (!loading && loadFailed) item {
                AppListMessage("暂时无法读取应用列表", "请稍后重试。") {
                    TextButton(onClick = { classifier.refresh(retry = true) }) { Text("重新加载") }
                }
            }
            else if (!loading && filtered.isEmpty()) item {
                val noneSelected = onlySelected && settings.packages.isEmpty() && !hasFilters
                AppListMessage(if (noneSelected) "还没有选择应用" else "没有匹配的应用",
                    if (noneSelected) "切换到全部应用，开启需要处理的应用。" else "试试其他类别、修改搜索词，或切换应用范围。") {
                    if (hasFilters) TextButton(onClick = ::clearFilters) { Text("清除筛选") }
                    if (onlySelected) TextButton(onClick = { onOnlySelected(false) }) { Text("查看全部应用") }
                }
            }
            items(filtered, key = { it.packageName }, contentType = { "application" }) { app ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.name, fontWeight = FontWeight.Medium)
                        Text(app.category.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Switch(checked = app.packageName in settings.packages,
                        modifier = Modifier.semantics { contentDescription = "处理 ${app.name}" }, onCheckedChange = { checked ->
                            repository.updateSettings { it.copy(packages = if (checked) it.packages + app.packageName else it.packages - app.packageName) }
                        })
                }
                HorizontalDivider(color = Color(0xFFE7EAE3))
            }
        }
    }
    if (showCategories) ModalBottomSheet(onDismissRequest = { showCategories = false }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按类别筛选", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("数量对应当前应用范围和搜索条件。", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).selectableGroup(), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(available, key = { it.name }) { option ->
                ListItem(headlineContent = { Text(option.title) },
                    trailingContent = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text((if (option == AppCategory.ALL) scoped.size else counts[option] ?: 0).toString(), style = MaterialTheme.typography.bodyMedium)
                        RadioButton(selected = category == option, onClick = null)
                    } },
                    modifier = Modifier.fillMaxWidth().selectable(selected = category == option, role = Role.RadioButton,
                        onClick = { category = option; showCategories = false })
                        .semantics { contentDescription = "类别选项：${option.title}" })
            }
        }
    }
}

@Composable private fun AppListMessage(title: String, message: String, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
