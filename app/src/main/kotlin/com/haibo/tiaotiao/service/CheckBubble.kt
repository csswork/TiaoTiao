package com.haibo.tiaotiao.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*

/** A non-interactive accessibility overlay, independent of the management activity. */
internal class CheckBubble(private val service: AccessibilityService,private val scope: CoroutineScope) {
    private val manager=service.getSystemService(WindowManager::class.java)
    @Volatile private var view: TextView?=null
    private var expiry: Job?=null
    @Volatile var shows=0L
        private set
    val visible get()=view?.visibility==View.VISIBLE
    private fun dp(n: Int)=(n*service.resources.displayMetrics.density).toInt()
    fun show(text: String,timeout: Long=10_000) {
        if(view!=null) return
        expiry?.cancel()
        if(view==null) {
            val label=TextView(service).apply {
                textSize=13f;setTextColor(Color.WHITE);setPadding(dp(14),dp(9),dp(14),dp(9))
                background=GradientDrawable().apply {setColor(Color.rgb(29,76,60));cornerRadius=dp(22).toFloat()}
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply {gravity=Gravity.BOTTOM or Gravity.START;x=dp(16);y=dp(90)}
            if(runCatching {manager.addView(label,params)}.isFailure) return
            view=label
            shows++
            label.post {
                if(view!==label) return@post
                val at=IntArray(2).also(label::getLocationOnScreen)
                // Padded for the rounded edge; screenshots may land between frames of the window animation.
                bounds=android.graphics.Rect(at[0]-dp(4),at[1]-dp(4),at[0]+label.width+dp(4),at[1]+label.height+dp(4))
            }
        }
        view?.text=text
        expiry=scope.launch {delay(timeout);hide()}
    }
    /** Screen pixels covered by the bubble, measured on the main thread after layout; null when not shown. */
    @Volatile var bounds: android.graphics.Rect?=null
        private set
    fun hide() {bounds=null;expiry?.cancel();expiry=null;view?.let {runCatching {manager.removeView(it)}};view=null}
}
