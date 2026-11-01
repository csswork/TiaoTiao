package com.haibo.tiaotiao.ui

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.haibo.tiaotiao.R

/** Canvas text deliberately exposes no readable accessibility labels: exercises the actual OCR path. */
class SmartDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(AdCanvas()) }
    private inner class AdCanvas : View(this@SmartDemoActivity) {
        private var ad = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { id = R.id.smart_demo_ad; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun onDraw(c: Canvas) {
            c.drawColor(if (ad) Color.rgb(230,240,234) else Color.WHITE)
            paint.color=Color.BLACK;paint.textSize=48f;paint.typeface=Typeface.DEFAULT_BOLD
            if (ad) {
                c.drawText("跳过 5秒",width*.74f,height*.08f,paint)
                c.drawText("广告",width*.42f,height*.22f,paint)
                paint.textSize=64f;c.drawText("智能视觉演示",width*.18f,height*.48f,paint)
            } else {
                c.drawText("首页",width*.15f,height*.35f,paint)
                c.drawText("我的",width*.65f,height*.35f,paint)
                c.drawText("广告已关闭",width*.25f,height*.55f,paint)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action==MotionEvent.ACTION_UP && ad && e.x>width*.70 && e.y<height*.12) {
                ad=false;id=R.id.smart_demo_home;invalidate();sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                return true
            }
            return true
        }
    }
}
