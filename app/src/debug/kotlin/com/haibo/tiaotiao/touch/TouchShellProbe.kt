package com.haibo.tiaotiao.touch

import android.os.Process
import android.os.SystemClock

/** ADB-only probe of the same bounded UserService used by Shizuku. No component is exported. */
object TouchShellProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(Process.myUid()==2000)
        require(args.size==5)
        println(SystemTouchService().tap(args[0],args[1].toInt(),args[2].toInt(),args[3].toInt(),args[4].toInt(),SystemClock.uptimeMillis()+600))
    }
}
