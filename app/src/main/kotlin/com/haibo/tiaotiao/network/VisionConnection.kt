package com.haibo.tiaotiao.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.data.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A fixed public image checks the selected endpoint without reading another application. */
suspend fun testVisionConnection(repository: AppRepository): String {
    val config=repository.settings.value;val generation=repository.beginVisionCheck()
    return try {
        val key=repository.visionKey(config,probe=true) ?: error("请填写 ${config.visionProvider.displayName} 的密钥")
        val image=withContext(Dispatchers.Default) {
            val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,80,it);it.toByteArray()}
            } finally {bitmap.recycle()}
        }
        val start=System.nanoTime()
        VisualClient().recognize(image,key,config.visionModel,config.visionProvider)
        check(repository.sameVision(config)) {"图片服务配置已改变，请重新测试"}
        val message="${config.visionProvider.displayName} 图片连接成功（${(System.nanoTime()-start)/1_000_000} ms）"
        repository.finishVisionCheck(generation,ConnectionState.CONNECTED,message);repository.log("",message);message
    } catch(c: CancellationException) {
        repository.finishVisionCheck(generation,ConnectionState.UNTESTED,"图片连接测试已取消");throw c
    } catch(e: Exception) {
        val message=modelErrorMessage(e)
        repository.finishVisionCheck(generation,ConnectionState.FAILED,message);repository.log("",message);message
    }
}
