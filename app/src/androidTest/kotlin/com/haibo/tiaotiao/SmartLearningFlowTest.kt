package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.ui.SmartDemoActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartLearningFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = (context.applicationContext as TiaoApplication).repository
    private lateinit var automation: UiAutomation
    @Test fun canvasAdvertisementLearnsAndValidatesWithoutHumanOrCloud() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        open()
        shell("settings put secure enabled_accessibility_services ''")
        waitUntil { SkipAccessibilityService.instance==null && !repository.connected.value }
        shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        waitUntil { SkipAccessibilityService.instance!=null && repository.connected.value }
        repository.clearLogs()
        repository.visualRules.value.filter { it.packageName==context.packageName }.forEach { repository.removeVisual(it.id) }
        repository.updateSettings { it.copy(enabled=true,cloudEnabled=false) }
        open()
        waitUntil { repository.visualRules.value.any { it.source=="bundled-ocr-demo" && it.successfulSessions()==1 } }
        val first=repository.visualRules.value.single { it.source=="bundled-ocr-demo" }
        assertFalse(first.active())
        repeat(2) { index ->
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
            Thread.sleep(600)
            open()
            waitUntil { repository.visualRules.value.find { it.id==first.id }?.successfulSessions()==index+2 }
        }
        assertTrue(repository.visualRules.value.single { it.id==first.id }.active())
        val imported=VisualPolicy.import(repository.exportVisual()).single { it.id==first.id }
        assertEquals(VisualRuleState.CANDIDATE,imported.state)
        assertTrue(imported.evidence.isEmpty())
        assertFalse(repository.settings.value.cloudEnabled)
        repository.updateSettings { it.copy(enabled=false) }
        open();Thread.sleep(1200)
        assertTrue(automation.windows.any { it.root?.findAccessibilityNodeInfosByViewId(context.packageName+":id/smart_demo_ad")?.isNotEmpty()==true })
    }
    private fun open() = instrumentation.runOnMainSync {
        context.startActivity(Intent(context,SmartDemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+20_000
        while (android.os.SystemClock.elapsedRealtime()<deadline) { if (predicate()) return;Thread.sleep(100) }
        fail("智能视觉等待超时；规则=${repository.visualRules.value}；日志=${repository.logs.value.take(5)}")
    }
}
