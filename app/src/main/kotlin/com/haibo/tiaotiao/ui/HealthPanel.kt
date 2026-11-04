package com.haibo.tiaotiao.ui

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.data.*
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.touch.TouchConnection
import kotlinx.coroutines.delay

private fun accessibilityListed(context: Context): Boolean {
    val resolver=context.contentResolver
    if(runCatching {Settings.Secure.getInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)}.getOrDefault(0)!=1) return false
    val me=ComponentName(context,SkipAccessibilityService::class.java)
    return Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
        .any {ComponentName.unflattenFromString(it)==me}
}

/** Re-reads system state every 1.5 s while the dashboard is in front; app state comes from its own flows. */
@Composable internal fun rememberHealthIssues(missingKey: String?): List<HealthIssue> {
    val context=LocalContext.current
    val app=context.applicationContext as TiaoApplication
    val repository=app.repository
    val settings by repository.settings.collectAsState()
    val connected by repository.connected.collectAsState()
    val jumps by repository.adJumps.items.collectAsState()
    val vision by repository.visionConnection.collectAsState()
    val touch by app.enhancedTouch.state.collectAsState()
    val wireless by app.wirelessBootstrap.state.collectAsState()
    var system by remember {mutableStateOf(Triple(true,true,false))}
    var tick by remember {mutableLongStateOf(0L)}
    var resumedAt by remember {mutableLongStateOf(SystemClock.elapsedRealtime())}
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            resumedAt=SystemClock.elapsedRealtime()
            while(true) {
                system=Triple(accessibilityListed(context),
                    runCatching {Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1}.getOrDefault(false),
                    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
                tick=SystemClock.elapsedRealtime()
                delay(1500)
            }
        }
    }
    val labels=remember {mutableStateMapOf<String,String>()}
    LaunchedEffect(jumps) {jumps.filter {it.unread}.forEach {labels[it.packageName]=app.appLabels.load(it.packageName)}}
    val service=SkipAccessibilityService.instance
    val bound=connected && service!=null
    // The dashboard's own window changes produce events; none for 5 s after it came to front means a stalled service.
    val silent=bound && tick-resumedAt>5000 && (service?.lastEventAt ?: 0L)<resumedAt
    val (listed,wirelessOn,batteryLimited)=system
    val capture=service?.captureFailure?.takeIf {tick-it.first<10*60_000}?.second
    return HealthCheck.evaluate(HealthSnapshot(
        sdk=android.os.Build.VERSION.SDK_INT,accessibilityEnabled=listed,serviceBound=bound,serviceSilent=silent,
        autoSkip=settings.enabled,selectedApps=settings.packages.size,automaticLearning=settings.automaticLearning,
        cloudEnabled=settings.cloudEnabled,missingKey=missingKey,
        blockingAiError=vision.message.takeIf {vision.blocksRequests()},
        enhancedTouch=settings.enhancedTouch,enhancedReady=touch==TouchConnection.READY && app.enhancedTouch.ownedReady,
        enhancedPaired=wireless.paired,wirelessDebugging=wirelessOn,
        // Only worth raising when the service has actually been lost; otherwise most phones would always show it.
        batteryRestricted=batteryLimited && (!listed || !bound || silent),
        captureFailure=capture,
        adJumps=jumps.filter {it.unread}.map {it.packageName to (labels[it.packageName] ?: it.packageName)}))
}

private val Amber=Color(0xFF5A3B12)
private fun HealthLevel.color()=when(this) {
    HealthLevel.CRITICAL -> Color(0xFFC0392B);HealthLevel.WARNING -> Color(0xFFD9822B);HealthLevel.INFO -> Color(0xFF8A8F98)
}

/** One compact yellow line on the first screen; tapping it lists every issue with its fix. */
@Composable internal fun HealthBar(issues: List<HealthIssue>,onAction: (HealthIssue) -> Unit,adJump: @Composable (String) -> Unit) {
    if(issues.isEmpty()) return
    var expanded by rememberSaveable {mutableStateOf(false)}
    var openJump by rememberSaveable {mutableStateOf<String?>(null)}
    val critical=issues.any {it.level==HealthLevel.CRITICAL}
    Card(Modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF1DC)),
        border=BorderStroke(1.dp,if(critical) Color(0xFFE29A8F) else Color(0xFFE9C58F))) {
        Row(Modifier.fillMaxWidth().clickable {expanded=!expanded}.padding(horizontal=16.dp,vertical=12.dp)
            .semantics {contentDescription=if(expanded) "收起状态提醒" else "展开 ${issues.size} 项状态提醒"},
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(issues.first().level.color(),CircleShape))
            Column(Modifier.weight(1f)) {
                Text(if(critical) "检测已停止 · ${issues.size} 项需要处理" else "${issues.size} 项需要处理",
                    style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=if(critical) Color(0xFF8E2A1F) else Amber)
                if(!expanded) Text(issues.joinToString("、") {it.title},style=MaterialTheme.typography.bodySmall,color=Amber,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            QingIcon(QingGlyph.CHEVRON_DOWN,Modifier.rotate(if(expanded) 180f else 0f),size=20.dp,tint=Amber)
        }
        if(expanded) Column(Modifier.padding(start=16.dp,end=16.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            issues.forEach {issue ->
                HorizontalDivider(color=Color(0xFFF0D9B5))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top=6.dp).size(8.dp).background(issue.level.color(),CircleShape))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(issue.title,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,color=Amber)
                        Text(issue.detail,style=MaterialTheme.typography.bodySmall,color=Amber)
                        if(issue.action!=null) OutlinedButton(onClick={
                            if(issue.action==HealthAction.AD_JUMP) openJump=if(openJump==issue.packageName) null else issue.packageName
                            else onAction(issue)
                        },contentPadding=PaddingValues(horizontal=14.dp,vertical=4.dp)) {
                            Text(if(issue.action==HealthAction.AD_JUMP && openJump==issue.packageName) "收起" else issue.actionLabel)
                        }
                    }
                }
                if(issue.action==HealthAction.AD_JUMP && openJump==issue.packageName) adJump(issue.packageName)
            }
        }
    }
}
