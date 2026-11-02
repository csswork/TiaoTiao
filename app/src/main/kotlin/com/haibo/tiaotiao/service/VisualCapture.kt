package com.haibo.tiaotiao.service

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.haibo.tiaotiao.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class VisualFrame(val screen: ScreenSnapshot, val bitmap: Bitmap, val signals: VisualSignals,
    val jpeg: ByteArray, val at: Long) : AutoCloseable {
    private val targetHashes=mutableMapOf<Area,String>()
    fun patch(area: Area): String = patchFrom(bitmap,area)
    /** Learning sees the masked 720px JPEG. Match targets through that same preprocessing. */
    suspend fun targetPatch(area: Area): String = withContext(Dispatchers.Default) {
        targetHashes.getOrPut(area) {
            val matching=requireNotNull(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
            try {patchFrom(matching,area)} finally {matching.recycle()}
        }
    }
    private fun patchFrom(image: Bitmap,area: Area): String {
        val values = (0 until 64).map { n ->
            val x = ((area.left + (area.right-area.left) * ((n%8+.5f)/8)) * image.width).toInt().coerceIn(0,image.width-1)
            val y = ((area.top + (area.bottom-area.top) * ((n/8+.5f)/8)) * image.height).toInt().coerceIn(0,image.height-1)
            val c = image.getPixel(x,y); Color.red(c)*.299 + Color.green(c)*.587 + Color.blue(c)*.114
        }
        val mean = values.average(); return values.joinToString("") { if (it >= mean) "1" else "0" }
    }
    fun difference(other: VisualFrame): Double {
        var total=0L
        for (row in 0 until 32) for (column in 0 until 16) {
            val a=bitmap.getPixel(((column+.5f)/16*bitmap.width).toInt(),((row+.5f)/32*bitmap.height).toInt())
            val b=other.bitmap.getPixel(((column+.5f)/16*other.bitmap.width).toInt(),((row+.5f)/32*other.bitmap.height).toInt())
            total+=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b))
        }
        return total/(512.0*3*255)
    }
    override fun close() = bitmap.recycle()
}

