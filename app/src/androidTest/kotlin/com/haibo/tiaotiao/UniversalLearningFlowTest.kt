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
        fun open(pkg: String,login: Boolean=false,canvas: Boolean=false,inert: Boolean=false,newWindow: Boolean=false,ignoreFirst: Boolean=false,localSkip: Boolean=false) = instrumentation.runOnMainSync {
            context.startActivity(Intent().setComponent(ComponentName(pkg,"com.haibo.tiaotiao.fixture.FixtureActivity"))
                .putExtra("login",login).putExtra("canvas",canvas).putExtra("inert",inert).putExtra("new_window",newWindow)
                .putExtra("ignore_first_touch",ignoreFirst)
                .putExtra("local_skip",localSkip)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        fun clicks(pkg: String)=shell("run-as $pkg cat files/clicks").trim().toIntOrNull() ?: 0
        fun leave() {shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");Thread.sleep(700)}
        val calls=ConcurrentHashMap<String,AtomicInteger>()
        val positiveNodeRequests=ConcurrentHashMap<String,AtomicInteger>()
        val verifications=AtomicInteger()
        val baselineChecks=AtomicInteger()
        val recognitions=AtomicInteger()
        val officialRequests=AtomicInteger()
        val directJevImages=AtomicInteger()
        val uncertainJevImages=AtomicInteger()
        val imageOnly=AtomicBoolean(false)
        val jevHttp=OkHttpClient.Builder().addInterceptor {chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer)
            val state=Json.parseToJsonElement(buffer.readUtf8()).jsonObject["state"]!!.jsonObject
            val pkg=state["package"]!!.jsonPrimitive.content
            calls.computeIfAbsent(pkg) {AtomicInteger()}.incrementAndGet()
            val controls=state["controls"]!!.jsonArray.map {it.jsonObject}
            val target=controls.find {it["resource_id"]!!.jsonPrimitive.content.endsWith(":id/ad_exit")}?.get("id")?.jsonPrimitive?.content
            val advertisement=state["page_signals"]!!.jsonArray.any {"广告" in it.jsonPrimitive.content}
            val chosen=if(advertisement && target!=null) target else "none"
            if(chosen!="none") positiveNodeRequests.computeIfAbsent(pkg) {AtomicInteger()}.incrementAndGet()
            if(pkg==beta) Thread.sleep(3000) // Result deliberately arrives after the application leaves.
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
            val native=request["assets"] is JsonObject
            val verification=if(native) "normalHome" in request["questions"]!!.jsonObject else
                request["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content.contains("normalHome:boolean")
            val deliberatelyUncertain=native && !verification && request["model"]!!.jsonPrimitive.content=="jev-latest" && uncertainJevImages.getAndIncrement()==0
            if(!native) {
                officialRequests.incrementAndGet()
                assertEquals("https://api.deepseek.com/chat/completions",chain.request().url.toString())
                assertEquals("Bearer isolated-official-key-no-live-requests",chain.request().header("Authorization"))
                assertEquals("deepseek-flash",request["model"]!!.jsonPrimitive.content)
                assertEquals("json_object",request["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            }
            if(native) {
                if(request["model"]!!.jsonPrimitive.content=="jev-latest") {
                    directJevImages.incrementAndGet()
                    assertEquals(JevProvider.TYPE_SAFE.endpoint,chain.request().url.toString())
                    assertEquals("Bearer isolated-official-jev-key",chain.request().header("Authorization"))
                    Thread.sleep(700)
                } else assertEquals(JevProvider.VERCEL_AI_GATEWAY.endpoint,chain.request().url.toString())
                assertEquals("true",chain.request().header("TypeSafe-Danger-Zone-Images"))
                request["assets"]!!.jsonObject.values.forEach {asset ->
                    val bytes=java.util.Base64.getDecoder().decode(asset.jsonObject["data_base64"]!!.jsonPrimitive.content)
                    val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    assertTrue(options.outWidth in 280..1568 && options.outHeight in 280..1568)
                }
            }
            val answer=if(verification) {
                verifications.incrementAndGet()
                if(request.toString().contains("unclicked launch advertisement")) baselineChecks.incrementAndGet()
                """{"normalHome":true,"advertisementGone":true,"landingPage":false,"systemPrompt":false,"confidence":0.99}"""
            } else {
                recognitions.incrementAndGet()
                if(imageOnly.get()) """{"advertisement":true,"confidence":0.99,"label":"close_ad","left":0.85,"top":0.035,"right":0.96,"bottom":0.105}"""
                else """{"advertisement":false,"confidence":0.1,"label":"none","left":0,"top":0,"right":0,"bottom":0}"""
            }
            val body=if(native) buildJsonObject {
                putJsonObject("usage") {put("input_tokens",2000)}
                putJsonObject("answers") {
                    fun noul(name: String,p: Double)=putJsonObject(name) {put("type","noul");put("noul",p)}
                    if(verification) {noul("normalHome",.99);noul("advertisementGone",.99);noul("landingPage",.01);noul("systemPrompt",.01)}
                    else {
                        noul("advertisement",if(deliberatelyUncertain) .6 else if(imageOnly.get()) .99 else .01)
                        putJsonObject("target") {put("type","box");put("confidence",.99);putJsonObject("box") {
                            put("left",.85);put("top",.035);put("right",.96);put("bottom",.105)
                        }}
                    }
                }
            }.toString() else buildJsonObject {putJsonArray("choices") {addJsonObject {
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
        context.getSharedPreferences("visual_baselines",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.visualLearner=VisualLearner(context,repository,{JevClient(jevHttp)},{VisualClient(visionHttp)})
        app.visualLearner.clearPending()
        listOf(alpha,beta).forEach {shell("run-as $it rm -f files/clicks files/native_attempts")}
        repository.visualRules.value.filter {it.packageName in setOf(alpha,beta)}.forEach {repository.removeVisual(it.id)}
        repository.vault.set("jevKey","isolated-test-key-no-live-requests")
        try {
            repository.updateSettings {it.copy(enabled=true,automaticLearning=true,cloudEnabled=true,packages=setOf(alpha,beta),
                jevProvider=JevProvider.VERCEL_AI_GATEWAY,model="typesafe-ai/jev",
                visionProvider=VisionProvider.VERCEL_AI_GATEWAY,visionModel="typesafe-ai/jev-1.14.0")}
            shell("settings put secure enabled_accessibility_services ${context.packageName}/com.haibo.tiaotiao.service.SkipAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            await("服务连接") {SkipAccessibilityService.instance!=null && repository.connected.value}
            repository.clearLogs();leave();open(alpha,newWindow=true)
            await("首次打开无文字关闭控件") {clicks(alpha)==1}
            await("首次自动结果检查") {repository.visualRules.value.any {it.packageName==alpha && it.successfulSessions()==1}}
            val learned=repository.visualRules.value.first {it.packageName==alpha}
            assertEquals("关闭",learned.nodeTarget?.label)
            // A confident positive model response cannot overrule a still-visible original skip control.
            val probeBitmap=android.graphics.Bitmap.createBitmap(320,320,android.graphics.Bitmap.Config.ARGB_8888)
            val probeImage=try {java.io.ByteArrayOutputStream().use {probeBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it);it.toByteArray()}}
                finally {probeBitmap.recycle()}
            app.visualLearner.verify(learned,"visible-skip-negative-control",listOf(probeImage,probeImage,probeImage),
                early=true,sameApp=true,homeScene="d".repeat(64),homeHash="01".repeat(32),basis="countdown",originalTargetStillVisible=true)
            await("原跳过按钮仍在时拒绝模型成功误判") {repository.logs.value.any {it.message=="原跳过按钮仍在，未计为成功；忽略 AI 首页误判"}}
            assertEquals("不得增加验证次数或污染首页模板",learned,repository.visualRules.value.single {it.id==learned.id})
            val firstCalls=calls[alpha]?.get() ?: 0
            assertTrue(firstCalls>0)
            leave();open(alpha,newWindow=true)
            await("缓存自动复用") {clicks(alpha)==2}
            await("跨会话启用") {repository.visualRules.value.any {it.packageName==alpha && it.active()}}
            assertEquals("已有方案不应再次调用 Jev",firstCalls,calls[alpha]?.get())
            leave();open(alpha,true);Thread.sleep(1800)
            assertEquals("登录页相同位置的关闭按钮不能复用广告规则",2,clicks(alpha))
            leave();open(beta)
            await("另一个未知应用提前调用 Jev 并取得广告上下文") {(positiveNodeRequests[beta]?.get() ?: 0)>0}
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
            await("本地结果检查") {repository.logs.value.any {it.packageName==alpha && "本地复用及提前关闭检查通过" in it.message}}
            assertEquals(networkCount,calls.values.sumOf {it.get()}+verifications.get()+recognitions.get())
            leave()
            repository.updateSettings {it.copy(cloudEnabled=true)}
            repository.visualRules.value.filter {it.packageName==beta}.forEach {repository.removeVisual(it.id)}
            app.visualLearner.clearPending();imageOnly.set(true)
            val beforeImages=recognitions.get();val beforeNodes=calls[beta]?.get()
            open(beta,canvas=true)
            await("无广告和跳过文字的纯图像关闭") {clicks(beta)==2}
            await("无倒计时的首次检查保留候选") {repository.visualRules.value.any {it.packageName==beta && it.evidence.any {proof -> proof.timingBasis=="unknown"}}}
            val imageRule=repository.visualRules.value.first {it.packageName==beta && it.evidence.any {proof ->proof.timingBasis=="unknown"}}
            assertEquals(TapMethod.GESTURE,imageRule.tapMethod)
            assertEquals("页面消失不能单独证明提前关闭",0,imageRule.successfulSessions())
            assertTrue(recognitions.get()>beforeImages)
            assertEquals("没有控件时不向 Jev 编造目标",beforeNodes,calls[beta]?.get())
            val cachedImageCalls=recognitions.get()
            leave();open(beta,canvas=true)
            await("无倒计时场景自动观察自然结束") {repository.visualBaseline(imageRule.behavior())>0}
            assertEquals("建立基线时不点击",2,clicks(beta))
            leave();open(beta,canvas=true)
            await("纯图像广告复用缓存") {clicks(beta)==3}
            await("图像方案自动检查") {repository.visualRules.value.any {it.packageName==beta && it.successfulSessions()==1}}
            leave();open(beta,canvas=true)
            await("纯图像广告再次复用") {clicks(beta)==4}
            await("纯图像方案跨会话启用") {repository.visualRules.value.any {it.packageName==beta && it.active()}}
            assertEquals("已知图像目标不应重复识别",cachedImageCalls,recognitions.get())
            val beforeVerify=verifications.get()
            leave();open(beta,canvas=true,inert=true)
            await("图像目标必须真正触控，不能替换成无效原生点击") {clicks(beta)==5}
            assertEquals("图像方案不应尝试无效的原生 ACTION_CLICK",0,shell("run-as $beta cat files/native_attempts").trim().toIntOrNull() ?: 0)
            await("首页其他位置的叉号不能阻止 AI 验证") {verifications.get()>beforeVerify &&
                repository.visualRules.value.any {it.packageName==beta && it.successfulSessions()>=3}}
            leave()
            val observed=repository.visualRules.value.first {it.packageName==beta && it.active()}
            repository.updateVisual(observed.id,observed.behavior()) {
                it.copy(state=VisualRuleState.CANDIDATE,homeHash="",homeScene="",evidence=listOf(
                    AutoEvidence("unknown-timing",it.behavior(),System.currentTimeMillis(),false,true,true,"unknown")))
            }
            context.getSharedPreferences("visual_baselines",android.content.Context.MODE_PRIVATE).edit().clear().commit()
            val beforeBaseline=baselineChecks.get()
            open(beta,canvas=true,inert=true)
            await("首页仍有叉号时由 AI 观察自然结束") {repository.visualBaseline(observed.behavior())>0 && baselineChecks.get()>beforeBaseline}
            assertEquals("自然结束观察不能发出触控",5,clicks(beta))
            assertEquals("自然结束不是一次成功跳过",0,repository.visualRules.value.first {it.id==observed.id}.successfulSessions())
            leave();open(beta,canvas=true,inert=true)
            await("AI 基线后自动触控复用") {clicks(beta)==6}
            await("AI 基线后首次时序检查") {repository.visualRules.value.first {it.id==observed.id}.successfulSessions()==1}
            leave();open(beta,canvas=true,inert=true)
            await("AI 基线后第二次触控复用") {clicks(beta)==7}
            await("AI 基线后两次检查自动启用") {repository.visualRules.value.first {it.id==observed.id}.active()}
            // The official provider must support the same universal pipeline without any Jev credential.
            leave()
            repository.visualRules.value.filter {it.packageName==beta}.forEach {repository.removeVisual(it.id)}
            app.visualLearner.clearPending()
            context.getSharedPreferences("visual_baselines",android.content.Context.MODE_PRIVATE).edit().clear().commit()
            repository.configureVision(VisionProvider.DEEPSEEK_OFFICIAL,"deepseek-flash","isolated-official-key-no-live-requests")
            repository.vault.set("jevKey","")
            val nodeCount=calls.values.sumOf {it.get()}
            assertFalse(app.visualLearner.nodeAllowed(beta));assertTrue(app.visualLearner.allowed(beta))
            open(beta,canvas=true)
            await("官方独立密钥首次纯图像识别并关闭") {clicks(beta)==8}
            await("官方结果检查完成") {repository.visualRules.value.any {it.packageName==beta && it.evidence.isNotEmpty()}}
            val officialRule=repository.visualRules.value.first {it.packageName==beta && it.evidence.isNotEmpty()}
            assertEquals("DEEPSEEK_OFFICIAL:deepseek-flash",officialRule.source)
            val officialRecognitionCount=recognitions.get()
            leave();open(beta,canvas=true)
            await("官方结果检查建立自然结束基线") {repository.visualBaseline(officialRule.behavior())>0}
            leave();open(beta,canvas=true)
            await("官方模式直接复用已缓存目标") {clicks(beta)==9}
            await("官方模式时序检查") {repository.visualRules.value.first {it.id==officialRule.id}.successfulSessions()==1}
            assertEquals("官方模式不重复识别已知目标",officialRecognitionCount,recognitions.get())
            assertEquals("官方模式不调用 Jev",nodeCount,calls.values.sumOf {it.get()})
            assertTrue(officialRequests.get()>=4)
            leave()
            repository.visualRules.value.filter {it.packageName==beta}.forEach {repository.removeVisual(it.id)}
            app.visualLearner.clearPending()
            repository.configureAi(JevProvider.TYPE_SAFE,"jev-latest","isolated-official-jev-key")
            repository.configureVision(VisionProvider.JEV_OFFICIAL,"jev-latest","")
            repository.updateSettings {it.copy(showCheckBubble=true)}
            val bubbleShows=SkipAccessibilityService.instance!!.diagnosticBubbleShows
            open(beta,canvas=true,ignoreFirst=true)
            await("检测时显示检查气泡") {SkipAccessibilityService.instance?.diagnosticBubbleVisible==true}
            repository.updateSettings {it.copy(showCheckBubble=false)}
            await("关闭气泡立即生效") {SkipAccessibilityService.instance?.diagnosticBubbleVisible==false}
            await("首次触控未生效后重新校准补点") {clicks(beta)==10}
            assertEquals("1",shell("run-as $beta cat files/ignored_touch").trim())
            await("官方 Jev 复核完成") {repository.visualRules.value.any {it.source=="JEV_OFFICIAL:jev-latest" && it.evidence.isNotEmpty()}}
            assertTrue(repository.logs.value.any {"补充一次短点" in it.message})
            assertTrue("第一次不确定后应在同一会话重新识别",uncertainJevImages.get()>=2)
            assertTrue(directJevImages.get()>=3)
            assertEquals("每个启动只显示一次提示，阶段变化不再弹出",bubbleShows+1,SkipAccessibilityService.instance!!.diagnosticBubbleShows)
            val directRule=repository.visualRules.value.first {it.source=="JEV_OFFICIAL:jev-latest" && it.evidence.isNotEmpty()}
            assertTrue(repository.previews.entries.value.any {it.ruleId==directRule.id && it.kind==com.haibo.tiaotiao.data.PreviewKind.RECOGNITION})
            assertTrue(repository.previews.entries.value.any {it.ruleId==directRule.id && it.kind==com.haibo.tiaotiao.data.PreviewKind.ACTION})
            leave()
            repository.visualRules.value.filter {it.packageName==alpha}.forEach {repository.removeVisual(it.id)}
            app.visualLearner.clearPending()
            val imageCallsBeforeExplicit=recognitions.get()
            val failedLocal=VisualRule(id="old-local-failure",packageName=alpha,versionCode=1,scene=AdRecognition.scene(
                ScreenSnapshot(alpha,1,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels,emptyList(),0,1)),sceneVersion=2,
                target=Area(.85f,.035f,.96f,.105f),targetHash="01".repeat(32),label="skip",source=VisualExecution.LOCAL_SOURCE,createdAt=1,
                state=VisualRuleState.PAUSED,automaticFailures=1,retryAfter=1,lastResult="自动失败",tapMethod=TapMethod.GESTURE)
            repository.putVisual(failedLocal)
            open(alpha,canvas=true,localSkip=true)
            await("明确广告与文字跳过不等待远端定位") {clicks(alpha)==4}
            await("明确控件仍交给 AI 检查结果") {repository.visualRules.value.any {it.packageName==alpha && it.source==VisualExecution.LOCAL_SOURCE && it.evidence.isNotEmpty()}}
            assertEquals("本地明确目标不应请求图片定位",imageCallsBeforeExplicit,recognitions.get())
        } finally {
            leave()
            repository.updateSettings {it.copy(enabled=false,cloudEnabled=false,packages=emptySet(),showCheckBubble=true)}
            repository.vault.set("jevKey","")
            repository.vault.set("deepseekKey","")
            app.visualLearner.close()
            app.visualLearner=VisualLearner(context,repository)
        }
    }
}
