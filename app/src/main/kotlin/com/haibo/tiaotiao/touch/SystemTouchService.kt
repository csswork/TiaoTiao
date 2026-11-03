package com.haibo.tiaotiao.touch

import android.os.Binder
import android.os.Process
import android.os.SystemClock
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

/** Runs under Shizuku's shell identity. No arbitrary command, file, or coordinate script API. */
@Keep
class SystemTouchService : ISystemTouch.Stub() {
    companion object {
        const val PRESS_MS=120L
        internal fun command(x: Int,y: Int)=listOf("/system/bin/input","touchscreen","swipe",
            x.toString(),y.toString(),x.toString(),y.toString(),PRESS_MS.toString())
    }
    private var ownerUid: Int?=null
    private var lastInput=0L
    private val direct by lazy {DirectSystemTouch.resolve()}
    @Synchronized override fun tap(expectedPackage: String,x: Int,y: Int,width: Int,height: Int,deadlineUptimeMs: Long): Int {
        val caller=Binder.getCallingUid()
        if(ownerUid!=null && ownerUid!=caller) return -1
        if(Process.myUid()!=2000 && Process.myUid()!=0) return -1
        if(!expectedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) ||
            width !in 1..16384 || height !in 1..16384 || x !in 0 until width || y !in 0 until height) return -1
        val now=SystemClock.uptimeMillis()
        if(deadlineUptimeMs<now || deadlineUptimeMs-now>1000 || now-lastInput<400) return -1
        ownerUid=caller
        return try {
            // Recheck foreground in the privileged process too, after the Binder handoff.
            val focus=ProcessBuilder("/system/bin/dumpsys","window","displays").redirectErrorStream(true).start()
            val read=FutureTask {focus.inputStream.bufferedReader().use {it.readText()}}
            Thread(read,"touch-foreground").apply {isDaemon=true;start()}
            val output=try {read.get(450,TimeUnit.MILLISECONDS)}
                catch(_: Exception) {return -1}
                finally {if(focus.isAlive) focus.destroyForcibly();read.cancel(true)}
            if(!SystemTouchGuard.allows(expectedPackage,x,y,width,height,output) || SystemClock.uptimeMillis()>deadlineUptimeMs) return -1
            lastInput=SystemClock.uptimeMillis()
            direct?.let {return if(it.tap(x,y,PRESS_MS)) 1 else 0}
            val process=ProcessBuilder(command(x,y)).redirectErrorStream(true).start()
            if(!process.waitFor(1500,TimeUnit.MILLISECONDS)) {process.destroyForcibly();0}
            else if(process.exitValue()==0) 2 else 0
        } catch(_: Exception) {0}
    }
    override fun destroy() {kotlin.system.exitProcess(0)}
}
