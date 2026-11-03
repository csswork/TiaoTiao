package com.haibo.tiaotiao

import android.graphics.*
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.SkipAccessibilityService
import com.haibo.tiaotiao.service.VisualCapture
import com.haibo.tiaotiao.service.VisualFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualTargetRefinementTest {
    @Test fun currentCropMovesCoarseModelPointInsideNarrowTextControl() = runBlocking {
        assumeTrue("仅在独立模拟器运行",Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=36f}
        val textBounds=Rect().also {paint.getTextBounds("跳过",0,2,it)}.apply {offset(978,185)}
        Canvas(bitmap).apply {drawColor(Color.WHITE);drawText("跳过",978f,185f,paint)}
        val coarse=Area(.899f,.036f,.966f,.067f)
        assertFalse("模型中心应处于实际文字触控范围之外",textBounds.contains(
            ((coarse.left+coarse.right)/2*1080).toInt(),((coarse.top+coarse.bottom)/2*2400).toInt()))
        val frame=VisualFrame(ScreenSnapshot("org.example.reader",1,1080,2400,emptyList(),0,1),
            bitmap,VisualSignals(true,null,emptyList(),false),byteArrayOf(),0)
        val capture=VisualCapture(SkipAccessibilityService())
        try {
            val refined=requireNotNull(capture.refineControl(frame,coarse)) {"应识别局部跳过控件"}
            assertTrue("修正中心必须落在当前实际文字范围内",textBounds.contains(
                ((refined.left+refined.right)/2*1080).toInt(),((refined.top+refined.bottom)/2*2400).toInt()))
        } finally {capture.close();frame.close()}
    }
}
