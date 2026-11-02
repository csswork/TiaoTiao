package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.Intent
import android.os.*
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.ui.MainActivity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** Opt-in diagnostic comparison, not a phone FPS guarantee or a hard timing assertion. */
@RunWith(AndroidJUnit4::class)
class ScrollPerformanceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=(context.applicationContext as TiaoApplication).repository
    private lateinit var automation: UiAutomation

    @Test fun measureScrollingWithAndWithoutDetection() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("显式传入 scrollPerf=true 才运行性能采样",args.getString("scrollPerf")=="true")
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        repository.updateSettings {it.copy(enabled=false,cloudEnabled=false,packages=emptySet())}
        repository.rules.value.forEach {repository.remove(it.id)}
        repository.visualRules.value.forEach {repository.removeVisual(it.id)}
        val now=System.currentTimeMillis()
        repeat(60) { index ->
            val rule=SkipRule(id="perf-$index",packageName=if(index%2==0) context.packageName else "com.android.settings",versionCode=1,
                screenFingerprint="a".repeat(64),target=TargetSelector("0.1","","android.view.View","跳过",Area(.8f,.05f,.96f,.1f)),
                source="perf-fixture",createdAt=now-index)
            repository.put(RuleEngine.confirm(RuleEngine.recordTrial(rule,true,now),true,now))
        }
        repeat(150) {repository.log("com.example.sample","滚动测试记录 $it")}
        repository.vault.set("jevKey","perf-test-not-a-real-key")
        val rows=mutableListOf<JsonObject>()
        try {
            instrumentation.runOnMainSync {context.startActivity(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
            waitUntil {find("首页")!=null}
            shell("settings put secure enabled_accessibility_services ''")
            waitUntil {SkipAccessibilityService.instance==null && !repository.connected.value}
            shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            waitUntil {SkipAccessibilityService.instance!=null && repository.connected.value}
            for(enabled in listOf(false,true)) {
                for(page in listOf("首页","规则","记录")) {
                    repository.updateSettings {it.copy(enabled=enabled)}
                    val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
                    instrumentation.runOnMainSync {context.startActivity(Intent(context,MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
                    val activity=requireNotNull(monitor.waitForActivityWithTimeout(5000))
                    instrumentation.removeMonitor(monitor)
                    instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                    waitUntil {find(page)!=null}
                    if(page!="首页") click(page)
                    Thread.sleep(1200)
                    assertTrue("每组必须确认服务实际连接",repository.connected.value && SkipAccessibilityService.instance!=null)
                    val thread=HandlerThread("scroll-frame-metrics").apply {start()}
                    val frames=Collections.synchronizedList(mutableListOf<Pair<Double,Double>>())
                    val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_ ->
                        if(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L) {
                            val deadline=if(Build.VERSION.SDK_INT>=31) metrics.getMetric(FrameMetrics.DEADLINE)/1_000_000.0 else 16.67
                            frames+=metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0 to deadline
                        }
                    }
                    instrumentation.runOnMainSync {activity.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))}
                    val logCount=repository.logs.value.size
                    val treesBefore=treeReads()
                    repeat(3) {
                        shell("input swipe 540 1750 540 650 450")
                        shell("input swipe 540 650 540 1750 450")
                    }
                    Thread.sleep(500)
                    instrumentation.runOnMainSync {activity.window.removeOnFrameMetricsAvailableListener(listener)}
                    thread.quitSafely();thread.join(2000)
                    val sample=synchronized(frames) {frames.toList()}
                    assertTrue("没有采集到滚动帧",sample.isNotEmpty())
                    val sorted=sample.map {it.first}.sorted()
                    fun percentile(p: Double)=sorted[((sorted.size-1)*p).toInt()]
                    rows+=buildJsonObject {
                        put("page",page);put("detectionEnabled",enabled);put("frames",sample.size)
                        put("serviceConnected",repository.connected.value)
                        put("p50Ms",percentile(.5));put("p95Ms",percentile(.95));put("p99Ms",percentile(.99))
                        put("overDeadline",sample.count {it.first>it.second});put("over50ms",sample.count {it.first>50})
                        put("logCountDelta",repository.logs.value.size-logCount)
                        val treesAfter=treeReads()
                        if(treesBefore!=null && treesAfter!=null) put("treeReadDelta",treesAfter-treesBefore)
                    }
                }
            }
        } finally {
            repository.vault.set("jevKey","")
            repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}
        }
        val name=args.getString("perfLabel")?.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))} ?: "sample"
        File(context.filesDir,"scroll-$name.json").writeText(JsonArray(rows).toString())
        println("SCROLL_METRICS ${JsonArray(rows)}")
    }
    private fun shell(command: String) {automation.executeShellCommand(command).use {ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun treeReads(): Long?=SkipAccessibilityService.instance?.let {service ->
        runCatching {service.javaClass.getMethod("getDiagnosticTreeReads").invoke(service) as Long}.getOrNull()
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(text)==true || node.contentDescription?.toString()?.lines()?.contains(text)==true)) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let {visit(it)?.let {found -> return found}}
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun click(text: String) {
        var node=requireNotNull(find(text))
        repeat(6) {if(node.isClickable) {assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return};node=node.parent ?: error(text)}
        error("控件不可点击：$text")
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<end) {if(predicate()) return;Thread.sleep(100)}
        fail("等待界面超时")
    }
}
