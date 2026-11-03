package com.haibo.tiaotiao.service

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/** Screenshot, node bounds, and injected points all use the logical display, including system bars. */
internal object DisplayCoordinates {
    fun size(context: Context): Point {
        val manager=context.getSystemService(WindowManager::class.java)
        return if(Build.VERSION.SDK_INT>=30) manager.maximumWindowMetrics.bounds.let {Point(it.width(),it.height())}
        else Point().also {@Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(it)}
    }
}
