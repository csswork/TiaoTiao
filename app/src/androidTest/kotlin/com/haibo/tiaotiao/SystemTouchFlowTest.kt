package com.haibo.tiaotiao

import android.accessibilityservice.GestureDescription
import android.app.UiAutomation
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.DisplayCoordinates
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.service.VisualCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemTouchFlowTest {
    @Test fun sameCoordinatesReachTwoAdsThatRejectAccessibilityEventsAndRejectStaleInput()=runBlocking {
        assumeTrue("仅限独立模拟器",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as TiaoApplication
        val repository=app.repository
        val original=repository.settings.value
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String=automation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()}
        fun await(label: String,test: () -> Boolean) {
            val deadline=SystemClock.elapsedRealtime()+8000
            while(SystemClock.elapsedRealtime()<deadline) {if(test()) return;Thread.sleep(80)}
            fail(label)
        }
        repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}
        shell("settings put secure enabled_accessibility_services ''")
        await("服务未停止") {SkipAccessibilityService.instance==null}
        shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        await("服务未连接") {SkipAccessibilityService.instance!=null}
        val service=requireNotNull(SkipAccessibilityService.instance)
        val capture=VisualCapture(service)
        try {
            for(pkg in listOf("com.haibo.tiaotiao.fixture.alpha","com.haibo.tiaotiao.fixture.beta")) {
                shell("am force-stop $pkg")
                shell("run-as $pkg rm -f files/clicks files/touch_events")
                shell("am start -W -n $pkg/com.haibo.tiaotiao.fixture.FixtureActivity --ez canvas true --ez record_touch true --ez reject_a11y_touch true --ei ad_seconds 30")
                val size=DisplayCoordinates.size(service)
                await("目标应用尚未取得焦点") {shell("dumpsys window displays").contains(" $pkg/")}
                Thread.sleep(500)
                val snapshot=ScreenSnapshot(pkg,1,size.x,size.y,emptyList(),0,0)
                val area=Area(.86f,.04f,.94f,.1f)
                val frame=capture.capture(snapshot)
                try {
                    val stable=capture.targetUnchanged(frame,area)
                    assertTrue("稳定的实际控件应通过点击前截图校验：${capture.lastTargetCheck}",stable)
                    val x=(size.x*.9f).toInt();val y=(size.y*.07f).toInt()
                    val done=CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        val path=Path().apply {moveTo(x.toFloat(),y.toFloat())}
                        assertTrue(service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,100)).build(),
                            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(description: GestureDescription) {done.countDown()}
                                override fun onCancelled(description: GestureDescription) {done.countDown()}
                            },null))
                    }
                    assertTrue(done.await(2,TimeUnit.SECONDS))
                    assertFalse("模拟 SDK 应拒绝普通无障碍事件",shell("run-as $pkg cat files/clicks")=="1")
                    val probe="env CLASSPATH=${context.applicationInfo.sourceDir} app_process / com.haibo.tiaotiao.touch.TouchShellProbe"
                    assertEquals("旋转或旧显示尺寸必须取消", "-1",shell("$probe $pkg $x $y ${size.y} ${size.x}"))
                    assertEquals("同一服务代码在 shell 身份下应完成触控","1",shell("$probe $pkg $x $y ${size.x} ${size.y}"))
                    await("系统触控未关闭广告") {shell("run-as $pkg cat files/clicks")=="1"}
                    val events=shell("run-as $pkg cat files/touch_events").lineSequence().map {it.split(',')}.filter {it.size==4}.toList()
                    assertTrue("必须实际记录到带无障碍标记的事件",events.any {it[3].toInt() and 0x800!=0})
                    assertTrue("必须实际记录到相同像素位置的系统事件",events.any {
                        it[0]=="1" && it[3].toInt() and 0x800==0 && kotlin.math.abs(it[1].toFloat()-x)<1 && kotlin.math.abs(it[2].toFloat()-y)<1
                    })
                    assertFalse("控件消失后不得继续使用原点",capture.targetUnchanged(frame,area))
                    shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
                    assertEquals("前台已离开时禁止注入","-1",shell("$probe $pkg $x $y ${size.x} ${size.y}"))
                    assertEquals("守卫拒绝不能重复触控","1",shell("run-as $pkg cat files/clicks"))
                } finally {frame.close()}
            }
        } finally {capture.close();repository.updateSettings {original}}
    }
}
