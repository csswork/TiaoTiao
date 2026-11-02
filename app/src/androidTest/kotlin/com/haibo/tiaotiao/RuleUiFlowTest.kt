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
import com.haibo.tiaotiao.data.AppCategory
import com.haibo.tiaotiao.data.InstalledApps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.haibo.tiaotiao.data.AppCategoryCache
import com.haibo.tiaotiao.data.AppCategoryRecord

@RunWith(AndroidJUnit4::class)
class RuleUiFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=(context.applicationContext as TiaoApplication).repository
    private lateinit var automation: UiAutomation

    @Test fun applicationCategoriesCombineWithSelectedScopeAndSearch() {
        assumeTrue("仅在独立模拟器运行",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {automation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        val selected=setOf("com.android.settings")
        repository.updateSettings {it.copy(enabled=false,cloudEnabled=false,packages=selected)}
        val catalog=runBlocking {InstalledApps.load(context)}
        val settings=requireNotNull(catalog.find {it.packageName=="com.android.settings"}).copy(category=AppCategory.TOOLS)
        val another=AppCategory.PHOTO
        val config=repository.settings.value
        runBlocking(Dispatchers.IO) {
            AppCategoryCache(context.filesDir).save(catalog.map { app -> AppCategoryRecord(
                app.packageName,app.name.take(120),if(app.packageName==settings.packageName) AppCategory.TOOLS else another,
                1.0,config.jevProvider,config.model,System.currentTimeMillis()) })
        }
        val classifier=(context.applicationContext as TiaoApplication).appClassification
        classifier.refresh(retry=true)
        waitUntil {classifier.state.value.apps.any {it.packageName==settings.packageName && it.category==AppCategory.TOOLS}}
        open()
        scrollTo("查看应用列表")
        click("查看应用列表")
        waitUntil {find("已选择的应用")!=null && find("处理 ${settings.name}")!=null}
        click("类别：全部类别")
        waitUntil {find("按类别筛选")!=null}
        screenshot("ui-category-picker")
        scrollTo("类别选项：${settings.category.title}")
        click("类别选项：${settings.category.title}")
        waitUntil {find("类别：${settings.category.title}")!=null && find("处理 ${settings.name}")!=null}
        setSearch("com.android.settings")
        waitUntil {find("处理 ${settings.name}")!=null}
        screenshot("ui-category-search")
        setSearch("no_such_installed_application")
        waitUntil {find("没有匹配的应用")!=null}
        screenshot("ui-category-empty")
        assertEquals(selected,repository.settings.value.packages)
        click("清除筛选")
        waitUntil {find("类别：全部类别")!=null && find("处理 ${settings.name}")!=null}
        assertNotNull(find("已选择的应用"))
        click("类别：全部类别")
        scrollTo("类别选项：${another.title}")
        click("类别选项：${another.title}")
        waitUntil {find("没有匹配的应用")!=null}
        assertNull(find("处理 ${settings.name}"))
        click("全部")
        waitUntil {find("没有匹配的应用")==null && find("类别：${another.title}")!=null}
        screenshot("ui-category-results")
        assertEquals("筛选不能更改已选择应用",selected,repository.settings.value.packages)
        // Return to the same category after changing range, then toggle an app through the filtered list.
        click("重置")
        click("类别：全部类别")
        scrollTo("类别选项：${settings.category.title}")
        click("类别选项：${settings.category.title}")
        setSearch("com.android.settings")
        waitUntil {find("处理 ${settings.name}")!=null}
        click("处理 ${settings.name}")
        waitUntil {"com.android.settings" !in repository.settings.value.packages && find("处理 ${settings.name}")?.isChecked==false}
        click("处理 ${settings.name}")
        waitUntil {repository.settings.value.packages==selected}
    }

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
            exportBrandPreview()
            scrollTo("AI 服务已配置")
            screenshot("ui-api-footer")
            open()
            waitUntil {find("查看已验证列表")!=null}
            click("查看已验证列表")
            waitUntil {find("已验证规则")!=null}
            assertNotNull(find("已验证 2"))
            assertNull(find("待试跳"))
            screenshot("ui-verified")
            click("查看点击位置与处理进度")
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
    private fun setSearch(value: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable && node.isVisibleToUser) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let {editable(it)?.let {found -> return found}}
            return null
        }
        var input: AccessibilityNodeInfo?=null
        waitUntil {input=automation.rootInActiveWindow?.let(::editable);input!=null}
        val args=android.os.Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}
        assertTrue(requireNotNull(input).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))
        waitUntil {find(value)!=null || value.isEmpty()}
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
        repeat(12) {
            if(find(text)!=null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for(i in 0 until node.childCount) if(node.getChild(i)?.let(::scroll)==true) return true
                return false
            }
            automation.rootInActiveWindow?.let(::scroll)
            Thread.sleep(350)
            // Let the asynchronous Compose scroll finish before issuing another scroll.
            runCatching {automation.waitForIdle(100,1500)}
        }
        screenshot("ui-scroll-failure")
        assertNotNull("找不到 $text",find(text))
    }
    private fun back() {automation.executeShellCommand("input keyevent KEYCODE_BACK").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    /** Visual review artifacts use the packaged assets, including Android's actual launcher mask. */
    private fun exportBrandPreview() {
        val dir=File(context.filesDir,"ui-verification").apply {mkdirs()}
        fun save(bitmap: android.graphics.Bitmap,name: String) {
            File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        fun draw(canvas: android.graphics.Canvas,drawable: android.graphics.drawable.Drawable,x: Int,y: Int,size: Int) {
            drawable.setBounds(x,y,x+size,y+size)
            drawable.draw(canvas)
        }
        fun vector(id: Int)=requireNotNull(context.getDrawable(id)).mutate()
        fun tile(canvas: android.graphics.Canvas,x: Int,y: Int,size: Int) {
            val checkpoint=canvas.save()
            val corner=size*.25f
            canvas.clipPath(android.graphics.Path().apply {
                addRoundRect(x.toFloat(),y.toFloat(),(x+size).toFloat(),(y+size).toFloat(),corner,corner,android.graphics.Path.Direction.CW)
            })
            draw(canvas,vector(R.drawable.ic_launcher),x,y,size)
            canvas.restoreToCount(checkpoint)
        }
        val logo=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888)
        tile(android.graphics.Canvas(logo),0,0,512)
        save(logo,"tiaotiao-logo")
        val board=android.graphics.Bitmap.createBitmap(1120,640,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(board)
        canvas.drawColor(android.graphics.Color.rgb(247,248,243))
        val ink=android.graphics.Color.rgb(28,69,53)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=ink}
        tile(canvas,72,72,272)
        paint.typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        paint.textSize=76f
        canvas.drawText("TiaoTiao",408f,179f,paint)
        paint.typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
        paint.textSize=28f
        canvas.drawText("Skip the wait.",411f,231f,paint)
        val glyphs=listOf(R.drawable.ic_qing_home,R.drawable.ic_qing_apps,R.drawable.ic_qing_rules,R.drawable.ic_qing_history,R.drawable.ic_qing_settings)
        glyphs.forEachIndexed {i,id -> draw(canvas,vector(id).apply {setTint(ink)},412+i*92,289,32)}
        paint.textSize=20f
        canvas.drawText("Launcher",82f,414f,paint)
        draw(canvas,context.packageManager.getApplicationIcon(context.packageName),72,440,112)
        canvas.drawText("Themed",248f,414f,paint)
        paint.color=android.graphics.Color.rgb(221,235,221)
        canvas.drawRoundRect(240f,440f,352f,552f,28f,28f,paint)
        draw(canvas,vector(R.drawable.ic_brand_monochrome).apply {setTint(ink)},212,412,168)
        paint.color=ink
        canvas.drawText("Small sizes",416f,414f,paint)
        listOf(24,48,72).forEachIndexed {i,size -> tile(canvas,416+i*116,460,size)}
        paint.textSize=17f
        canvas.drawText("Flat double leap  /  QingIcon Line interface icons",72f,602f,paint)
        save(board,"tiaotiao-brand-board")
    }
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
