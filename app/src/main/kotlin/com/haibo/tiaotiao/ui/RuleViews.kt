package com.haibo.tiaotiao.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.TiaoApplication
import kotlin.math.roundToInt

internal enum class RuleFilter(val label: String, val title: String) {
    ALL("全部", "规则库"), VERIFIED("已学会", "已学会规则"),
    PENDING("学习中", "学习中规则"), PAUSED("已停用", "已停用规则")
}

internal data class RuleItem(val ordinary: SkipRule? = null, val visual: VisualRule? = null,
    val variants: List<VisualRule> = listOfNotNull(visual), val taskId: String? = null) {
    val key get() = ordinary?.let { "ordinary-${it.id}" } ?: "visual-${taskId ?: requireNotNull(visual).id}"
    val packageName get() = ordinary?.packageName ?: requireNotNull(visual).packageName
    val target get() = ordinary?.target?.bounds ?: requireNotNull(visual).target
    val createdAt get() = ordinary?.createdAt ?: requireNotNull(visual).createdAt
    val verified = ordinary?.isVerified() ?: requireNotNull(visual).active()
    val pending = ordinary?.let { it.status in setOf(RuleStatus.PENDING,RuleStatus.IMPORTED,RuleStatus.TRIALLED) }
        ?: (!requireNotNull(visual).active() && !VisualPolicy.userPaused(visual))
    val paused = ordinary?.let { it.status == RuleStatus.DISABLED } ?: VisualPolicy.userPaused(requireNotNull(visual))
    val status = when {
        verified -> "已验证"
        paused -> "已停用"
        visual?.state == VisualRuleState.PAUSED -> "等待自动重学"
        visual?.awaitingLocalReview == true -> "等待重新识别"
        visual != null -> "自动学习中"
        ordinary?.status == RuleStatus.TRIALLED -> "待确认结果"
        else -> "待试跳"
    }
    val action get() = if (visual?.label == "close_ad") "关闭广告" else "跳过广告"
    fun matches(filter: RuleFilter) = when(filter) {
        RuleFilter.ALL -> true; RuleFilter.VERIFIED -> verified; RuleFilter.PENDING -> pending; RuleFilter.PAUSED -> paused
    }
}

@Composable internal fun appName(packageName: String): String {
    val context=LocalContext.current
    val store=(context.applicationContext as TiaoApplication).appLabels
    val name by produceState(store.cached(packageName) ?: packageName,packageName,store) {value=store.load(packageName)}
    return name
}

private fun position(area: Area): String {
    val x=(area.left+area.right)/2;val y=(area.top+area.bottom)/2
    val vertical=when { y<.34f -> "上";y>.66f -> "下";else -> "中" }
    val horizontal=when { x<.34f -> "左";x>.66f -> "右";else -> "中" }
    return if (horizontal=="中" && vertical=="中") "屏幕中央" else "屏幕${horizontal}${vertical}部"
}

