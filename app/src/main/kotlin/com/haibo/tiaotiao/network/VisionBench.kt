package com.haibo.tiaotiao.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.haibo.tiaotiao.core.Area
import com.haibo.tiaotiao.core.SignalText
import com.haibo.tiaotiao.core.VisionProvider
import com.haibo.tiaotiao.core.VisualSignals
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.haibo.tiaotiao.data.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File

/** Debug-only: runs both official image providers over the same labeled images. Keys never leave the vault. */
internal object VisionBench {
    @Volatile var status="idle"
        private set
    suspend fun run(dir: File,repository: AppRepository,repeats: Int) {
        val client=VisualClient()
        val keys=mapOf(VisionProvider.JEV_OFFICIAL to repository.vault.get("jevKey"),VisionProvider.DEEPSEEK_OFFICIAL to repository.vault.get("deepseekKey"))
        val images=File(dir,"recognize").listFiles().orEmpty().filter {it.extension=="jpg"}.sortedBy {it.name}
        val cases=File(dir,"verify").listFiles().orEmpty().filter {it.isDirectory}.sortedBy {it.name}
        val out=File(dir,"results.jsonl").apply {delete()}
        var done=0;val total=(images.size+cases.size)*keys.size*repeats
        for(round in 1..repeats) for((provider,key) in keys) {
            for(image in images) {
                status="running $done/$total"
                val row=buildJsonObject {
                    put("kind","recognize");put("name",image.name);put("provider",provider.name);put("round",round)
                    try {
                        val d=client.recognize(image.readBytes(),key,provider.defaultModel,provider)
                        put("advertisement",d.advertisement);put("confidence",d.confidence);put("label",d.label)
                        put("left",d.left);put("top",d.top);put("right",d.right);put("bottom",d.bottom)
                        put("accepted",d.target()!=null);put("elapsedMs",d.elapsedMs ?: -1);put("inputTokens",d.inputTokens ?: -1)
                    } catch(c: CancellationException) {throw c} catch(e: Exception) {put("error",e.message ?: e.javaClass.simpleName)}
                }
                out.appendText(row.toString()+"\n");done++
            }
            for(case in cases) {
                status="running $done/$total"
                val row=buildJsonObject {
                    put("kind","verify");put("name",case.name);put("provider",provider.name);put("round",round)
                    try {
                        val v=client.verify((0..2).map {File(case,"$it.jpg").readBytes()},key,provider.defaultModel,provider=provider)
                        put("normalHome",v.normalHome);put("advertisementGone",v.advertisementGone);put("landingPage",v.landingPage)
                        put("confidence",v.confidence);put("accepted",v.accepted());put("certain",v.certain())
                        put("elapsedMs",v.elapsedMs ?: -1);put("inputTokens",v.inputTokens ?: -1)
                    } catch(c: CancellationException) {throw c} catch(e: Exception) {put("error",e.message ?: e.javaClass.simpleName)}
                }
                out.appendText(row.toString()+"\n");done++
            }
        }
        status="done $done/$total"
    }

    private fun resize(jpeg: ByteArray,width: Int): ByteArray {
        val source=requireNotNull(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
        if(source.width<=width) {source.recycle();return jpeg}
        val scaled=Bitmap.createScaledBitmap(source,width,source.height*width/source.width,true)
        return try {ByteArrayOutputStream().use {scaled.compress(Bitmap.CompressFormat.JPEG,75,it);it.toByteArray()}} finally {scaled.recycle();source.recycle()}
    }
    private suspend fun ocr(jpeg: ByteArray): VisualSignals {
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val text=suspendCoroutine<com.google.mlkit.vision.text.Text> {c ->
                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener {c.resume(it.result)}}
            val lines=text.textBlocks.flatMap {it.lines}
            fun area(r: android.graphics.Rect)=Area(r.left.toFloat()/bitmap.width,r.top.toFloat()/bitmap.height,r.right.toFloat()/bitmap.width,r.bottom.toFloat()/bitmap.height)
            val skips=lines.filter {val t=it.text.replace(Regex("\\s"),"");SignalText.isSkip(t) || SignalText.sanitize(t)=="关闭"}.mapNotNull {it.boundingBox?.let(::area)}
            val ad=lines.any {it.text.replace(Regex("[\\s|｜·]"),"") in setOf("广告","AD")}
            return VisualSignals(ad,null,skips,false,skips)
        } finally {recognizer.close();bitmap.recycle()}
    }
    /** DeepSeek-only comparison of image width, prompt and OCR grounding. Variant: "<width>-<base|new|ocr>". */
    suspend fun runDeepSeek(dir: File,repository: AppRepository,variants: List<String>) {
        val client=VisualClient();val key=repository.vault.get("deepseekKey");val provider=VisionProvider.DEEPSEEK_OFFICIAL
        val images=File(dir,"recognize").listFiles().orEmpty().filter {it.extension=="jpg"}.sortedBy {it.name}
        val out=File(dir,"deepseek.jsonl").apply {delete()}
        var done=0;val total=images.size*variants.size
        for(variant in variants) {
            val width=variant.substringBefore('-').toInt();val mode=variant.substringAfter('-')
            for(image in images) {
                status="running $done/$total"
                val row=buildJsonObject {
                    put("kind","recognize");put("name",image.name);put("provider",provider.name);put("variant",variant)
                    try {
                        val original=image.readBytes();val bytes=resize(original,width)
                        val hints=if(mode=="ocr") ocr(original) else null
                        val d=client.recognize(bytes,key,provider.defaultModel,provider,instruction=if(mode=="base") null else launchAdPrompt(hints))
                        put("advertisement",d.advertisement);put("confidence",d.confidence);put("label",d.label)
                        put("left",d.left);put("top",d.top);put("right",d.right);put("bottom",d.bottom)
                        put("accepted",d.target()!=null);put("elapsedMs",d.elapsedMs ?: -1);put("inputTokens",d.inputTokens ?: -1);put("bytes",bytes.size)
                        put("hints",hints?.skips?.size ?: -1)
                    } catch(c: CancellationException) {throw c} catch(e: Exception) {put("error",e.message ?: e.javaClass.simpleName)}
                }
                out.appendText(row.toString()+"\n");done++
            }
        }
        status="done $done/$total"
    }
}
