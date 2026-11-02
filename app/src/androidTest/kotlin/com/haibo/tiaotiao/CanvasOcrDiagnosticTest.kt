package com.haibo.tiaotiao

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Test
import org.junit.Assume.assumeTrue

class CanvasOcrDiagnosticTest {
    @Test fun inspectSyntheticTimer() {
        assumeTrue(android.os.Build.HARDWARE.contains("ranchu"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services ''").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        Thread.sleep(200)
        automation.executeShellCommand("settings put secure enabled_accessibility_services com.haibo.tiaotiao/com.haibo.tiaotiao.service.SkipAccessibilityService").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        automation.executeShellCommand("settings put secure accessibility_enabled 1").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val until=android.os.SystemClock.elapsedRealtime()+5000
        while(com.haibo.tiaotiao.service.SkipAccessibilityService.instance==null && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(100)
        automation.executeShellCommand("am start -W -n com.haibo.tiaotiao.fixture.beta/com.haibo.tiaotiao.fixture.FixtureActivity --ez canvas true -f 0x10008000").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        Thread.sleep(400)
        val service=requireNotNull(com.haibo.tiaotiao.service.SkipAccessibilityService.instance)
        val window=service.windows.first {it.type==1 && it.isFocused}
        val screen=com.haibo.tiaotiao.core.ScreenSnapshot("com.haibo.tiaotiao.fixture.beta",1,1080,2400,emptyList(),0,window.id)
        val capture=com.haibo.tiaotiao.service.VisualCapture(service)
        val frame=kotlinx.coroutines.runBlocking {capture.capture(screen)}
        val scaled=android.graphics.Bitmap.createScaledBitmap(frame.bitmap,720,1600,true)
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(scaled,0)))
            println("Synthetic OCR: "+result.textBlocks.flatMap {it.lines}.map {it.text+":"+it.boundingBox}+" signals="+frame.signals)
        } finally {recognizer.close();scaled.recycle();frame.close();capture.close()}
    }
}
