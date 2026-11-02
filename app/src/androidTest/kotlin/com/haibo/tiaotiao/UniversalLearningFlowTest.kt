package com.haibo.tiaotiao

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.data.VisualLearner
import com.haibo.tiaotiao.network.JevClient
import com.haibo.tiaotiao.network.VisualClient
import com.haibo.tiaotiao.service.SkipAccessibilityService
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class UniversalLearningFlowTest {
    @Test fun firstLaunchUnknownIconsLateResultsAndOfflineReuseWorkAcrossPackages() {
        assumeTrue("仅在独立模拟器运行",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as TiaoApplication
        val repository=app.repository
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String=automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }
        val alpha="com.haibo.tiaotiao.fixture.alpha";val beta="com.haibo.tiaotiao.fixture.beta"
        listOf(alpha,beta).forEach { assertTrue("请先安装 test-app 的两个 debug APK",shell("pm path $it").contains("package:")) }
        fun await(label: String,predicate: () -> Boolean) {
            val until=SystemClock.elapsedRealtime()+25_000
            while(SystemClock.elapsedRealtime()<until) {if(predicate()) return;Thread.sleep(100)}
            val diagnostics=shell("dumpsys activity service ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
            fail("$label 超时；$diagnostics；日志=${repository.logs.value.take(12)}")
        }
        fun open(pkg: String,login: Boolean=false,canvas: Boolean=false) = instrumentation.runOnMainSync {
            context.startActivity(Intent().setComponent(ComponentName(pkg,"com.haibo.tiaotiao.fixture.FixtureActivity"))
                .putExtra("login",login).putExtra("canvas",canvas).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        fun clicks(pkg: String)=shell("run-as $pkg cat files/clicks").trim().toIntOrNull() ?: 0
        fun leave() {shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");Thread.sleep(700)}
        val calls=ConcurrentHashMap<String,AtomicInteger>()
        val verifications=AtomicInteger()
        val recognitions=AtomicInteger()
        val imageOnly=AtomicBoolean(false)
        val jevHttp=OkHttpClient.Builder().addInterceptor {chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer)
            val state=Json.parseToJsonElement(buffer.readUtf8()).jsonObject["state"]!!.jsonObject
            val pkg=state["package"]!!.jsonPrimitive.content
            calls.computeIfAbsent(pkg) {AtomicInteger()}.incrementAndGet()
            if(pkg==beta) Thread.sleep(3000) // Result deliberately arrives after the application leaves.
            val controls=state["controls"]!!.jsonArray.map {it.jsonObject}
            val target=controls.find {it["resource_id"]!!.jsonPrimitive.content.endsWith(":id/ad_exit")}?.get("id")?.jsonPrimitive?.content
            val advertisement=state["page_signals"]!!.jsonArray.any {"广告" in it.jsonPrimitive.content}
            val chosen=if(advertisement && target!=null) target else "none"
            val ids=controls.map {it["id"]!!.jsonPrimitive.content}+"none"
            val body=buildJsonObject {putJsonObject("answers") {
                putJsonObject("splash") {put("type","noul");put("noul",if(advertisement) .99 else .01)}
                putJsonObject("target") {put("type","choice");put("choice",chosen);put("confidence",.99)
                    putJsonObject("probabilities") {ids.forEach {put(it,if(it==chosen) .99 else .01/(ids.size-1))}}
                }
            }}.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val visionHttp=OkHttpClient.Builder().addInterceptor {chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer)
            val request=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val verification=request["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["name"]!!.jsonPrimitive.content=="skip_verification"
            val answer=if(verification) {
                verifications.incrementAndGet()
                """{"normalHome":true,"advertisementGone":true,"landingPage":false,"systemPrompt":false,"confidence":0.99}"""
            } else {
                recognitions.incrementAndGet()
                if(imageOnly.get()) """{"advertisement":true,"confidence":0.99,"label":"close_ad","left":0.85,"top":0.035,"right":0.96,"bottom":0.105}"""
                else """{"advertisement":false,"confidence":0.1,"label":"none","left":0,"top":0,"right":0,"bottom":0}"""
            }
            val body=buildJsonObject {putJsonArray("choices") {addJsonObject {
                put("finish_reason","stop");putJsonObject("message") {put("content",answer)}
            }}}.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository.updateSettings {it.copy(enabled=false,cloudEnabled=false)}
        shell("settings put secure enabled_accessibility_services ''")
        await("旧服务停止") {SkipAccessibilityService.instance==null}
        app.visualLearner.close()
        context.getSharedPreferences("visual_cache",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.visualLearner=VisualLearner(context,repository,{JevClient(jevHttp)},{VisualClient(visionHttp)})
        app.visualLearner.clearPending()
        listOf(alpha,beta).forEach {shell("run-as $it rm -f files/clicks")}
        repository.visualRules.value.filter {it.packageName in setOf(alpha,beta)}.forEach {repository.removeVisual(it.id)}
        repository.vault.set("jevKey","isolated-test-key-no-live-requests")
        try {
            repository.updateSettings {it.copy(enabled=true,automaticLearning=true,cloudEnabled=true,packages=setOf(alpha,beta),
                jevProvider=JevProvider.VERCEL_AI_GATEWAY,model="typesafe-ai/jev")}
            shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            await("服务连接") {SkipAccessibilityService.instance!=null && repository.connected.value}
            repository.clearLogs();leave();open(alpha)
            await("首次打开无文字关闭控件") {clicks(alpha)==1}
            await("首次自动结果检查") {repository.visualRules.value.any {it.packageName==alpha && it.successfulSessions()==1}}
            val learned=repository.visualRules.value.first {it.packageName==alpha}
            assertEquals("关闭",learned.nodeTarget?.label)
            val firstCalls=calls[alpha]?.get() ?: 0
            assertTrue(firstCalls>0)
            leave();open(alpha)
            await("缓存自动复用") {clicks(alpha)==2}
            await("跨会话启用") {repository.visualRules.value.any {it.packageName==alpha && it.active()}}
            assertEquals("已有方案不应再次调用 Jev",firstCalls,calls[alpha]?.get())
            leave();open(alpha,true);Thread.sleep(1800)
            assertEquals("登录页相同位置的关闭按钮不能复用广告规则",2,clicks(alpha))
            leave();open(beta)
            await("另一个未知应用提前调用 Jev") {(calls[beta]?.get() ?: 0)>0}
            leave()
            await("离开应用后继续缓存") {repository.visualRules.value.any {it.packageName==beta}}
            assertEquals("晚到结果不能点击后台或其他应用",0,clicks(beta))
            val betaCalls=calls[beta]?.get()
            open(beta)
            await("下次直接复用晚到缓存") {clicks(beta)==1}
            assertEquals(betaCalls,calls[beta]?.get())
            await("Beta 结果检查完成") {repository.visualRules.value.any {it.packageName==beta && it.evidence.isNotEmpty()}}
            leave()
            repository.updateSettings {it.copy(cloudEnabled=false)}
            val networkCount=calls.values.sumOf {it.get()}+verifications.get()+recognitions.get()
            open(alpha)
            await("关闭云端后复用") {clicks(alpha)==3}
            await("本地结果检查") {repository.logs.value.any {it.packageName==alpha && "本地复用及结果检查通过" in it.message}}
            assertEquals(networkCount,calls.values.sumOf {it.get()}+verifications.get()+recognitions.get())
            leave()
            repository.updateSettings {it.copy(cloudEnabled=true)}
            repository.visualRules.value.filter {it.packageName==beta}.forEach {repository.removeVisual(it.id)}
            app.visualLearner.clearPending();imageOnly.set(true)
            val beforeImages=recognitions.get();val beforeNodes=calls[beta]?.get()
            open(beta,canvas=true)
            await("无广告和跳过文字的纯图像关闭") {clicks(beta)==2}
            await("图像方案自动检查") {repository.visualRules.value.any {it.packageName==beta && it.successfulSessions()==1}}
            assertTrue(recognitions.get()>beforeImages)
            assertEquals("没有控件时不向 Jev 编造目标",beforeNodes,calls[beta]?.get())
            val cachedImageCalls=recognitions.get()
            leave();open(beta,canvas=true)
            await("纯图像广告复用缓存") {clicks(beta)==3}
            await("纯图像方案跨会话启用") {repository.visualRules.value.any {it.packageName==beta && it.active()}}
            assertEquals("已知图像目标不应重复识别",cachedImageCalls,recognitions.get())
        } finally {
            leave()
            repository.updateSettings {it.copy(enabled=false,cloudEnabled=false,packages=emptySet())}
            repository.vault.set("jevKey","")
            app.visualLearner.close()
            app.visualLearner=VisualLearner(context,repository)
        }
    }
}
