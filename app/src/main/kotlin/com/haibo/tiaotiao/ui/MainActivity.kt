package com.haibo.tiaotiao.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.network.RuleSyncClient
import com.haibo.tiaotiao.network.readLimited
import com.haibo.tiaotiao.service.SkipAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

private val Pine = Color(0xFF276751)
private val Cream = Color(0xFFF7F8F3)
private data class InstalledApp(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private val repository get() = (application as TiaoApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Pine, secondary = Color(0xFF536B5F),
                secondaryContainer = Color(0xFFDDEBDD), onSecondaryContainer = Pine,
                background = Cream, surface = Color.White, surfaceVariant = Color(0xFFE8EEE6))) { App() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val settings by repository.settings.collectAsStateWithLifecycle()
        val rules by repository.rules.collectAsStateWithLifecycle()
        val logs by repository.logs.collectAsStateWithLifecycle()
        val connected by repository.connected.collectAsStateWithLifecycle()
        val samples by repository.sampleCount.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tab by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var disclosure by remember { mutableStateOf(false) }
        var cloudDisclosure by remember { mutableStateOf(false) }
        var jsonRule by remember { mutableStateOf<SkipRule?>(null) }
        fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
        fun task(action: suspend () -> String) {
            if (busy) return
            scope.launch {
                busy = true
                try { val message = withContext(Dispatchers.IO) { action() }; snackbar.showSnackbar(message) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { snackbar.showSnackbar(error.message?.take(160) ?: "操作未完成，请重试") }
                finally { busy = false }
            }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) task {
                val text = requireNotNull(contentResolver.openInputStream(uri)).use { String(it.readLimited(RuleCodec.MAX_BYTES), Charsets.UTF_8) }
                "已导入 ${repository.importRules(text)} 条新规则，试跳确认后可自动使用"
            }
        }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) task {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(repository.exportVerified().toByteArray(Charsets.UTF_8)) }
                "已导出本机已验证规则"
            }
        }
        fun openTarget(rule: SkipRule) {
            if (!connected || !settings.enabled) { notify("请先连接无障碍服务并开启自动跳过"); return }
            SkipAccessibilityService.instance?.restartSession()
            val intent = if (rule.packageName == packageName) Intent(this, DemoActivity::class.java)
                else packageManager.getLaunchIntentForPackage(rule.packageName)
            if (intent == null) notify("未找到应用入口，请手动打开目标应用")
            else runCatching { startActivity(intent) }.onFailure { notify("无法打开目标应用，请手动打开") }
        }

        Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Column {
                Text("跳跳", fontWeight = FontWeight.Bold)
                Text("让等待少一点", style = MaterialTheme.typography.labelMedium, color = Pine)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)) },
            bottomBar = { NavigationBar(containerColor = Color.White) {
                listOf("首页", "应用", "规则", "记录", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("⌂", "▦", "≡", "◷", "⚙")[index], style = MaterialTheme.typography.titleLarge) }, label = { Text(title) })
                }
            } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Pine), shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (!connected) "等待服务连接" else if (settings.enabled) "已准备好，轻松一点" else "已暂停", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text("新界面由 Jev 判断，确认后记住。\n熟悉的界面，直接按规则跳过。", color = Color(0xFFE1EEE4))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("自动跳过", color = Color.White, modifier = Modifier.weight(1f))
                                        Switch(checked = settings.enabled, modifier = Modifier.semantics { contentDescription = "自动跳过" }, onCheckedChange = { repository.updateSettings { s -> s.copy(enabled = it) } })
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Stat("已验证", rules.count { it.isVerified() }.toString(), Modifier.weight(1f))
                                Stat("待处理", rules.count { it.status in setOf(RuleStatus.PENDING, RuleStatus.IMPORTED, RuleStatus.TRIALLED) }.toString(), Modifier.weight(1f))
                                Stat("应用", settings.packages.size.toString(), Modifier.weight(1f))
                            }
                        }
                        item { SectionCard("开始使用") {
                            Text(if (connected) "● 无障碍服务已连接" else "○ 无障碍服务未连接", color = Pine)
                            Text("开启服务后，到「应用」选择需要处理的应用。小米系统如回收服务，可从这里重新开启。", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { disclosure = true }, modifier = Modifier.fillMaxWidth()) { Text("打开无障碍设置") }
                            OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("选择应用") }
                        } }
                        item { SectionCard("先试一次，不用密钥") {
                            Text("演示完整流程：试跳 → 人工确认 → 再次打开自动跳过。演示不会调用 Jev。")
                            OutlinedButton(onClick = {
                                if (!connected || !settings.enabled) notify("请先连接无障碍服务并开启自动跳过")
                                else startActivity(Intent(this@MainActivity, DemoActivity::class.java))
                            }, modifier = Modifier.fillMaxWidth()) { Text("打开模拟开屏") }
                        } }
                        val awaiting = rules.filter { it.status == RuleStatus.TRIALLED }
                        if (awaiting.isNotEmpty()) item { Text("等待你确认结果", fontWeight = FontWeight.SemiBold) }
                        items(awaiting, key = { it.id }) { rule -> RuleCard(rule, repository, { openTarget(rule) }, { jsonRule = rule }, { task { it(); "已更新规则" } }) }
                    }
                    1 -> Applications(repository)
                    2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { Text("你的规则库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                        item { Text("JSON 保存页面条件与点击目标。导入规则先试跳确认；仅导出本机已验证的规则。", style = MaterialTheme.typography.bodyMedium) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("导入 JSON") }
                            OutlinedButton(onClick = { exporter.launch("tiaotiao-rules.json") }, enabled = !busy) { Text("导出") }
                            Button(onClick = {
                                task {
                                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                                    val file = File(dir, "tiaotiao-rules.json").apply { writeText(repository.exportVerified()) }
                                    withContext(Dispatchers.Main) {
                                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                                        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("跳跳规则", uri) }
                                        startActivity(Intent.createChooser(intent, "分享已验证规则"))
                                    }
                                    "已准备分享文件"
                                }
                            }, enabled = !busy) { Text("分享") }
                        } }
                        if (rules.isEmpty()) item { EmptyState("规则库还是空的", "先运行演示，或开启 Jev 后打开选中的应用。") }
                        items(rules.sortedByDescending { it.createdAt }, key = { it.id }) { rule ->
                            RuleCard(rule, repository, { openTarget(rule) }, { jsonRule = rule }, { task { it(); "已更新规则" } })
                        }
                    }
                    3 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("处理记录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { task { repository.clearLogs(); "已清空记录" } }) { Text("清空") }
                        } }
                        item { Text("记录保留最近 150 条。发起点击表示系统接受动作，实际试跳结果由你确认。", style = MaterialTheme.typography.bodyMedium) }
                        if (logs.isEmpty()) item { EmptyState("暂无记录", "服务开始处理后，会在这里显示结果。") }
                        items(logs) { entry -> SectionCard(entry.message) {
                            Text(entry.packageName.ifBlank { "跳跳服务" }, style = MaterialTheme.typography.bodySmall)
                            Text(time(entry.at), style = MaterialTheme.typography.labelSmall, color = Pine)
                        } }
                    }
                    4 -> SettingsPage(repository, busy, samples, { cloudDisclosure = true }, { task(it) })
                }
            }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启跳跳无障碍服务") }, text = {
            Text("跳跳会在你选择的应用进入前台后，短时读取界面结构和跳过按钮特征，并执行已经验证的点击方案。\n\n新方案需要试跳后由你确认。你可随时暂停或关闭服务。Jev 云端分析需要在设置中另行开启。")
        }, confirmButton = { TextButton(onClick = { disclosure = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("了解，前往设置") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } })
        if (cloudDisclosure) AlertDialog(onDismissRequest = { cloudDisclosure = false }, title = { Text("允许 Jev 分析未知界面") }, text = {
            Text("对你选中的应用，跳跳会把筛选后的控件信息（包名、版本、控件 ID、位置及跳过/广告等允许词）发送到 TypeSafe 官方 API。第一版不发送截图或输入框内容。\n\n仅在本地没有匹配规则时请求，已验证规则可离线使用。你可随时关闭。")
        }, confirmButton = { TextButton(onClick = { repository.updateSettings { it.copy(cloudEnabled = true) }; cloudDisclosure = false }) { Text("允许") } },
            dismissButton = { TextButton(onClick = { cloudDisclosure = false }) { Text("取消") } })
        jsonRule?.let { rule -> AlertDialog(onDismissRequest = { jsonRule = null }, title = { Text("规则 JSON") }, text = {
            LazyColumn { item { Text(RuleCodec.encode(listOf(rule), rule.createdAt), style = MaterialTheme.typography.bodySmall) } }
        }, confirmButton = { TextButton(onClick = { jsonRule = null }) { Text("关闭") } }) }
    }

    @Composable private fun Applications(repository: AppRepository) {
        val settings by repository.settings.collectAsStateWithLifecycle()
        var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var query by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0).map {
                    InstalledApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
                }.filter { it.packageName != packageName }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            }
            loading = false
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text("只处理你选择的应用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("默认全部关闭。模拟开屏不需要在此选择。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索名称或包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                items(apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(app.name, fontWeight = FontWeight.Medium); Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                        Switch(checked = app.packageName in settings.packages, modifier = Modifier.semantics { contentDescription = "处理 ${app.name}" }, onCheckedChange = { checked ->
                            repository.updateSettings { it.copy(packages = if (checked) it.packages + app.packageName else it.packages - app.packageName) }
                        })
                    }
                    HorizontalDivider(color = Color(0xFFE7EAE3))
                }
            }
        }
    }

    @Composable private fun SettingsPage(repository: AppRepository, busy: Boolean, samples: Int, allowCloud: () -> Unit, task: (suspend () -> String) -> Unit) {
        val settings by repository.settings.collectAsStateWithLifecycle()
        var model by remember(settings.model) { mutableStateOf(settings.model) }
        var key by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(repository.vault.get("jevKey").isNotBlank()) }
        var url by remember(settings.syncUrl) { mutableStateOf(settings.syncUrl) }
        var authorization by remember { mutableStateOf("") }
        var authSaved by remember { mutableStateOf(repository.vault.get("syncAuthorization").isNotBlank()) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("按你的方式运行", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            item { SectionCard("Jev · 未知界面分析") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许云端分析", Modifier.weight(1f))
                    Switch(checked = settings.cloudEnabled, modifier = Modifier.semantics { contentDescription = "允许 Jev 云端分析" }, onCheckedChange = { if (it) allowCloud() else repository.updateSettings { s -> s.copy(cloudEnabled = false) } })
                }
                Text("使用 TypeSafe 官方 API。API Key 在手机上加密保存，不进入规则文件。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(if (keySaved) "API Key 已保存，留空保持" else "Jev API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { task {
                        require(model.trim().matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "模型名称格式无效" }
                        if (key.isNotBlank()) repository.vault.set("jevKey", key.trim())
                        repository.updateSettings { it.copy(model = model.trim()) }
                        withContext(Dispatchers.Main) { key = ""; keySaved = repository.vault.get("jevKey").isNotBlank() }
                        "已保存 Jev 配置"
                    } }, enabled = !busy) { Text("保存配置") }
                    TextButton(onClick = { task {
                        repository.vault.set("jevKey", ""); repository.updateSettings { it.copy(cloudEnabled = false) }
                        withContext(Dispatchers.Main) { key = ""; keySaved = false }; "已清除密钥并关闭云端分析"
                    } }, enabled = !busy) { Text("清除密钥") }
                }
            } }
            item { SectionCard("JSON 同步 · 你的 HTTPS / WebDAV 空间") {
                Text("填写一个可 GET / PUT 的 JSON 文件地址。手动同步本机已验证规则；更新远端需要服务器支持 ETag 条件写入。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("https://…/tiaotiao-rules.json") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(value = authorization, onValueChange = { authorization = it }, label = { Text(if (authSaved) "Authorization 已保存，留空保持" else "Authorization（可选）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { task {
                    if (authorization.isNotBlank()) repository.vault.set("syncAuthorization", authorization.trim())
                    repository.updateSettings { it.copy(syncUrl = url.trim(), syncEtag = if (it.syncUrl == url.trim()) it.syncEtag else "") }
                    withContext(Dispatchers.Main) { authorization = ""; authSaved = repository.vault.get("syncAuthorization").isNotBlank() }
                    "已保存同步配置"
                } }, enabled = !busy) { Text("保存同步配置") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { task { "已下载 ${RuleSyncClient(repository).download()} 条新规则，等待试跳确认" } }, enabled = !busy && settings.syncUrl.isNotBlank()) { Text("下载规则") }
                    OutlinedButton(onClick = { task { RuleSyncClient(repository).upload(); "已上传本机已验证规则" } }, enabled = !busy && settings.syncUrl.isNotBlank()) { Text("上传规则") }
                }
                TextButton(onClick = { task {
                    repository.vault.set("syncAuthorization", ""); withContext(Dispatchers.Main) { authorization = ""; authSaved = false }; "已清除同步凭据"
                } }, enabled = !busy) { Text("清除同步凭据") }
            } }
            item { SectionCard("本地数据") {
                Text("已保存 $samples 个经过筛选的节点样本，最多保留 100 个。默认不上传样本。")
                Text("第一版使用节点识别，不采集截图。图片分析会在后续单独验证后加入。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { task { repository.clearSamples(); "已清空本地样本" } }, enabled = !busy) { Text("清空样本") }
            } }
            item { Text("跳跳 0.1.0 · Android 8.0 及以上\n服务被系统关闭后，需在系统设置重新开启。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        }
    }
}

@Composable private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFFE8EEE6), RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Pine, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
@Composable private fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}
@Composable private fun RuleCard(rule: SkipRule, repository: AppRepository, open: () -> Unit, showJson: () -> Unit, task: (() -> Unit) -> Unit) {
    val state = when(rule.status) { RuleStatus.PENDING -> "待试跳"; RuleStatus.TRIALLED -> "待确认结果"; RuleStatus.VERIFIED -> "已验证"; RuleStatus.IMPORTED -> "已导入 · 待试跳"; RuleStatus.DISABLED -> "已停用" }
    SectionCard(if (rule.source == "demo-local") "演示规则 · $state" else state) {
        Text(rule.packageName, style = MaterialTheme.typography.bodyMedium)
        Text("应用版本 ${rule.versionCode} · ${rule.target.label}\n${rule.target.resourceId.ifBlank { rule.target.className }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        if (rule.status == RuleStatus.TRIALLED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { task { repository.confirm(rule.id, true) } }) { Text("确实跳过") }
            OutlinedButton(onClick = { task { repository.confirm(rule.id, false) } }) { Text("没有跳过") }
        }
        if (rule.status == RuleStatus.TRIALLED) TextButton(onClick = { task {
            repository.put(rule.copy(status = RuleStatus.PENDING, trial = null, validation = null))
        } }) { Text("重新试跳") }
        if (rule.status in setOf(RuleStatus.PENDING, RuleStatus.IMPORTED)) OutlinedButton(onClick = open) { Text("打开应用试跳") }
        Row {
            TextButton(onClick = showJson) { Text("查看 JSON") }
            if (rule.isVerified()) TextButton(onClick = { task { repository.put(rule.copy(status = RuleStatus.DISABLED)) } }) { Text("停用") }
            TextButton(onClick = { task { repository.remove(rule.id) } }) { Text("删除") }
        }
    }
}
private fun time(at: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))
