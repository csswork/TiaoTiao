package com.haibo.tiaotiao

import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haibo.tiaotiao.core.ruleJson
import com.haibo.tiaotiao.data.LogEntry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class LogPersistenceTest {
    @Test fun logBurstAndClearAvoidMainThreadDiskWritesAndPersistInOrder() {
        assumeTrue(Build.VERSION.SDK_INT>=28 && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val repository=(context.applicationContext as TiaoApplication).repository
        val violations=Collections.synchronizedList(mutableListOf<String>())
        instrumentation.runOnMainSync {
            val previous=StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectDiskWrites()
                .penaltyListener({it.run()}) {violations+=it.javaClass.simpleName}.build())
            try {
                repeat(200) {repository.log("com.example.sample","旧诊断 $it")}
                repository.clearLogs()
                repository.log("com.example.sample","清空后的最后一条")
            } finally {StrictMode.setThreadPolicy(previous)}
        }
        val expected=repository.logs.value
        val file=File(context.filesDir,"logs.json")
        val end=SystemClock.elapsedRealtime()+5000
        var persisted=false
        while(SystemClock.elapsedRealtime()<end) {
            if(runCatching {ruleJson.decodeFromString<List<LogEntry>>(file.readText())==expected}.getOrDefault(false)) {persisted=true;break}
            Thread.sleep(50)
        }
        assertTrue("清空后不能被排队中的旧日志覆盖",persisted)
        assertEquals(1,expected.size)
        assertTrue("日志不能在主线程落盘：$violations",violations.isEmpty())
    }
}
