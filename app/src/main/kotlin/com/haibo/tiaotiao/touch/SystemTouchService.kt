package com.haibo.tiaotiao.touch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

/** Runs under the locally authorized shell identity. No arbitrary command or file API. */
@Keep
class SystemTouchService @JvmOverloads constructor(initialOwnerUid: Int?=null,private val context: Context?=null) : ISystemTouch.Stub() {
    companion object {
        const val PRESS_MS=120L
        internal fun command(x: Int,y: Int)=listOf("/system/bin/input","touchscreen","swipe",
            x.toString(),y.toString(),x.toString(),y.toString(),PRESS_MS.toString())
    }
    private var ownerUid: Int?=initialOwnerUid
    private var lastInput=0L
    private var sequence=0L
    private val direct by lazy {DirectSystemTouch.resolve()}
    private fun authenticate(): Boolean=Process.myUid() in setOf(0,2000) && (ownerUid==null || ownerUid==Binder.getCallingUid())
    private fun permission(): String=try {
        if(context==null) "unknown" else if(context.checkPermission("android.permission.INJECT_EVENTS",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED) "granted" else "denied"
    } catch(_: Exception) {"unknown"}
    @Synchronized override fun status(): Bundle {
        check(authenticate()) {"Unauthorized touch client"}
        return Bundle().apply {
            putInt("protocol",2);putInt("uid",Process.myUid());putInt("pid",Process.myPid())
            putLong("uptimeMs",SystemClock.uptimeMillis());putString("permission",permission());putBoolean("directAvailable",direct!=null)
        }
    }
    @Synchronized override fun tap(expectedPackage: String,x: Int,y: Int,width: Int,height: Int,deadlineUptimeMs: Long): Int =
        tapDetailed(expectedPackage,x,y,width,height,deadlineUptimeMs).getInt("code")
    @Synchronized override fun tapDetailed(expectedPackage: String,x: Int,y: Int,width: Int,height: Int,deadlineUptimeMs: Long): Bundle {
        val started=SystemClock.uptimeMillis()
        val id="${Process.myPid()}-${++sequence}"
        fun reply(code: Int,reason: String)=Bundle().apply {
            putInt("code",code);putString("reason",reason);putString("requestId",id)
            putInt("uid",Process.myUid());putInt("pid",Process.myPid());putInt("displayId",0)
            putLong("elapsedMs",SystemClock.uptimeMillis()-started)
        }
        if(!authenticate()) return reply(-1,"unauthorized_caller")
        if(!expectedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) ||
            width !in 1..16384 || height !in 1..16384 || x !in 0 until width || y !in 0 until height) return reply(-1,"invalid_coordinates")
        if(deadlineUptimeMs<started || deadlineUptimeMs-started>1000) return reply(-1,"request_expired")
        if(started-lastInput<400) return reply(-1,"rate_limited")
        ownerUid=Binder.getCallingUid()
        val identity=Binder.clearCallingIdentity()
        try {
            if(permission()=="denied") return reply(-1,"inject_permission_denied")
            // Recheck foreground in the privileged process too, after the Binder handoff.
            val focus=ProcessBuilder("/system/bin/dumpsys","window","displays").redirectErrorStream(true).start()
            val read=FutureTask {focus.inputStream.bufferedReader().use {it.readText()}}
            Thread(read,"touch-foreground").apply {isDaemon=true;start()}
            val output=try {read.get(450,TimeUnit.MILLISECONDS)}
                catch(_: Exception) {return reply(-1,"foreground_check_timeout")}
                finally {if(focus.isAlive) focus.destroyForcibly();read.cancel(true)}
            SystemTouchGuard.rejection(expectedPackage,x,y,width,height,output)?.let {return reply(-1,it)}
            if(SystemClock.uptimeMillis()>deadlineUptimeMs) return reply(-1,"request_expired_after_check")
            lastInput=SystemClock.uptimeMillis()
            direct?.let {return reply(if(it.tap(x,y,PRESS_MS)) 1 else 0,it.lastReason)}
            val process=ProcessBuilder(command(x,y)).redirectErrorStream(true).start()
            return if(!process.waitFor(1500,TimeUnit.MILLISECONDS)) {process.destroyForcibly();reply(0,"command_timeout")}
                else if(process.exitValue()==0) reply(2,"command_completed") else reply(0,"command_exit_${process.exitValue()}")
        } catch(error: Exception) {return reply(0,"service_exception_${error.javaClass.simpleName}")}
        finally {Binder.restoreCallingIdentity(identity)}
    }
    private val sensorTimer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor {Thread(it,"sensor-restore").apply {isDaemon=true}}
    private val sensorRestores=mutableMapOf<String,java.util.concurrent.ScheduledFuture<*>>()
    private fun sensorState(packageName: String,state: String): Boolean {
        val process=ProcessBuilder("/system/bin/cmd","sensorservice",if(state=="reset") "reset-uid-state" else "set-uid-state",
            *(if(state=="reset") arrayOf(packageName) else arrayOf(packageName,state))).redirectErrorStream(true).start()
        if(!process.waitFor(1500,TimeUnit.MILLISECONDS)) {process.destroyForcibly();return false}
        return process.exitValue()==0
    }
    /** Shake/flip launch ads read motion sensors to open their landing page; idle UIDs receive no sensor events.
     * Only a package name and a bounded duration are accepted, and the restore is scheduled here, not by the caller. */
    @Synchronized override fun pauseSensors(packageName: String,durationMs: Long): Bundle {
        fun reply(code: Int,reason: String)=Bundle().apply {putInt("code",code);putString("reason",reason)}
        if(!authenticate()) return reply(-1,"unauthorized_caller")
        if(!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) || durationMs !in 1000..15_000) return reply(-1,"invalid_request")
        val identity=Binder.clearCallingIdentity()
        try {
            if(!sensorState(packageName,"idle")) return reply(0,"sensor_command_failed")
            sensorRestores.remove(packageName)?.cancel(false)
            sensorRestores[packageName]=sensorTimer.schedule({
                synchronized(this) {sensorRestores.remove(packageName)}
                runCatching {sensorState(packageName,"reset")}
            },durationMs,TimeUnit.MILLISECONDS)
            return reply(1,"sensors_paused")
        } catch(error: Exception) {return reply(0,"service_exception_${error.javaClass.simpleName}")}
        finally {Binder.restoreCallingIdentity(identity)}
    }
    @Synchronized override fun resumeSensors(packageName: String): Bundle {
        val result=Bundle()
        if(!authenticate() || !packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) return result.apply {putInt("code",-1)}
        sensorRestores.remove(packageName)?.cancel(false)
        val identity=Binder.clearCallingIdentity()
        return try {result.apply {putInt("code",if(sensorState(packageName,"reset")) 1 else 0)}}
            catch(_: Exception) {result.apply {putInt("code",0)}} finally {Binder.restoreCallingIdentity(identity)}
    }
    @Synchronized override fun destroy() {
        check(authenticate())
        // Never leave an app sensor-idle behind when the service is stopped.
        sensorRestores.keys.toList().forEach {runCatching {sensorState(it,"reset")}}
        kotlin.system.exitProcess(0)
    }
}
