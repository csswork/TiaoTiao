package com.haibo.tiaotiao.touch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import android.system.Os
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlin.system.exitProcess

/** A single-app shell service, launched by the user's paired local ADB connection. */
@Keep
object OwnedTouchServer {
    private const val APP="com.haibo.tiaotiao"
    private var lifetimeLock: FileLock?=null
    @JvmStatic fun main(args: Array<String>) {
        require(Process.myUid() in setOf(0,2000))
        Looper.prepareMainLooper()
        val type=Class.forName("android.app.ActivityThread")
        val thread=type.getMethod("systemMain").invoke(null)
        val system=type.getMethod("getSystemContext").invoke(thread) as Context
        val context=system.createPackageContext("com.android.shell",0)
        val info=context.packageManager.getApplicationInfo(APP,0)
        require(info.enabled && info.flags and ApplicationInfo.FLAG_STOPPED==0)
        val lockPath="/data/local/tmp/tiaotiao-touch-${info.uid}.lock"
        val lockFile=RandomAccessFile(lockPath,"rw")
        Os.chmod(lockPath,0x180)
        lifetimeLock=lockFile.channel.tryLock() ?: exitProcess(0)
        val service=SystemTouchService(info.uid,context)
        val main=Handler(Looper.getMainLooper())
        var retries=0
        fun attach() {
            try {
                val current=context.packageManager.getApplicationInfo(APP,0)
                if(!current.enabled || current.flags and ApplicationInfo.FLAG_STOPPED!=0 || current.sourceDir!=info.sourceDir) exitProcess(0)
                val response=ShellProviderLink.attach("$APP.owned-touch",
                    Bundle().apply {putInt("protocol",2);putBinder("service",service)})
                val client=requireNotNull(response?.getBinder("client"))
                client.linkToDeath({main.postDelayed({attach()},1000)},0)
                retries=0
            } catch(error: Exception) {
                System.err.println("Local service attach failed: ${error.javaClass.simpleName}: ${error.message}")
                if(++retries>=3) exitProcess(1)
                main.postDelayed({attach()},1000)
            }
        }
        attach()
        Looper.loop()
    }
}