@android.annotation.SuppressLint("NewApi")
class VisualCapture(private val service: AccessibilityService) {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val navigation = setOf("首页","我的","分类","搜索","推荐","关注","消息","发现","动态","个人中心","设置","购物车","订单")
    private val warmScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    fun warmUp() {
        warmScope.launch {
            val bitmap=Bitmap.createBitmap(160,64,Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply { drawColor(Color.WHITE);drawText("广告 5",8f,45f,Paint().apply {color=Color.BLACK;textSize=32f}) }
                kotlin.coroutines.suspendCoroutine { c: kotlin.coroutines.Continuation<Unit> ->
                    recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener { c.resume(Unit) }
                }
            } catch(c: CancellationException) {throw c}
            catch(_: Exception) { /* A failed warm-up does not disable actual capture attempts. */ }
            finally {bitmap.recycle()}
        }
    }
    suspend fun capture(screen: ScreenSnapshot): VisualFrame {
        var owned: VisualFrame? = null
        try {
            withContext(Dispatchers.Default) { owned=captureWorker(screen) }
            return requireNotNull(owned)
        } catch (t: Throwable) { owned?.close();throw t }
    }
    private suspend fun captureWorker(screen: ScreenSnapshot): VisualFrame {
        check(Build.VERSION.SDK_INT >= 30) { "视觉学习需要 Android 11 及以上" }
        // A disappearing Android starting window can otherwise hold this callback for seconds.
        // Late callbacks release their bitmap; the startup loop can retry the current window.
        val bitmap = withTimeoutOrNull(700) {screenshot(screen.windowId)}
            ?: throw IllegalStateException("截图请求超时")
        val capturedAt = SystemClock.elapsedRealtime()
        var ocrBitmap: Bitmap? = null
        return try {
            check(kotlin.math.abs(bitmap.width.toFloat()/bitmap.height-screen.width.toFloat()/screen.height)<.015f) { "窗口比例变化，暂不使用截图坐标" }
            val ocrWidth=minOf(720,bitmap.width)
            val imageBitmap=Bitmap.createScaledBitmap(bitmap,ocrWidth,(ocrWidth.toFloat()*bitmap.height/bitmap.width).toInt(),true)
            ocrBitmap=imageBitmap
            val result = kotlin.coroutines.suspendCoroutine<com.google.mlkit.vision.text.Text> { c ->
                recognizer.process(InputImage.fromBitmap(imageBitmap,0))
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
            currentCoroutineContext().ensureActive()
            val lines = result.textBlocks.flatMap { it.lines }
            fun area(r: Rect) = Area((r.left.toFloat()/imageBitmap.width).coerceIn(0f,1f),(r.top.toFloat()/imageBitmap.height).coerceIn(0f,1f),
                (r.right.toFloat()/imageBitmap.width).coerceIn(0f,1f),(r.bottom.toFloat()/imageBitmap.height).coerceIn(0f,1f))
            fun isSkipControl(text: String): Boolean {
                val t=text.replace(Regex("\\s"),"")
                if (listOf("登录","引导","教程","视频","支付").any { it in t }) return false
                return SignalText.isSkip(t) || SignalText.sanitize(t)=="关闭" || t.matches(Regex("[\\d秒sS|｜:：()（）<>›»→·.\\-]*跳过(?:广告)?[\\d秒sS|｜:：()（）<>›»→·.\\-]*"))
            }
            val skipLines = lines.filter { isSkipControl(it.text) && it.boundingBox != null }
            val skips = (skipLines.map { area(requireNotNull(it.boundingBox)) } +
                screen.nodes.filter { SignalText.hasSkip(it.label) || SignalText.canonical(it.label)=="关闭" }.map { it.bounds }).filter { it.valid() }.distinct()
            val ad = screen.nodes.any { SignalText.canonical(it.label).split('|').contains("广告") } ||
                lines.any { it.text.replace(Regex("[\\s|｜·]"),"") in setOf("广告","AD","Advertisement") }
            val inlineTimer = skipLines.mapNotNull { Regex("\\d{1,2}").find(it.text)?.value?.toIntOrNull() }.maxOrNull()
            val nearbyTimer = lines.filter { it.text.trim().matches(Regex("\\d{1,2}\\s*(?:秒|s|S)?")) }.mapNotNull { line ->
                val a = line.boundingBox?.let(::area) ?: return@mapNotNull null
                if (skips.any { abs(it.left-a.left)<.18 && abs(it.top-a.top)<.06 }) Regex("\\d{1,2}").find(line.text)?.value?.toIntOrNull() else null
            }.maxOrNull()
            val edgeTimer=lines.mapNotNull {
                val a=it.boundingBox?.let(::area) ?: return@mapNotNull null
                if(a.right>.6f && a.right-a.left<.35f && a.bottom-a.top<.1f && (a.top<.2f || a.bottom>.8f))
                    Regex("(?<!\\d)(\\d{1,2})\\s*(?:秒|s|S)(?![A-Za-z])").find(it.text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf {n -> n in 1..15}
                else null
            }.distinct().singleOrNull()
            val home = !ad && skips.isEmpty() && lines.filter {
                it.boundingBox?.let {r -> area(r).top>.72f}==true
            }.flatMap {it.text.trim().split(Regex("\\s+"))}.filter {it in navigation}.distinct().size>=2
            // Mask body text; retain public labels and short ambiguous controls at ad edges.
            val masked = imageBitmap.copy(Bitmap.Config.ARGB_8888,true)
            val canvas = Canvas(masked); val paint = Paint().apply { color = Color.rgb(170,170,170) }
            fun possibleControl(line: com.google.mlkit.vision.text.Text.Line): Boolean {
                val rect=line.boundingBox ?: return false
                val a=area(rect)
                // Keep short ambiguous labels at ad edges visible to the visual model, rather than
                // masking the very button that OCR could not interpret. Other OCR text remains masked.
                return ad && line.text.length<=12 && !Regex("\\d{3,}|@|https?://").containsMatchIn(line.text) &&
                    a.right>.65f && (a.top<.18f || a.bottom>.82f)
            }
            lines.filterNot { SignalText.isPublicControlText(it.text) || it.text.trim() in navigation || possibleControl(it) ||
                it.text.trim().matches(Regex("\\d{1,2}\\s*(?:秒|s|S)")) }.forEach {
                it.boundingBox?.let { r -> canvas.drawRect(r,paint) }
            }
            val jpeg = ByteArrayOutputStream().use { masked.compress(Bitmap.CompressFormat.JPEG,75,it); it.toByteArray() }
            masked.recycle()
            VisualFrame(screen,bitmap,VisualSignals(ad,inlineTimer ?: nearbyTimer ?: edgeTimer,skips,home),jpeg,capturedAt)
        } catch (t: Throwable) { bitmap.recycle(); throw t }
        finally { ocrBitmap?.takeIf { it!==bitmap }?.recycle() }
    }
    private suspend fun screenshot(window: Int): Bitmap = suspendCancellableCoroutine { c ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace))
                    val copy = wrapped.copy(Bitmap.Config.ARGB_8888,false)
                    wrapped.recycle()
                    if (c.isActive) c.resume(copy) { _, b, _ -> b.recycle() } else copy.recycle()
                } catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
                finally { result.hardwareBuffer.close() }
            }
            override fun onFailure(code: Int) { if (c.isActive) c.resumeWithException(IllegalStateException("截图不可用（$code）")) }
        }
        val executor = java.util.concurrent.Executor { it.run() }
        if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(window,executor,callback)
        else service.takeScreenshot(Display.DEFAULT_DISPLAY,executor,callback)
    }
    fun close() { warmScope.cancel();recognizer.close() }
}