@Composable internal fun RuleSummary(item: RuleItem, onClick: () -> Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            AdRuleThumbnail(item)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(appName(item.packageName),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(item.status,color=if(item.verified) Color(0xFF276751) else Color(0xFF776143),style=MaterialTheme.typography.labelLarge)
                Text("${item.action} · ${position(item.target)}",style=MaterialTheme.typography.bodyMedium)
                if(item.variants.size>1) Text("已归集 ${item.variants.size} 个识别样本",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("查看点击位置与处理进度",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    QingIcon(QingGlyph.CHEVRON_RIGHT,size=20.dp,tint=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable internal fun RuleListPage(entries: List<RuleItem>, filter: RuleFilter, onFilter: (RuleFilter) -> Unit,
    onSelect: (RuleItem) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onShare: () -> Unit, busy: Boolean) {
    val filtered=remember(entries,filter) {entries.filter { it.matches(filter) }.sortedByDescending { it.createdAt }}
    val counts=remember(entries) {RuleFilter.entries.associateWith {option -> entries.count {it.matches(option)}}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=4.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                RuleFilter.entries.forEach { option ->
                    FilterChip(selected=filter==option,onClick={ onFilter(option) },label={ Text("${option.label} ${counts[option]}") })
                }
            }
        }
        if (filtered.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(when(filter) {
                    RuleFilter.VERIFIED -> "还没有学会的规则";RuleFilter.PENDING -> "暂无学习中的规则"
                    RuleFilter.PAUSED -> "暂无已停用规则";RuleFilter.ALL -> "规则库还是空的"
                },style=MaterialTheme.typography.titleMedium)
                Text(if(filter==RuleFilter.ALL || filter==RuleFilter.VERIFIED) "选好应用后，学习和验证结果会出现在这里。" else "新的处理进展会自动更新到这里。",style=MaterialTheme.typography.bodyMedium,color=Color.Gray)
            }
        }
        items(filtered,key={it.key},contentType={"rule"}) { item -> RuleSummary(item,{ onSelect(item) }) }
        item {
            HorizontalDivider(Modifier.padding(top=8.dp,bottom=12.dp))
            Text("备份与分享",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("导出和分享仅包含已验证方案。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onImport,enabled=!busy) { Text("导入规则") }
                OutlinedButton(onClick=onExport,enabled=!busy) { Text("导出") }
                OutlinedButton(onClick=onShare,enabled=!busy) { Text("分享") }
            }
        }
    }
}

@Composable internal fun RuleDetailPage(task: RuleItem, busy: Boolean, onOpen: () -> Unit,
    onConfirm: (Boolean) -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onRelearn: () -> Unit, onDelete: () -> Unit,
    onAdjust: (VisualRule,com.haibo.tiaotiao.data.VisualPreview,Float,Float)->Unit,onConfirmVisual: (VisualRule)->Unit) {
    var variantId by remember(task.key) { mutableStateOf<String?>(null) }
    val repository=(LocalContext.current.applicationContext as TiaoApplication).repository
    val previews by repository.previews.entries.collectAsState()
    val available=previews.filter {p -> task.variants.any {it.id==p.ruleId}}
    val preferred=task.variants.find {it.id==available.maxByOrNull {it.at}?.ruleId} ?: task.visual
    val item=task.copy(visual=task.variants.find {it.id==variantId} ?: preferred)
    var confirmDelete by remember(item.key) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            DetailSection {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(appName(item.packageName),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                        Text(if(item.visual?.manuallyConfirmed()==true) "你已确认的跳过方案" else if(item.visual!=null) "智能跳过方案" else "普通跳过方案",style=MaterialTheme.typography.bodyMedium)
                    }
                    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp)) {
                        Text(item.status,Modifier.padding(horizontal=12.dp,vertical=8.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                    }
                }
                if(item.visual!=null && !item.verified) Button(onClick={onConfirmVisual(item.visual)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                    Text("确认方案正确")
                }
            }
        }
        item {
            DetailSection {
                Text("点击位置预览",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                if(item.variants.size>1) {
                    Text("同一关闭目标的 ${item.variants.size} 个样本；每个样本单独检查，不叠加成功次数。",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        item.variants.forEachIndexed {index,rule ->
                            FilterChip(selected=rule.id==item.visual?.id,onClick={variantId=rule.id},label={Text(if(rule.manuallyConfirmed()) "样本 ${index+1} · 已确认" else "样本 ${index+1} · ${rule.successfulSessions()}/2")})
                        }
                    }
                }
                if(item.visual!=null) SavedAdPreviews(item.visual,busy,onAdjust,available)
                else Text("这个旧方案暂无实际广告截图。",style=MaterialTheme.typography.bodySmall)
                Text("${item.action} · ${position(item.target)}",fontWeight=FontWeight.Medium)
                Text("点击中心：距左侧 ${(((item.target.left+item.target.right)/2)*100).roundToInt()}%，距顶部 ${(((item.target.top+item.target.bottom)/2)*100).roundToInt()}%",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
            }
        }
        item {
            DetailSection {
                Text("处理进度",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                ProgressStep("已保存目标位置",true)
                if (item.visual!=null) {
                    val n=item.visual.successfulSessions()
                    ProgressStep(if(item.visual.manuallyConfirmed()) "你已确认方案正确" else if(item.visual.awaitingLocalReview) "等待本机 AI 重新识别" else "自动检查已通过 $n 次，至少需要 2 次",n>=2 || item.visual.manuallyConfirmed())
                    Text(item.visual.lastResult,style=MaterialTheme.typography.bodyMedium)
                } else ProgressStep(when(item.ordinary?.status) {
                    RuleStatus.TRIALLED -> "试跳已发起，等待确认结果"
                    RuleStatus.IMPORTED -> "已导入，等待本机试跳"
                    RuleStatus.VERIFIED -> "已完成人工确认"
                    RuleStatus.DISABLED -> "方案已停用"
                    else -> "等待试跳"
                },item.verified)
                ProgressStep(if(item.verified) "已启用，可在匹配界面时自动跳过" else "尚未启用自动复用",item.verified)
            }
        }
        item {
            DetailSection {
                Text("管理方案",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                val node=item.ordinary
                if (node?.status==RuleStatus.TRIALLED) {
                    Text("若之前已试跳，请确认实际结果；也可重新打开应用试跳。",style=MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={onConfirm(true)},enabled=!busy) { Text("确实跳过") }
                        OutlinedButton(onClick={onConfirm(false)},enabled=!busy) { Text("没有跳过") }
                    }
                    TextButton(onClick=onRetry,enabled=!busy) { Text("重新试跳") }
                }
                if (node?.status in setOf(RuleStatus.PENDING,RuleStatus.IMPORTED)) {
                    OutlinedButton(onClick=onOpen,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("打开应用试跳") }
                }
                if (item.visual!=null) Text("AI 会自动尝试和检查，你只需在发现异常时停用。",style=MaterialTheme.typography.bodyMedium)
                if (!item.paused) OutlinedButton(onClick=onPause,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(if(item.visual!=null) "点错了／停用方案" else "停用方案") }
                if (item.visual!=null && item.paused) OutlinedButton(onClick=onRelearn,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("重新学习") }
                TextButton(onClick={confirmDelete=true},enabled=!busy) { Text("删除方案") }
            }
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除这个方案？")},text={Text(if(item.variants.size>1) "将删除此学习任务及其 ${item.variants.size} 个样本。" else "删除后不会再使用此方案。")},
        confirmButton={TextButton(onClick={confirmDelete=false;onDelete()}) { Text("删除") }},dismissButton={TextButton(onClick={confirmDelete=false}) {Text("取消")}})
}

@Composable private fun DetailSection(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable private fun ProgressStep(label: String, complete: Boolean) {
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        QingIcon(if(complete) QingGlyph.CHECK_CIRCLE else QingGlyph.PENDING_CIRCLE,size=20.dp,tint=MaterialTheme.colorScheme.primary)
        Text(label,style=MaterialTheme.typography.bodyMedium)
    }
}
