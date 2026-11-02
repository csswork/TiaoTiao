package com.haibo.tiaotiao.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.haibo.tiaotiao.data.ConnectionState
import com.haibo.tiaotiao.network.RuleSyncClient
import com.haibo.tiaotiao.network.readLimited
import com.haibo.tiaotiao.network.testModelConnection
import com.haibo.tiaotiao.service.SkipAccessibilityService
import java.io.File
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
        val visualRules by repository.visualRules.collectAsStateWithLifecycle()
        val connected by repository.connected.collectAsStateWithLifecycle()
        val samples by repository.sampleCount.collectAsStateWithLifecycle()
        val modelConnection by repository.modelConnection.collectAsStateWithLifecycle()
        val detection by repository.detection.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var ruleFilter by rememberSaveable { mutableStateOf(RuleFilter.ALL) }
        var selectedRuleKey by rememberSaveable { mutableStateOf<String?>(null) }
        var onlySelectedApps by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var disclosure by remember { mutableStateOf(false) }
        var cloudDisclosure by remember { mutableStateOf(false) }
        var transferAction by remember { mutableStateOf<String?>(null) }
        var hasJevKey by remember { mutableStateOf(repository.vault.get("jevKey").isNotBlank()) }
        var focusJevKey by remember { mutableStateOf(false) }
        var exportSmart by rememberSaveable { mutableStateOf(false) }
        val entries=remember(rules,visualRules) { rules.map { RuleItem(ordinary=it) } + visualRules.map { RuleItem(visual=it) } }
        val selectedRule=entries.find { it.key==selectedRuleKey }
        LaunchedEffect(selectedRuleKey,entries) { if(selectedRuleKey!=null && selectedRule==null) selectedRuleKey=null }
        BackHandler(enabled=selectedRuleKey!=null || tab!=0) { if(selectedRuleKey!=null) selectedRuleKey=null else tab=0 }
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
                if (text.contains("\"TiaoTiaoVisualRules\"")) "已导入 ${repository.importVisual(text)} 条智能候选，等待本机自动检查"
                else "已导入 ${repository.importRules(text)} 条新规则，试跳确认后可自动使用"
            }
        }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) task {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write((if (exportSmart) repository.exportVisual() else repository.exportVerified()).toByteArray(Charsets.UTF_8)) }
                "已导出本机已验证规则"
            }
        }
        fun transfer(smart: Boolean) {
            val action=transferAction
            transferAction=null
            exportSmart=smart
            if(action=="export") exporter.launch(if(smart) "tiaotiao-visual-rules.json" else "tiaotiao-rules.json")
            else if(action=="share") task {
                val dir=File(cacheDir,"shared").apply { mkdirs() }
                val file=File(dir,if(smart) "tiaotiao-visual-rules.json" else "tiaotiao-rules.json").apply {
                    writeText(if(smart) repository.exportVisual() else repository.exportVerified())
                }
                withContext(Dispatchers.Main) {
                    val uri=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
                    val intent=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri("TiaoTiao 规则",uri) }
                    startActivity(Intent.createChooser(intent,"分享已验证规则"))
                }
                "已准备分享文件"
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
            topBar = { TopAppBar(navigationIcon={ if(selectedRuleKey!=null) TextButton(onClick={selectedRuleKey=null},modifier=Modifier.semantics { contentDescription="返回规则列表" }) { Text("‹ 返回") } },
                title = { Column {
                Text(if(selectedRuleKey!=null) "规则详情" else "TiaoTiao", fontWeight = FontWeight.Bold)
                Text(if(selectedRuleKey!=null) "点击位置与处理进度" else "让等待少一点", style = MaterialTheme.typography.labelMedium, color = Pine)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)) },
            bottomBar = { if(selectedRuleKey==null) NavigationBar(containerColor = Color.White) {
                listOf("首页", "应用", "规则", "记录", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; if(index==2) ruleFilter=RuleFilter.ALL; if(index==1) onlySelectedApps=false },
                        icon = { Text(listOf("⌂", "▦", "≡", "◷", "⚙")[index], style = MaterialTheme.typography.titleLarge) }, label = { Text(title) })
                }
            } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(selectedRule!=null) RuleDetailPage(selectedRule,busy,
                    onOpen={selectedRule.ordinary?.let {openTarget(it)}},
                    onConfirm={success -> selectedRule.ordinary?.let {rule -> task {repository.confirm(rule.id,success);"已更新规则"}}},
                    onRetry={selectedRule.ordinary?.let {rule -> task {repository.put(rule.copy(status=RuleStatus.PENDING,trial=null,validation=null));"可以重新打开应用试跳"}}},
                    onPause={task {
                        selectedRule.ordinary?.let {repository.put(it.copy(status=RuleStatus.DISABLED))}
                        selectedRule.visual?.let {r -> repository.visualRules.value.filter {it.packageName==r.packageName && it.scene==r.scene}.forEach {
                            repository.putVisual(it.copy(state=VisualRuleState.PAUSED,lastResult="用户反馈点错，已停用"))
                        }}
                        "已停用方案"
                    }},
                    onRelearn={task {
                        selectedRule.visual?.let {r -> repository.visualRules.value.filter {it.packageName==r.packageName && it.scene==r.scene}.forEach {
                            repository.putVisual(it.copy(state=VisualRuleState.CANDIDATE,evidence=emptyList(),awaitingLocalReview=true,lastResult="等待 AI 重新识别"))
                        }}
                        "已安排重新学习"
                    }},
                    onDelete={task {
                        selectedRule.ordinary?.let {repository.remove(it.id)}
                        selectedRule.visual?.let {repository.removeVisual(it.id)}
                        "已删除方案"
                    }})
                else when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!hasJevKey) item(key = "missing_jev_key") {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE8BF)),
                                border = BorderStroke(1.dp, Color(0xFFE3BC77))) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("尚未填写 Jev API Key", style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold, color = Color(0xFF654516))
                                    Text("填写密钥并开启云端分析，即可使用 Jev 识别新的开屏广告。",
                                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF654516))
                                    Button(onClick = { focusJevKey = true; tab = 4 }, modifier = Modifier.fillMaxWidth()) {
                                        Text("填写 API Key")
                                    }
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Pine), shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(when {
                                        !connected -> "等待服务连接"
                                        !settings.enabled -> "已暂停"
                                        settings.cloudEnabled && (!hasJevKey || modelConnection.state != ConnectionState.CONNECTED) -> "本地规则可用，模型待检查"
                                        else -> "已准备好，轻松一点"
                                    }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text("已验证的界面，按规则自动跳过。\n新方案的学习与检查进度，随时可查。", color = Color(0xFFE1EEE4))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("自动跳过", color = Color.White, modifier = Modifier.weight(1f))
                                        Switch(checked = settings.enabled, modifier = Modifier.semantics { contentDescription = "自动跳过" }, onCheckedChange = { repository.updateSettings { s -> s.copy(enabled = it) } })
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Stat("已验证", entries.count { it.verified }.toString(), Modifier.weight(1f)) { ruleFilter=RuleFilter.VERIFIED;tab=2 }
                                Stat("待处理", entries.count { it.pending }.toString(), Modifier.weight(1f)) { ruleFilter=RuleFilter.PENDING;tab=2 }
                                Stat("应用", settings.packages.size.toString(), Modifier.weight(1f)) { onlySelectedApps=true;tab=1 }
                            }
                        }
                        detection?.let { latest -> item { SectionCard("最近处理") {
                            Text(latest.message,style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={tab=3}) { Text("查看处理记录 ›") }
                        } } }
                        item { SectionCard("大麦 · 智能自动学习试用") {
                            Text("自动采样 → 视觉 AI 学习 → 自动尝试 → 结果检查。方案会缓存，页面结束后仍可继续学习。")
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("智能学习",Modifier.weight(1f))
                                Switch(checked=settings.smartDamai,onCheckedChange={ repository.updateSettings { s -> s.copy(smartDamai=it) } })
                            }
                            Text("智能规则：${visualRules.count { it.active() }} 条已启用，${visualRules.count { it.state==VisualRuleState.CANDIDATE }} 条学习中",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={
                                if (!connected || !settings.enabled) notify("请先开启服务和自动跳过")
                                else startActivity(Intent(this@MainActivity,SmartDemoActivity::class.java))
                            }) { Text("智能视觉演示（无需确认）") }
                        } }
                        item { SectionCard("开始使用") {
                            Text(if (connected) "● 无障碍服务已连接" else "○ 无障碍服务未连接", color = Pine)
                            Text("开启服务后，到「应用」选择需要处理的应用。小米系统如回收服务，可从这里重新开启。", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { disclosure = true }, modifier = Modifier.fillMaxWidth()) { Text("打开无障碍设置") }
                            OutlinedButton(onClick = { onlySelectedApps=false;tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("选择应用") }
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
                        items(awaiting, key = { it.id }) { rule -> RuleSummary(RuleItem(ordinary=rule), { ruleFilter=RuleFilter.PENDING;tab=2;selectedRuleKey="ordinary-${rule.id}" }) }
                        if (hasJevKey) item(key="model_connection") { SectionCard("AI 服务已配置") {
                            Text(modelConnection.message,color=if(modelConnection.state==ConnectionState.FAILED) Color(0xFF9C3B20) else Pine,style=MaterialTheme.typography.bodyMedium)
                            Text(if(settings.cloudEnabled) "${settings.jevProvider.displayName} · 云端分析已开启" else "云端分析已关闭，已验证规则仍可本地使用",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={focusJevKey=false;tab=4}) { Text("管理 API 配置 ›") }
                        } }
                    }
                    1 -> Applications(repository,onlySelectedApps,{onlySelectedApps=it})
                    2 -> RuleListPage(entries,ruleFilter,{ruleFilter=it},{selectedRuleKey=it.key},
                        onImport={importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},
                        onExport={transferAction="export"},onShare={transferAction="share"},busy=busy)
                    3 -> ProcessingLogPage(repository,busy,onClear={task {repository.clearLogs();"已清空记录"}})
                    4 -> SettingsPage(repository = repository, busy = busy, samples = samples,
                        hasJevKey = hasJevKey, focusJevKey = focusJevKey,
                        onKeyEntryHandled = { focusJevKey = false },
                        onKeyPresenceChanged = { hasJevKey = it },
                        allowCloud = { cloudDisclosure = true }, task = { task(it) })
                }
            }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启 TiaoTiao 无障碍服务") }, text = {
            Text("TiaoTiao 在选定应用启动后短时读取界面并执行跳过。大麦智能试用还会截图、端侧文字识别、自动尝试跳过并检查结果；其他应用沿用普通确认流程。你可随时暂停。云端分析需另行开启。")
        }, confirmButton = { TextButton(onClick = { disclosure = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("了解，前往设置") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } })
        if (cloudDisclosure) AlertDialog(onDismissRequest = { cloudDisclosure = false }, title = { Text("允许 Jev 分析未知界面") }, text = {
            Text("普通规则发送筛选后的控件信息。大麦智能试用会向 Vercel 及视觉模型发送遮挡非必要文字的开屏和点击前后画面。图片最多缓存 8 份，识别成功后删除，未识别画面在 24 小时后清理，不导出分享。可随时关闭智能学习或云端分析。")
        }, confirmButton = { TextButton(onClick = { repository.updateSettings { it.copy(cloudEnabled = true) }; cloudDisclosure = false }) { Text("允许") } },
            dismissButton = { TextButton(onClick = { cloudDisclosure = false }) { Text("取消") } })
        transferAction?.let { action -> AlertDialog(onDismissRequest={transferAction=null},
            title={Text(if(action=="share") "分享已验证方案" else "导出已验证方案")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("选择方案类型。文件不包含 API 密钥或截图。")
                OutlinedButton(onClick={transfer(false)},enabled=rules.any {it.isVerified()},modifier=Modifier.fillMaxWidth()) {
                    Text("普通规则 · ${rules.count {it.isVerified()}} 条")
                }
                OutlinedButton(onClick={transfer(true)},enabled=visualRules.any {it.active()},modifier=Modifier.fillMaxWidth()) {
                    Text("智能规则 · ${visualRules.count {it.active()}} 条")
                }
            }},confirmButton={TextButton(onClick={transferAction=null}) {Text("取消")}}) }

    }

    @Composable private fun Applications(repository: AppRepository, onlySelected: Boolean, onOnlySelected: (Boolean) -> Unit) {
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
            Text(if(onlySelected) "已选择的应用" else "应用列表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("默认全部关闭。模拟开屏不需要在此选择。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索名称或包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!onlySelected,onClick={onOnlySelected(false)},label={Text("全部应用")})
                FilterChip(selected=onlySelected,onClick={onOnlySelected(true)},label={Text("已选择 ${settings.packages.size}")})
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            val filtered=remember(apps,onlySelected,settings.packages,query) {
                apps.filter { (!onlySelected || it.packageName in settings.packages) && (it.name.contains(query,true) || it.packageName.contains(query,true)) }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                if (!loading && filtered.isEmpty()) item { EmptyState(if(query.isNotBlank()) "没有找到匹配的应用" else "还没有选择应用", "可切换到全部应用，搜索并开启需要处理的应用。") }
                items(filtered, key = { it.packageName },contentType={"application"}) { app ->
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

    @Composable private fun SettingsPage(repository: AppRepository, busy: Boolean, samples: Int,
        hasJevKey: Boolean, focusJevKey: Boolean, onKeyEntryHandled: () -> Unit,
        onKeyPresenceChanged: (Boolean) -> Unit, allowCloud: () -> Unit, task: (suspend () -> String) -> Unit) {
        val settings by repository.settings.collectAsStateWithLifecycle()
        val connection by repository.modelConnection.collectAsStateWithLifecycle()
        var model by remember(settings.model) { mutableStateOf(settings.model) }
        var provider by remember(settings.jevProvider) { mutableStateOf(settings.jevProvider) }
        var key by remember { mutableStateOf("") }
        var visionModel by remember(settings.visionModel) { mutableStateOf(settings.visionModel) }
        val listState = rememberLazyListState()
        val keyFocusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        var url by remember(settings.syncUrl) { mutableStateOf(settings.syncUrl) }
        var authorization by remember { mutableStateOf("") }
        var authSaved by remember { mutableStateOf(repository.vault.get("syncAuthorization").isNotBlank()) }
        LaunchedEffect(focusJevKey) {
            if (focusJevKey) {
                listState.scrollToItem(1)
                withFrameNanos { }
                keyFocusRequester.requestFocus()
                keyboard?.show()
                onKeyEntryHandled()
            }
        }
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("按你的方式运行", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            item { SectionCard("Jev · 未知界面分析") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许云端分析", Modifier.weight(1f))
                    Switch(checked = settings.cloudEnabled, modifier = Modifier.semantics { contentDescription = "允许 Jev 云端分析" }, onCheckedChange = { if (it) allowCloud() else repository.updateSettings { s -> s.copy(cloudEnabled = false) } })
                }
                Text("选择密钥对应的接入服务", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JevProvider.entries.forEach { option -> FilterChip(
                        selected = provider == option, enabled = !busy,
                        onClick = { model = option.migrateModel(model); provider = option },
                        label = { Text(if (option == JevProvider.TYPE_SAFE) "TypeSafe 直连" else "Vercel 网关") }) }
                }
                Text("Vercel 密钥请选择 Vercel 网关；TypeSafe 密钥请选择直连。密钥在手机上加密保存，留空会沿用已保存的密钥。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(if (hasJevKey) "API Key 已保存，留空保持" else "Jev API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().focusRequester(keyFocusRequester), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") },
                    supportingText = { Text("建议使用 ${provider.defaultModel}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { task {
                        provider.validateModel(model.trim())
                        if (key.isNotBlank()) repository.vault.set("jevKey", key.trim())
                        repository.invalidateModelConnection()
                        repository.updateSettings { it.copy(model = model.trim(), jevProvider = provider) }
                        withContext(Dispatchers.Main) {
                            key = ""; onKeyPresenceChanged(repository.vault.get("jevKey").isNotBlank())
                            focusManager.clearFocus(); keyboard?.hide()
                        }
                        testModelConnection(repository)
                    } }, enabled = !busy) { Text("保存并测试") }
                    TextButton(onClick = { task {
                        repository.vault.set("jevKey", ""); repository.updateSettings { it.copy(cloudEnabled = false) }
                        repository.invalidateModelConnection()
                        withContext(Dispatchers.Main) { key = ""; onKeyPresenceChanged(false) }; "已清除密钥并关闭云端分析"
                    } }, enabled = !busy) { Text("清除密钥") }
                }
                Text(connection.message, style = MaterialTheme.typography.bodyMedium,
                    color = if (connection.state == ConnectionState.FAILED) Color(0xFF9C3B20) else Pine)
                Text("连接测试会发送一条固定测试请求，不读取其他应用的内容。", style = MaterialTheme.typography.bodySmall)
            } }
            item { SectionCard("大麦 · 视觉学习模型") {
                Text("沿用 Vercel 网关密钥。端侧文字识别随安装包提供；未知画面由视觉模型学习，点击后自动检查结果。")
                Text("截图遮挡非必要文字后发送至 Vercel 及模型提供方；最多缓存 8 份，成功后删除，未识别画面按 24 小时过期清理。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=visionModel,onValueChange={visionModel=it},label={Text("视觉模型")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Button(onClick={ task {
                    require(visionModel.trim().matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))) { "请检查模型名称" }
                    repository.updateSettings { it.copy(visionModel=visionModel.trim()) };"已保存视觉模型"
                } },enabled=!busy) { Text("保存视觉模型") }
            } }
            item { SectionCard("规则同步 · 你的 HTTPS / WebDAV 空间") {
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
                Text("普通规则保留筛选后的节点样本；智能学习另有少量遮挡图片缓存，识别成功或过期后清理。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { task { repository.clearSamples(); (application as TiaoApplication).visualLearner.clearPending(); "已清空本地节点与视觉学习样本" } }, enabled = !busy) { Text("清空样本") }
            } }
            item { Text("TiaoTiao 0.2.2 · 智能视觉试用需要 Android 11 及以上\n服务被系统关闭后，需在系统设置重新开启。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        }
    }
}

@Composable internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun Stat(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick=onClick,modifier=modifier.semantics {contentDescription="查看${label}列表"},shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=Color(0xFFE8EEE6))) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(value,style=MaterialTheme.typography.headlineMedium,color=Pine,fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(label,style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                Text("›",color=Pine)
            }
        }
    }
}
@Composable private fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}
