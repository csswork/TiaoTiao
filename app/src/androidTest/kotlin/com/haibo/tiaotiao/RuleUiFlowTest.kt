package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.haibo.tiaotiao.service.SkipAccessibilityService

@RunWith(AndroidJUnit4::class)
class RuleUiFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=(context.applicationContext as TiaoApplication).repository
    private lateinit var automation: UiAutomation

    @Test fun dashboardScrollingDoesNotReadItsOwnAccessibilityTree() {
        assumeTrue("仅在独立模拟器运行",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {automation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        repository.updateSettings {it.copy(enabled=true,cloudEnabled=false,packages=emptySet())}
        open()
        shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        try {
            waitUntil {SkipAccessibilityService.instance!=null && repository.connected.value &&
                (context.applicationContext as TiaoApplication).foregroundScreen==TiaoApplication.ForegroundScreen.DASHBOARD}
            Thread.sleep(500)
            val service=requireNotNull(SkipAccessibilityService.instance)
            val before=service.diagnosticTreeReads
            repeat(2) {shell("input swipe 540 1750 540 650 400");shell("input swipe 540 650 540 1750 400")}
            Thread.sleep(500)
            assertTrue(repository.settings.value.enabled)
            assertEquals("管理页滚动不应触发识别服务遍历界面树",before,service.diagnosticTreeReads)
        } finally {repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}}
    }

    @Test fun statisticsOpenFilteredListsAndPreviewNeverDispatchesAnAction() {
        assumeTrue("仅在独立模拟器运行",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        repository.updateSettings {it.copy(enabled=false,cloudEnabled=false,packages=setOf("com.android.settings"))}
        repository.rules.value.forEach {repository.remove(it.id)}
        repository.visualRules.value.forEach {repository.removeVisual(it.id)}
        val now=System.currentTimeMillis()
        val area=Area(.8f,.05f,.96f,.1f)
        val ordinary=SkipRule(packageName=context.packageName,versionCode=1,screenFingerprint="a".repeat(64),
            target=TargetSelector("0.1","","android.view.View","跳过",area),source="ui-fixture",createdAt=now)
        repository.put(RuleEngine.confirm(RuleEngine.recordTrial(ordinary,true,now),true,now))
        repository.put(ordinary.copy(id="ui-pending",packageName="com.android.settings",createdAt=now-1))
        val visual=VisualRule(id="ui-visual",packageName="com.android.settings",versionCode=1,scene="b".repeat(64),
            target=area,targetHash="01".repeat(32),label="skip",source="ui-fixture",createdAt=now-2)
        repository.putVisual(VisualPolicy.record(VisualPolicy.record(visual,"one",true,true,true,now),"two",true,true,true,now))
        repository.putVisual(visual.copy(id="ui-visual-pending",createdAt=now-3))
        repository.vault.set("jevKey","ui-test-not-a-real-key")
        try {
            open()
            waitUntil {find("查看已验证列表")!=null}
            assertNull("配置完成后 API 卡片不应占据首屏",find("AI 服务已配置"))
            screenshot("ui-home")
            scrollTo("AI 服务已配置")
            screenshot("ui-api-footer")
            open()
            waitUntil {find("查看已验证列表")!=null}
            click("查看已验证列表")
            waitUntil {find("已验证规则")!=null}
            assertNotNull(find("已验证 2"))
            assertNull(find("待试跳"))
            screenshot("ui-verified")
            click("查看点击位置与处理进度 ›")
            waitUntil {find("点击位置预览")!=null}
            assertNotNull(find("点击位置模拟图：屏幕右上部"))
            assertNull(find("查看 JSON"))
            screenshot("ui-rule-preview")
            scrollTo("播放点击演示")
            val before=repository.logs.value.size
            click("播放点击演示")
            screenshot("ui-rule-tap")
            Thread.sleep(1300)
            assertEquals("预览不得发起实际点击或模型请求",before,repository.logs.value.size)
            assertFalse(repository.settings.value.enabled)
            back()
            waitUntil {find("已验证规则")!=null}
            back()
            waitUntil {find("查看待处理列表")!=null}
            click("查看待处理列表")
            waitUntil {find("待处理规则")!=null}
            assertNotNull(find("待处理 2"))
            assertNotNull(find("待试跳"))
            assertNotNull(find("自动学习中"))
            screenshot("ui-pending")
            back()
            waitUntil {find("查看应用列表")!=null}
            click("查看应用列表")
            waitUntil {find("已选择的应用")!=null && find("处理 Settings")!=null}
            screenshot("ui-selected-apps")
            repository.vault.set("jevKey","")
            open()
            waitUntil {find("填写 API Key")!=null}
            screenshot("ui-missing-key")
        } finally {
            repository.vault.set("jevKey","")
            repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}
        }
    }

    private fun open()=instrumentation.runOnMainSync {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        val root=automation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(text)==true || node.contentDescription?.toString()?.lines()?.contains(text)==true)) return node
            for(i in 0 until node.childCount) { node.getChild(i)?.let {visit(it)?.let {found -> return found}} }
            return null
        }
        return visit(root)
    }
    private fun click(text: String) {
        waitUntil {find(text)!=null}
        var node=requireNotNull(find(text))
        repeat(6) {
            if(node.isClickable) {assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return}
            node=node.parent ?: error("控件不可点击：$text")
        }
        error("控件不可点击：$text")
    }
    private fun scrollTo(text: String) {
        repeat(8) {
            if(find(text)!=null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for(i in 0 until node.childCount) if(node.getChild(i)?.let(::scroll)==true) return true
                return false
            }
            automation.rootInActiveWindow?.let(::scroll)
            Thread.sleep(250)
        }
        assertNotNull("找不到 $text",find(text))
    }
    private fun back() {automation.executeShellCommand("input keyevent KEYCODE_BACK").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun screenshot(name: String) {
        Thread.sleep(350) // Accessibility can see the destination before Android's enter animation finishes.
        runCatching {automation.waitForIdle(100,1500)}
        automation.takeScreenshot()?.let { bitmap ->
            val dir=File(context.filesDir,"ui-verification").apply {mkdirs()}
            File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<deadline) {if(predicate()) return;Thread.sleep(100)}
        screenshot("ui-failure")
        fail("等待界面状态超时")
    }
}
