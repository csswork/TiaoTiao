package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.RuleStatus
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.ui.DemoActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = (context.applicationContext as TiaoApplication).repository
    private lateinit var automation: UiAutomation

    @Test fun newRuleNeedsHumanConfirmationThenReusesOffline() {
        // This test changes accessibility settings and exercises only this app's synthetic screen.
        assumeTrue("仅在独立 Android 模拟器运行", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        openDemo() // Start the installed app before asking Android to bind its accessibility service.
        shell("settings put secure enabled_accessibility_services ''")
        shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        waitUntil { SkipAccessibilityService.instance != null && repository.connected.value }
        repository.rules.value.filter { it.packageName == context.packageName }.forEach { repository.remove(it.id) }
        repository.updateSettings { it.copy(enabled = true, cloudEnabled = false) }
        openDemo()
        waitUntil { repository.rules.value.any { it.source == "demo-local" && it.status == RuleStatus.PENDING } }
        val id = repository.rules.value.single { it.source == "demo-local" }.id
        shell("screencap -p /sdcard/Download/tiaotiao-proposal.png")
        assertFalse(repository.rules.value.single { it.id == id }.isVerified())
        click("试跳")
        waitUntil { repository.rules.value.single { it.id == id }.status == RuleStatus.TRIALLED }
        waitUntil { hasText("已进入演示首页") }
        shell("screencap -p /sdcard/Download/tiaotiao-confirmation.png")
        assertFalse(repository.rules.value.single { it.id == id }.isVerified())
        click("确实跳过")
        waitUntil { repository.rules.value.single { it.id == id }.isVerified() }
        val autoBefore = repository.logs.value.count { it.message == "已按验证规则发起跳过点击" }
        openDemo()
        waitUntil { repository.logs.value.count { it.message == "已按验证规则发起跳过点击" } == autoBefore + 1 }
        waitUntil { hasText("已进入演示首页") }
        assertTrue(hasText("跳过登录")) // Normal-page skip controls remain untouched.

        // Real application transitions must reopen detection without DemoActivity resetting the gate.
        repeat(3) { index ->
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
            waitUntil { automation.windows.none { it.root?.packageName?.toString() == context.packageName } }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, DemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitUntil { repository.logs.value.count { it.message == "已按验证规则发起跳过点击" } == autoBefore + 2 + index }
            waitUntil { hasText("已进入演示首页") }
        }

        // Shared verification history must not bypass local confirmation.
        val exported = repository.exportVerified()
        repository.remove(id)
        assertEquals(1, repository.importRules(exported))
        assertEquals(RuleStatus.IMPORTED, repository.rules.value.single { it.id == id }.status)
        openDemo()
        waitUntil { hasText("试跳") }
        assertEquals(autoBefore + 4, repository.logs.value.count { it.message == "已按验证规则发起跳过点击" })
        click("试跳")
        waitUntil { repository.rules.value.single { it.id == id }.status == RuleStatus.TRIALLED }
        click("确实跳过")
        waitUntil { repository.rules.value.single { it.id == id }.isVerified() }

        repository.updateSettings { it.copy(enabled = false) }
        openDemo()
        waitUntil { hasText("跳过 5") }
        Thread.sleep(800)
        assertTrue("暂停后不得自动跳过", hasText("跳过 5"))
        assertFalse(repository.settings.value.cloudEnabled)
    }
    private fun openDemo() {
        if (hasText("再看一次模拟开屏")) { click("再看一次模拟开屏"); return }
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, DemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    private fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun nodes(): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }
    private fun find(text: String): AccessibilityNodeInfo? {
        for (root in nodes()) {
            val found = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text && it.isVisibleToUser }
            if (found != null) return found
        }
        return null
    }
    private fun hasText(text: String) = find(text) != null
    private fun click(text: String) {
        var found: AccessibilityNodeInfo? = null
        waitUntil { found = find(text); found != null }
        var node = requireNotNull(found)
        repeat(4) {
            if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            node = node.parent ?: error("找不到可点击父控件：$text")
        }
        error("控件不可点击：$text")
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(80)
        }
        shell("screencap -p /sdcard/Download/tiaotiao-test-failure.png")
        fail("等待状态超时；窗口=${automation.windows.map { it.type }}；服务连接=${repository.connected.value}，本机服务=${SkipAccessibilityService.instance != null}，最近日志=${repository.logs.value.take(4)}")
    }
}
