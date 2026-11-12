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
    /** Ends other instances of this service (same shell identity, same process name), found through /proc. */
    private fun replaceStale() {
        java.io.File("/proc").listFiles().orEmpty().forEach {entry ->
            val pid=entry.name.toIntOrNull() ?: return@forEach
            if(pid==Process.myPid()) return@forEach
            val name=runCatching {java.io.File(entry,"cmdline").readText().substringBefore('\u0000')}.getOrNull()
            if(name=="tiaotiao_touch" && runCatching {Os.stat(entry.path).st_uid}.getOrNull()==Process.myUid()) {
                System.err.println("Replacing unreachable local service $pid")
                Process.killProcess(pid)
            }
        }
    }
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
        // A holder of the lock that the app cannot reach is useless, and the app only starts a new server when it has
        // none. An orphan from before an app update once held this lock for days and made every start exit here, so
        // the newest server replaces whatever is left instead of giving way to it.
        lifetimeLock=lockFile.channel.tryLock() ?: run {
            replaceStale()
            var lock: FileLock?=null
            for(attempt in 0 until 20) {lock=lockFile.channel.tryLock();if(lock!=null) break;Thread.sleep(100)}
            lock ?: run {System.err.println("Another local service still holds the lock");exitProcess(1)}
        }
        val service=SystemTouchService(info.uid,context)
        val main=Handler(Looper.getMainLooper())
        var retries=0
        var client: android.os.IBinder?=null
        var attaching=false
        fun attach() {
            attaching=false
            try {
                val current=context.packageManager.getApplicationInfo(APP,0)
                if(!current.enabled || current.flags and ApplicationInfo.FLAG_STOPPED!=0 || current.sourceDir!=info.sourceDir) exitProcess(0)
                val response=ShellProviderLink.attach("$APP.owned-touch",
                    Bundle().apply {putInt("protocol",2);putBinder("service",service)})
                val binder=requireNotNull(response?.getBinder("client"))
                client=binder
                binder.linkToDeath({main.post {if(!attaching) {attaching=true;main.postDelayed({attach()},1000)}}},0)
                retries=0
            } catch(error: Throwable) {
                client=null
                System.err.println("Local service attach failed: ${error.javaClass.simpleName}: ${error.message}")
                // A killed app may take a while to be allowed to start again; keep trying for about a minute.
                if(++retries>=12) exitProcess(1)
                attaching=true
                main.postDelayed({attach()},(1000L*retries).coerceAtMost(8000))
            }
        }
        // A missed death notification must not leave this process running unreachable: check the link regularly.
        fun watch() {
            main.postDelayed({
                if(!attaching && client?.pingBinder()!=true) {attaching=true;attach()}
                watch()
            },20_000)
        }
        attach()
        watch()
        Looper.loop()
    }
}
