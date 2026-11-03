package com.haibo.tiaotiao

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WirelessSetupFlowTest {
    @Test fun manualKeypadKeepsSettingsFocusedAndCancelEndsTheSession() {
        assumeTrue(Build.VERSION.SDK_INT>=30 && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as TiaoApplication
        val original=app.repository.settings.value
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun shell(command: String)=automation.executeShellCommand(command).use {ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
        fun await(label: String,check: () -> Boolean) {
            val end=SystemClock.elapsedRealtime()+8000
            while(SystemClock.elapsedRealtime()<end) {if(check()) return;Thread.sleep(80)}
            fail(label+" phase="+app.wirelessBootstrap.state.value.phase+" assisting="+app.wirelessBootstrap.state.value.assisting+
                " windows="+automation.windows.map {"${it.type}/${it.isFocused}/${it.root?.packageName}"})
        }
        shell("settings put secure enabled_accessibility_services ''")
        await("旧无障碍服务未解绑") {SkipAccessibilityService.instance==null && !app.repository.connected.value}
        shell("settings put secure enabled_accessibility_services com.haibo.tiaotiao/com.haibo.tiaotiao.service.SkipAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        await("无障碍服务未连接") {SkipAccessibilityService.instance!=null && app.repository.connected.value}
        app.repository.updateSettings {it.copy(enabled=true,cloudEnabled=false,packages=setOf("com.android.settings"))}
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val beforeReads=SkipAccessibilityService.instance!!.diagnosticTreeReads
        instrumentation.runOnMainSync {
            assertTrue(app.wirelessBootstrap.beginAssistedPairing())
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun overlay()=automation.windows.firstOrNull {it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}
        fun visit(node: AccessibilityNodeInfo?,text: String): Rect? {
            if(node==null) return null
            if(node.text?.toString()==text) return Rect().also(node::getBoundsInScreen)
            for(i in 0 until node.childCount) visit(node.getChild(i),text)?.let {return it}
            return null
        }
        fun click(text: String) {
            val rect=visit(overlay()?.root,text) ?: error("缺少浮层按钮：$text")
            val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
                event.source=InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event,true));event.recycle()
                if(action==MotionEvent.ACTION_DOWN) Thread.sleep(50)
            }
        }
        try {
            await("配对辅助浮层未显示") {visit(overlay()?.root,"手动输入")!=null}
            click("手动输入")
            await("数字键盘未显示") {visit(overlay()?.root,"1")!=null}
            click("1");click("2");click("3")
            Thread.sleep(200)
            val windows=automation.windows
            val focused=windows.singleOrNull {it.isFocused}
            assertEquals("com.android.settings",focused?.root?.packageName?.toString())
            assertTrue(windows.none {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD})
            assertTrue("输入时辅助会话必须保持有效",app.wirelessBootstrap.state.value.assisting)
            assertEquals("配对辅助不能进入普通广告读取管线",beforeReads,SkipAccessibilityService.instance!!.diagnosticTreeReads)
            assertTrue(visit(overlay()?.root,"› 配对码：•••")!=null)
            click("取消")
            await("取消后浮层未清理") {!app.wirelessBootstrap.state.value.assisting && overlay()==null}
        } finally {
            instrumentation.runOnMainSync {app.wirelessBootstrap.cancelAssistance()}
            app.repository.updateSettings {original}
        }
    }
}
