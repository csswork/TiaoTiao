package com.haibo.tiaotiao.fixture

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.widget.*
import android.view.Gravity
import android.view.View
import java.io.File

/** Independent packages exercise the same production code, with no package allowlist in TiaoTiao. */
class FixtureActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private var ended=false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(intent.getBooleanExtra("canvas",false)) {setContentView(ImageAdvertisement());return}
        val login=intent.getBooleanExtra("login",false)
        val root=FrameLayout(this).apply {setBackgroundColor(Color.rgb(228,240,232))}
        fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
        val close=Button(this).apply {
            id=R.id.ad_exit;text="×";textSize=24f
            setOnClickListener {
                val file=File(filesDir,"clicks")
                val n=file.takeIf {it.exists()}?.readText()?.toIntOrNull() ?: 0
                file.writeText((n+1).toString());home()
            }
        }
        root.addView(close,FrameLayout.LayoutParams(dp(72),dp(44),Gravity.TOP or Gravity.END).apply {rightMargin=dp(8);topMargin=dp(16)})
        root.addView(TextView(this).apply {id=R.id.ad_badge;text=if(login) "跳过登录" else "广告";textSize=22f},
            FrameLayout.LayoutParams(dp(160),dp(44),Gravity.TOP or Gravity.START).apply {topMargin=dp(70);leftMargin=dp(16)})
        val timer=TextView(this).apply {textSize=20f;text=if(login) "" else "8秒"}
        root.addView(timer,FrameLayout.LayoutParams(dp(50),dp(40),Gravity.TOP or Gravity.END).apply {topMargin=dp(20);rightMargin=dp(88)})
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        repeat(20) { group ->
            content.addView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL
                repeat(12) {n -> addView(TextView(context).apply {text="内容 $group/$n"},LinearLayout.LayoutParams(-1,1))}
            },LinearLayout.LayoutParams(-1,dp(6)))
        }
        root.addView(content,0,FrameLayout.LayoutParams(-1,dp(144),Gravity.CENTER))
        setContentView(root)
        if(!login) {
            val started=android.os.SystemClock.elapsedRealtime()
            fun tick() {
                if(ended) return
                val left=8-((android.os.SystemClock.elapsedRealtime()-started)/1000).toInt()
                if(left<=0) home() else {timer.text="${left}秒";handler.postDelayed({tick()},1000)}
            }
            tick()
        }
    }
    private fun home() {
        if(ended) return
        ended=true;handler.removeCallbacksAndMessages(null)
        val root=FrameLayout(this).apply {id=R.id.home_root;setBackgroundColor(Color.WHITE)}
        root.addView(TextView(this).apply {text="首页";textSize=28f;gravity=Gravity.CENTER},
            FrameLayout.LayoutParams(280,150,Gravity.BOTTOM or Gravity.START).apply {bottomMargin=90})
        root.addView(TextView(this).apply {text="我的";textSize=28f;gravity=Gravity.CENTER},
            FrameLayout.LayoutParams(280,150,Gravity.BOTTOM or Gravity.END).apply {bottomMargin=90})
        setContentView(root)
        root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    /** No accessible controls and no words saying advertisement or skip. */
    private inner class ImageAdvertisement : View(this@FixtureActivity) {
        private var remaining=8
        private var showing=true
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
            fun tick() {if(showing) {remaining--;if(remaining<=0) {showing=false};invalidate();this@FixtureActivity.handler.postDelayed({tick()},1000)}}
            this@FixtureActivity.handler.postDelayed({tick()},1000)
        }
        override fun onDraw(c: android.graphics.Canvas) {
            c.drawColor(if(showing) Color.rgb(33,74,112) else Color.WHITE)
            paint.style=android.graphics.Paint.Style.FILL
            if(showing) {
                paint.color=Color.rgb(245,225,171);c.drawCircle(width*.45f,height*.43f,width*.28f,paint)
                paint.color=Color.rgb(180,90,90);c.drawRect(width*.55f,height*.45f,width*.85f,height*.72f,paint)
                paint.color=Color.WHITE;paint.strokeWidth=7f
                c.drawLine(width*.88f,height*.05f,width*.93f,height*.09f,paint)
                c.drawLine(width*.93f,height*.05f,width*.88f,height*.09f,paint)
                paint.textSize=52f;c.drawText("${remaining}秒",width*.7f,height*.08f,paint)
            } else {
                paint.color=Color.BLACK;paint.textSize=64f
                c.drawText("首页",width*.13f,height*.9f,paint);c.drawText("我的",width*.7f,height*.9f,paint)
            }
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if(showing && e.action==android.view.MotionEvent.ACTION_UP && e.x>width*.8f && e.y<height*.14f) {
                val file=File(filesDir,"clicks");val n=file.takeIf {it.exists()}?.readText()?.toIntOrNull() ?: 0
                file.writeText((n+1).toString());showing=false;invalidate()
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            return true
        }
    }
    override fun onDestroy() {handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
