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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
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
import com.haibo.tiaotiao.R
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.data.ConnectionState
import com.haibo.tiaotiao.network.RuleSyncClient
import com.haibo.tiaotiao.network.readLimited
import com.haibo.tiaotiao.network.testModelConnection
import com.haibo.tiaotiao.network.testVisionConnection
import com.haibo.tiaotiao.service.SkipAccessibilityService
import java.io.File
import kotlinx.coroutines.*

private val Pine = Color(0xFF276751)
private val Cream = Color(0xFFF7F8F3)

class MainActivity : ComponentActivity() {
    private val repository get() = (application as TiaoApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as TiaoApplication).appClassification.refresh()
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
        val visionConnection by repository.visionConnection.collectAsStateWithLifecycle()
        val officialVision=settings.visionProvider==VisionProvider.DEEPSEEK_OFFICIAL
        val hasVisionKey=repository.visionKeyAvailable()
        val currentConnection=visionConnection
        val detection by repository.detection.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableIntStateOf(if(intent.getBooleanExtra("configure_vision",false) || intent.getBooleanExtra("configure_touch",false)) 4 else 0) }
        var ruleFilter by rememberSaveable { mutableStateOf(RuleFilter.ALL) }
        var selectedRuleKey by rememberSaveable { mutableStateOf<String?>(null) }
        var previewRequest by remember {mutableStateOf<Pair<String,String?>?>(intent.getStringExtra("preview_rule")?.let {it to null})}
        var onlySelectedApps by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var disclosure by remember { mutableStateOf(false) }
        var cloudDisclosure by remember { mutableStateOf(false) }
        var transferAction by remember { mutableStateOf<String?>(null) }
        var hasJevKey by remember { mutableStateOf(repository.vault.get("jevKey").isNotBlank()) }
        var focusJevKey by remember { mutableStateOf(false) }
        var focusVisionKey by remember { mutableStateOf(intent.getBooleanExtra("configure_vision",false)) }
        var exportSmart by rememberSaveable { mutableStateOf(false) }
        val entries=remember(rules,visualRules) { rules.map { RuleItem(ordinary=it) } + VisualProposals.tasks(visualRules).map {
            RuleItem(visual=it.representative,variants=it.variants,taskId=it.id)
        } }
        val selectedRule=entries.find { it.key==selectedRuleKey }
        LaunchedEffect(entries,previewRequest) {
            val request=previewRequest ?: return@LaunchedEffect
            entries.find {entry -> entry.variants.any {it.id==request.first && (request.second==null || it.behavior()==request.second)}}?.let {
                selectedRuleKey=it.key;tab=2;previewRequest=null
            }
        }
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
                val text = requireNotNull(contentResolver.openInputStream(uri)).use { String(it.readLimited(VisualPolicy.MAX_BYTES), Charsets.UTF_8) }
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
            topBar = { TopAppBar(navigationIcon={
                if(selectedRuleKey!=null) IconButton(onClick={selectedRuleKey=null},modifier=Modifier.semantics { contentDescription="返回规则列表" }) {
                    QingIcon(QingGlyph.CHEVRON_LEFT)
                } else Box(Modifier.padding(start=20.dp,end=8.dp),contentAlignment=Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher),contentDescription=null,modifier=Modifier.size(36.dp))
                }
            },
                title = { Column {
                Text(if(selectedRuleKey!=null) "规则详情" else "TiaoTiao", fontWeight = FontWeight.Bold)
                Text(if(selectedRuleKey!=null) "点击位置与处理进度" else "让等待少一点", style = MaterialTheme.typography.labelMedium, color = Pine)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)) },
            bottomBar = { if(selectedRuleKey==null) NavigationBar(containerColor = Color.White) {
                listOf("首页", "应用", "规则", "记录", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; if(index==2) ruleFilter=RuleFilter.ALL; if(index==1) onlySelectedApps=false },
                        icon = { QingIcon(listOf(QingGlyph.HOME,QingGlyph.APPS,QingGlyph.RULES,QingGlyph.HISTORY,QingGlyph.SETTINGS)[index]) }, label = { Text(title) })
                }
            } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(selectedRule!=null) RuleDetailPage(selectedRule,busy,
                    onConfirmVisual={rule -> task {
                        repository.confirmVisual(rule)
                        val behavior=repository.visualRules.value.find {it.id==rule.id}?.behavior()
                        withContext(Dispatchers.Main) {previewRequest=rule.id to behavior}
                        "已确认并启用方案"
                    }},
                    onOpen={selectedRule.ordinary?.let {openTarget(it)}},
                    onConfirm={success -> selectedRule.ordinary?.let {rule -> task {repository.confirm(rule.id,success);"已更新规则"}}},
                    onRetry={selectedRule.ordinary?.let {rule -> task {repository.put(rule.copy(status=RuleStatus.PENDING,trial=null,validation=null));"可以重新打开应用试跳"}}},
                    onPause={task {
                        selectedRule.ordinary?.let {repository.put(it.copy(status=RuleStatus.DISABLED))}
                        selectedRule.visual?.let {r -> repository.visualRules.value.filter {VisualProposals.sameTarget(it,r)}.forEach {
                            repository.putVisual(it.copy(state=VisualRuleState.PAUSED,lastResult="用户反馈点错，已停用"))
                        }}
                        "已停用方案"
                    }},
                    onRelearn={task {
                        selectedRule.visual?.let {r -> repository.visualRules.value.filter {VisualProposals.sameTarget(it,r)}.forEach {
                            repository.putVisual(it.copy(state=VisualRuleState.CANDIDATE,evidence=emptyList(),awaitingLocalReview=true,
                                manualConfirmation=null,retryAfter=0,automaticFailures=0,learningRevision=it.learningRevision+1,lastResult="等待 AI 重新识别"))
                        }}
                        "已安排重新学习"
                    }},
                    onAdjust={rule,preview,x,y -> task {
                        val result=repository.adjustTarget(rule,preview,x,y)
                        val behavior=repository.visualRules.value.find {it.id==rule.id}?.behavior()
                        withContext(Dispatchers.Main) {previewRequest=rule.id to behavior}
                        result
                    }},
                    onDelete={task {
                        selectedRule.ordinary?.let {repository.remove(it.id)}
                        selectedRule.variants.forEach {repository.removeVisual(it.id)}
                        "已删除方案"
                    }})
                else when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (if(officialVision) !hasVisionKey else !hasJevKey) item(key = "missing_ai_key") {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE8BF)),
                                border = BorderStroke(1.dp, Color(0xFFE3BC77))) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(if(officialVision) "尚未填写 DeepSeek 官方 Key" else "尚未填写 Jev API Key", style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold, color = Color(0xFF654516))
                                    Text(if(officialVision) "填写独立的官方 Key，使用 DeepSeek 识别开屏广告及检查结果。" else "填写密钥并开启云端分析，即可使用 Jev 识别新的开屏广告。",
                                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF654516))
                                    Button(onClick = { focusVisionKey=officialVision;focusJevKey=!officialVision;tab=4 }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if(officialVision) "填写 DeepSeek 官方 Key" else "填写 API Key")
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
                                        settings.cloudEnabled && (!hasVisionKey || currentConnection.state != ConnectionState.CONNECTED) -> "本地规则可用，模型待检查"
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
                            TextButton(onClick={tab=3}) { Text("查看处理记录");Spacer(Modifier.width(4.dp));QingIcon(QingGlyph.CHEVRON_RIGHT,size=20.dp) }
                        } } }
                        item { SectionCard("通用自动学习") {
                            Text(if(officialVision) "所有已选应用共用 DeepSeek 官方图片判断和端侧识别；方案缓存后自动尝试并检查结果。" else "所有已选应用共用：Jev 提前检查控件，OCR 与视觉识别补充图标；方案缓存后自动尝试并检查结果。")
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("智能学习",Modifier.weight(1f))
                                Switch(checked=settings.automaticLearning,onCheckedChange={ repository.updateSettings { s -> s.copy(automaticLearning=it) } })
                            }
                            Text("智能规则：${visualRules.count { it.active() }} 条已启用，${entries.count { it.visual!=null && it.pending }} 个任务学习中",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={
                                if (!connected || !settings.enabled) notify("请先开启服务和自动跳过")
                                else startActivity(Intent(this@MainActivity,SmartDemoActivity::class.java))
                            }) { Text("智能视觉演示（无需确认）") }
                        } }
                        item { SectionCard("开始使用") {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                QingIcon(if(connected) QingGlyph.CHECK_CIRCLE else QingGlyph.PENDING_CIRCLE,size=20.dp,tint=Pine)
                                Text(if (connected) "无障碍服务已连接" else "无障碍服务未连接", color = Pine)
                            }
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
                        if (hasVisionKey) item(key="model_connection") { SectionCard("AI 服务已配置") {
                            Text(currentConnection.message,color=if(currentConnection.state==ConnectionState.FAILED) Color(0xFF9C3B20) else Pine,style=MaterialTheme.typography.bodyMedium)
                            Text("图片与结果复核：${settings.visionProvider.displayName} · ${settings.visionModel}\n应用分类：${settings.model}",style=MaterialTheme.typography.bodySmall)
                            Text(if(settings.cloudEnabled) "${settings.visionProvider.displayName} · 云端分析已开启" else "云端分析已关闭，已验证规则仍可本地使用",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={focusJevKey=false;tab=4}) { Text("管理 API 配置");Spacer(Modifier.width(4.dp));QingIcon(QingGlyph.CHEVRON_RIGHT,size=20.dp) }
                        } }
                    }
                    1 -> ApplicationsPage(repository,onlySelectedApps,{onlySelectedApps=it},onConfigure={focusJevKey=!hasJevKey;tab=4})
                    2 -> RuleListPage(entries,ruleFilter,{ruleFilter=it},{selectedRuleKey=it.key},
                        onImport={importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},
                        onExport={transferAction="export"},onShare={transferAction="share"},busy=busy)
                    3 -> ProcessingLogPage(repository,busy,onClear={task {repository.clearLogs();"已清空记录"}})
                    4 -> SettingsPage(repository = repository, busy = busy, samples = samples,
                        hasJevKey = hasJevKey, focusJevKey = focusJevKey,
                        focusVisionKey=focusVisionKey,onVisionKeyEntryHandled={focusVisionKey=false},
                        onKeyEntryHandled = { focusJevKey = false },
                        onKeyPresenceChanged = { hasJevKey = it },
                        allowCloud = { cloudDisclosure = true }, task = { task(it) })
                }
            }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启 TiaoTiao 无障碍服务") }, text = {
            Text("TiaoTiao 在所有已选应用启动后短时检测广告，结合控件、图标和端侧识别，自动尝试并检查结果。Android 11 及以上支持截图；可随时暂停，云端分析需另行开启。")
        }, confirmButton = { TextButton(onClick = { disclosure = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("了解，前往设置") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } })
        if (cloudDisclosure) AlertDialog(onDismissRequest = { cloudDisclosure = false }, title = { Text("允许 AI 分析所选应用") }, text = {
            Text("遮挡文字后的图片发送至所选的 Jev 官方、DeepSeek 官方或 Vercel 服务。两套官方 Key 分别保存。待识别图片最多缓存 8 份，未识别样本在 24 小时后清理；识别和点击截图随方案保存在本机，删除方案时一并清除，不加入导出或同步。可随时关闭云端分析。")
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

    @Composable private fun SettingsPage(repository: AppRepository, busy: Boolean, samples: Int,
        hasJevKey: Boolean, focusJevKey: Boolean, onKeyEntryHandled: () -> Unit,
        focusVisionKey: Boolean,onVisionKeyEntryHandled: () -> Unit,
        onKeyPresenceChanged: (Boolean) -> Unit, allowCloud: () -> Unit, task: (suspend () -> String) -> Unit) {
        val settings by repository.settings.collectAsStateWithLifecycle()
        val connection by repository.modelConnection.collectAsStateWithLifecycle()
        val visionConnection by repository.visionConnection.collectAsStateWithLifecycle()
        var model by remember(settings.model) { mutableStateOf(settings.model) }
        var provider by remember(settings.jevProvider) { mutableStateOf(settings.jevProvider) }
        var key by remember { mutableStateOf("") }
        var visionModel by remember(settings.visionModel) { mutableStateOf(settings.visionModel) }
        var visionProvider by remember(settings.visionProvider) {mutableStateOf(settings.visionProvider)}
        var officialKey by remember(visionProvider) {mutableStateOf("")}
        val listState = rememberLazyListState()
        val keyFocusRequester = remember { FocusRequester() }
        val visionKeyFocusRequester=remember {FocusRequester()}
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        var url by remember(settings.syncUrl) { mutableStateOf(settings.syncUrl) }
        var authorization by remember { mutableStateOf("") }
        var authSaved by remember { mutableStateOf(repository.vault.get("syncAuthorization").isNotBlank()) }
        LaunchedEffect(Unit) {
            if(intent.getBooleanExtra("configure_touch",false)) listState.scrollToItem(3)
        }
        LaunchedEffect(focusJevKey) {
            if (focusJevKey) {
                listState.scrollToItem(1)
                withFrameNanos { }
                keyFocusRequester.requestFocus()
                keyboard?.show()
                onKeyEntryHandled()
            }
        }
        LaunchedEffect(focusVisionKey) {
            if(focusVisionKey) {
                listState.scrollToItem(2);withFrameNanos {}
                if(visionProvider==VisionProvider.DEEPSEEK_OFFICIAL) {
                    visionKeyFocusRequester.requestFocus();keyboard?.show()
                }
                onVisionKeyEntryHandled()
            }
        }
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("按你的方式运行", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            item { SectionCard("云端分析与 Jev（可选）") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text("显示检查气泡");Text("检测时显示提示，关闭后仍在后台检查",style=MaterialTheme.typography.bodySmall)}
                    Switch(checked=settings.showCheckBubble,onCheckedChange={value -> repository.updateSettings {it.copy(showCheckBubble=value)}},
                        modifier=Modifier.semantics {contentDescription="显示检查气泡"})
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许云端分析", Modifier.weight(1f))
                    Switch(checked = settings.cloudEnabled, modifier = Modifier.semantics { contentDescription = "允许云端分析" }, onCheckedChange = { if (it) allowCloud() else repository.updateSettings { s -> s.copy(cloudEnabled = false) } })
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
                        repository.configureAi(provider,model.trim(),key.trim())
                        withContext(Dispatchers.Main) {
                            key = ""; onKeyPresenceChanged(repository.vault.get("jevKey").isNotBlank())
                            focusManager.clearFocus(); keyboard?.hide()
                        }
                        testModelConnection(repository)
                    } }, enabled = !busy) { Text("保存并测试") }
                    TextButton(onClick = { task {
                        repository.clearAiCredentials()
                        withContext(Dispatchers.Main) { key = ""; onKeyPresenceChanged(false) }; "已清除 Jev Key"
                    } }, enabled = !busy) { Text("清除 Jev Key") }
                }
                Text(connection.message, style = MaterialTheme.typography.bodyMedium,
                    color = if (connection.state == ConnectionState.FAILED) Color(0xFF9C3B20) else Pine)
                Text("连接测试会发送一条固定测试请求，不读取其他应用的内容。", style = MaterialTheme.typography.bodySmall)
            } }
            item { SectionCard("通用视觉学习模型") {
                Text("选择图片识别和点击结果检查的服务。DeepSeek 官方直连独立使用官方 Key；Jev 配置保留，便于切换对照。")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    VisionProvider.entries.forEach {option -> FilterChip(selected=visionProvider==option,enabled=!busy,
                        onClick={visionProvider=option;visionModel=option.defaultModel},label={Text(option.displayName)})}
                }
                Text("当前使用：${settings.visionProvider.displayName} · ${settings.visionModel}",style=MaterialTheme.typography.bodySmall)
                Text("待识别图片最多缓存 8 份，按 24 小时过期清理；识别和点击截图随方案保存在本机，可放大查看和调整位置。",style=MaterialTheme.typography.bodySmall)
                if(visionProvider==VisionProvider.DEEPSEEK_OFFICIAL) {
                    Text("官方接口：api.deepseek.com；填写你从 DeepSeek 官方获取的 Key。",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=officialKey,onValueChange={officialKey=it},singleLine=true,
                        label={Text(if(repository.vault.contains("deepseekKey")) "DeepSeek 官方 Key 已保存，留空保持" else "DeepSeek 官方 API Key")},
                        visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                        modifier=Modifier.fillMaxWidth().focusRequester(visionKeyFocusRequester))
                } else Text(if(visionProvider==VisionProvider.JEV_OFFICIAL) "使用上方 TypeSafe 直连的 Jev Key，模型 jev-latest 跟随官方最新版本。" else "沿用上方已保存的 Vercel 网关 Key。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=visionModel,onValueChange={visionModel=it},label={Text("视觉模型")},
                    readOnly=visionProvider!=VisionProvider.VERCEL_AI_GATEWAY,modifier=Modifier.fillMaxWidth(),singleLine=true)
                Button(onClick={ task {
                    repository.configureVision(visionProvider,visionModel.trim(),officialKey.trim())
                    withContext(Dispatchers.Main) {officialKey="";focusManager.clearFocus();keyboard?.hide()}
                    testVisionConnection(repository)
                } },enabled=!busy) { Text("保存并测试图片") }
                if(visionProvider==VisionProvider.DEEPSEEK_OFFICIAL) TextButton(onClick={task {
                    repository.clearDeepseekKey();withContext(Dispatchers.Main) {officialKey=""};"已清除 DeepSeek 官方 Key，Jev 配置保留"
                }},enabled=!busy) {Text("清除 DeepSeek Key")}
                Text(visionConnection.message,style=MaterialTheme.typography.bodyMedium)
                Text("连接测试只发送固定测试图片，不读取其他应用内容。",style=MaterialTheme.typography.bodySmall)
            } }
            item { EnhancedTouchCard() }
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
            item { Text("TiaoTiao 0.3.10 · 智能视觉试用需要 Android 11 及以上\n服务被系统关闭后，需在系统设置重新开启。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
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
                QingIcon(QingGlyph.CHEVRON_RIGHT,size=20.dp,tint=Pine)
            }
        }
    }
}
