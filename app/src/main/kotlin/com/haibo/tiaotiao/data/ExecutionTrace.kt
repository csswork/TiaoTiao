package com.haibo.tiaotiao.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.service.VisualFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/** One explicitly armed diagnostic, kept private and overwritten by the next diagnostic. */
object ExecutionTrace {
    suspend fun save(context: Context,rule: VisualRule,area: Area,before: VisualFrame,after: List<VisualFrame>,actionSent: Boolean=true,tapCount: Int=1) = withContext(Dispatchers.IO) {
        val folder=File(context.filesDir,"last_execution").apply {mkdirs()}
        folder.listFiles().orEmpty().forEach {it.delete()}
        File(folder,"before.jpg").writeBytes(before.jpeg)
        after.forEachIndexed {i,frame -> File(folder,"after-$i.jpg").writeBytes(frame.jpeg)}
        val data=buildJsonObject {
            val touches=(context as? com.haibo.tiaotiao.service.SkipAccessibilityService)?.touchExecutions.orEmpty()
                .filter {it.ruleId==rule.id && it.uptimeMs>=before.at}
            put("package",before.screen.packageName);put("ruleId",rule.id);put("tapMethod",rule.tapMethod.name)
            put("actionSent",actionSent)
            put("tapCount",if(actionSent) tapCount else 0)
            if(touches.isNotEmpty()) {
                put("pressDurationMs",touches.last().pressMs)
                put("touches",ruleJson.encodeToJsonElement(touches))
            }
            put("at",System.currentTimeMillis());put("window",before.screen.windowId)
            put("screenWidth",before.screen.width);put("screenHeight",before.screen.height)
            put("bitmapWidth",before.bitmap.width);put("bitmapHeight",before.bitmap.height)
            (context as? android.accessibilityservice.AccessibilityService)?.windows?.firstOrNull {it.id==before.screen.windowId}?.let {window ->
                val rect=android.graphics.Rect().also(window::getBoundsInScreen)
                putJsonArray("windowBounds") {listOf(rect.left,rect.top,rect.right,rect.bottom).forEach {add(it)}}
            }
            put("clickArea",ruleJson.encodeToJsonElement(area));put("ruleTarget",ruleJson.encodeToJsonElement(rule.target))
            put("countdown",before.signals.countdown?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("frames") {
                (listOf(before)+after).forEach {frame -> addJsonObject {
                    put("at",frame.at);put("difference",before.difference(frame))
                    put("advertisement",frame.signals.advertisement);put("home",frame.signals.home)
                    put("closeAreas",ruleJson.encodeToJsonElement(frame.signals.skips))
                    put("explicitSkipAreas",ruleJson.encodeToJsonElement(frame.signals.explicitSkips))
                }}
            }
        }
        File(folder,"trace.json").writeText(data.toString())
        val decoded=requireNotNull(BitmapFactory.decodeByteArray(before.jpeg,0,before.jpeg.size))
        val bitmap=try {decoded.copy(android.graphics.Bitmap.Config.ARGB_8888,true)} finally {decoded.recycle()}
        try {
            val x=(area.left+area.right)/2*bitmap.width;val y=(area.top+area.bottom)/2*bitmap.height
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.RED;style=Paint.Style.STROKE;strokeWidth=3f}
            Canvas(bitmap).apply {drawCircle(x,y,14f,paint);drawLine(x-20,y,x+20,y,paint);drawLine(x,y-20,x,y+20,paint)}
            File(folder,"tap-preview.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        } finally {bitmap.recycle()}
    }
}
