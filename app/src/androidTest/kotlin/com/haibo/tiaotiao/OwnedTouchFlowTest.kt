package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.service.DisplayCoordinates
import com.haibo.tiaotiao.touch.*
import com.haibo.tiaotiao.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@android.annotation.SuppressLint("NewApi")
class OwnedTouchFlowTest {
    @Test fun appOwnedBinderWorksWithoutAManagerAppAndOnlyAcceptsShellHandoff()=runBlocking {
        assumeTrue(Build.VERSION.SDK_INT>=31 && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as TiaoApplication
        val original=app.repository.settings.value
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String)=automation.executeShellCommand(command).use {ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()}
        fun await(label: String,test: () -> Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10_000
            while(SystemClock.elapsedRealtime()<deadline) {if(test()) return;Thread.sleep(80)}
            fail(label+" "+shell("cat /data/local/tmp/tiaotiao-owned-test.log"))
        }
        fun settings()=instrumentation.runOnMainSync {context.startActivity(Intent(context,MainActivity::class.java)
            .putExtra("configure_touch",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
        fun textVisible(text: String): Boolean {
            fun find(node: AccessibilityNodeInfo?): Boolean {
                if(node==null) return false
                if(node.text?.toString()==text) return true
                for(i in 0 until node.childCount) if(find(node.getChild(i))) return true
                return false
            }
            if(Build.VERSION.SDK_INT>=33) automation.clearCache()
            return find(automation.rootInActiveWindow)
        }
        app.repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}
        instrumentation.runOnMainSync {context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
        assertFalse("独立模拟器不能安装外部管理器",shell("pm path moe.shizuku.privileged.api").startsWith("package:"))
        app.enhancedTouch.stopOwned()
        val rejected=runCatching {context.contentResolver.call(Uri.parse("content://${context.packageName}.owned-touch"),"attach",null,Bundle())}.exceptionOrNull()
        assertTrue("普通应用身份不能自行交接系统 Binder",rejected is SecurityException)
        val path="'"+context.applicationInfo.sourceDir.replace("'","'\\''")+"'"
        val command="CLASSPATH=$path nohup setsid app_process /system/bin --nice-name=tiaotiao_touch com.haibo.tiaotiao.touch.OwnedTouchServer </dev/null >/data/local/tmp/tiaotiao-owned-test.log 2>&1 &\nexit\n"
        val pipes=automation.executeShellCommandRwe("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {it.write(command.toByteArray())}
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use {it.readBytes()}
        pipes[2].close()
        try {
            await("应用没有收到独立服务") {app.enhancedTouch.ownedReady}
            settings()
            await("实际连接后界面未显示已连接") {textVisible("增强点击服务已连接") && textVisible("停止增强点击")}
            val size=DisplayCoordinates.size(context)
            for(pkg in listOf("com.haibo.tiaotiao.fixture.alpha","com.haibo.tiaotiao.fixture.beta")) {
                shell("am force-stop $pkg");shell("run-as $pkg rm -f files/clicks files/touch_events")
                shell("am start -W -n $pkg/com.haibo.tiaotiao.fixture.FixtureActivity --ez canvas true --ez record_touch true --ez reject_a11y_touch true --ei ad_seconds 30")
                await("目标未获得焦点") {shell("dumpsys window displays").contains(" $pkg/")}
                Thread.sleep(350)
                val x=(size.x*.9f).toInt();val y=(size.y*.07f).toInt()
                assertEquals(TouchDispatch.COMPLETED,app.enhancedTouch.tap(pkg,x,y,size.x,size.y))
                assertEquals("系统输入接口",app.enhancedTouch.lastEngine)
                await("独立服务未完成广告点击") {shell("run-as $pkg cat files/clicks")=="1"}
                val events=shell("run-as $pkg cat files/touch_events").lineSequence().map {it.split(',')}.filter {it.size==4}.toList()
                assertTrue(events.any {it[0]=="1" && it[3].toInt() and 0x800==0 && kotlin.math.abs(it[1].toFloat()-x)<1 && kotlin.math.abs(it[2].toFloat()-y)<1})
                shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
                assertEquals("离开前台后不能使用旧点",TouchDispatch.FAILED,app.enhancedTouch.tap(pkg,x,y,size.x,size.y))
                assertEquals("1",shell("run-as $pkg cat files/clicks"))
            }
        } finally {
            app.enhancedTouch.stopOwned();app.repository.updateSettings {original}
            settings()
            await("停止服务后界面未恢复未启动状态") {textVisible("增强点击未启动")}
        }
    }
    @Test fun wirelessIdentityIsPersistentEncryptedAndIndependentOfAiCredentials() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=WirelessAdbKeys(context);val second=WirelessAdbKeys(context)
        assertArrayEquals(first.certificate.encoded,second.certificate.encoded)
        val sealed=context.getSharedPreferences("wireless_credentials",0).getString("identity",null)
        assertNotNull(sealed)
        assertFalse(requireNotNull(sealed).contains(android.util.Base64.encodeToString(first.privateKey.encoded,android.util.Base64.NO_WRAP)))
    }
}
