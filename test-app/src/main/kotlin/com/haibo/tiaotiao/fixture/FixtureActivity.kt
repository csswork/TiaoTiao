package com.haibo.tiaotiao.fixture

import android.app.Activity
import android.content.Intent
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
        if(intent.getBooleanExtra("canvas",false)) {imagePage(intent.getBooleanExtra("inert",false));return}
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
        if(intent.getBooleanExtra("new_window",false)) {
            startActivity(Intent(this,FixtureHomeActivity::class.java));finish()
        } else renderFixtureHome(this)
    }
    private fun imagePage(inert: Boolean) {
        val image=ImageAdvertisement(inert)
        val root=FrameLayout(this).apply {addView(image,FrameLayout.LayoutParams(-1,-1))}
        if(inert) {
            val close=object : Button(this) {
                override fun performAccessibilityAction(action: Int,args: Bundle?): Boolean {
                    if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                        val file=File(filesDir,"native_attempts")
                        file.writeText(((file.takeIf {it.exists()}?.readText()?.toIntOrNull() ?: 0)+1).toString())
                        return true // Some ad SDKs accept accessibility clicks but only handle touch.
                    }
                    return super.performAccessibilityAction(action,args)
                }
            }.apply {
                text="";contentDescription="关闭";setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {image.closeFromTouch();visibility=View.GONE}
            }
            val m=resources.displayMetrics
            root.addView(close,FrameLayout.LayoutParams((m.widthPixels*.11f).toInt(),(m.heightPixels*.07f).toInt(),Gravity.TOP or Gravity.END).apply {
                rightMargin=(m.widthPixels*.04f).toInt();topMargin=(m.heightPixels*.035f).toInt()
            })
        }
        setContentView(root)
    }
    /** Optional inert accessibility target; actual control always handles touch. */
    private inner class ImageAdvertisement(private val otherClose: Boolean) : View(this@FixtureActivity) {
        private var remaining=intent.getIntExtra("ad_seconds",8).coerceIn(1,60)
        private var showing=true
        private var firstTouchIgnored=false
        private val ignoreFirst=intent.getBooleanExtra("ignore_first_touch",false)
        private val localSkip=intent.getBooleanExtra("local_skip",false)
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
                if(!ignoreFirst && !localSkip) {
                    c.drawLine(width*.88f,height*.05f,width*.93f,height*.09f,paint)
                    c.drawLine(width*.93f,height*.05f,width*.88f,height*.09f,paint)
                }
                if(ignoreFirst || localSkip) {
                    paint.textSize=40f;c.drawText("广告",width*.04f,height*.07f,paint)
                    c.drawText(if(localSkip) "跳过" else "关闭",width*.86f,height*.08f,paint)
                }
            } else {
                paint.color=Color.BLACK;paint.textSize=64f
                c.drawText("首页",width*.13f,height*.9f,paint);c.drawText("我的",width*.7f,height*.9f,paint)
                if(otherClose) {
                    paint.strokeWidth=7f
                    c.drawLine(width*.88f,height*.05f,width*.93f,height*.09f,paint)
                    c.drawLine(width*.93f,height*.05f,width*.88f,height*.09f,paint)
                }
            }
        }
        fun closeFromTouch() {
            if(!showing) return
            val file=File(filesDir,"clicks");val n=file.takeIf {it.exists()}?.readText()?.toIntOrNull() ?: 0
            file.writeText((n+1).toString());showing=false;invalidate()
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if(intent.getBooleanExtra("record_touch",false) && e.action in setOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                File(filesDir,"touch_events").appendText("${e.action},${e.rawX},${e.rawY},${e.flags}\n")
            }
            if(intent.getBooleanExtra("reject_a11y_touch",false) && e.flags and 0x800!=0) return true
            if(showing && e.action==android.view.MotionEvent.ACTION_UP && e.x>width*.8f && e.y<height*.14f) {
                if(ignoreFirst && !firstTouchIgnored) {
                    firstTouchIgnored=true;File(filesDir,"ignored_touch").writeText("1");return true
                }
                closeFromTouch()
            }
            return true
        }
    }
    override fun onDestroy() {handler.removeCallbacksAndMessages(null);super.onDestroy()}
}

class FixtureHomeActivity : Activity() {
    override fun onCreate(state: Bundle?) {super.onCreate(state);renderFixtureHome(this)}
}

private fun renderFixtureHome(activity: Activity) {
    val root=FrameLayout(activity).apply {id=R.id.home_root;setBackgroundColor(Color.WHITE)}
    root.addView(TextView(activity).apply {text="首页";textSize=28f;gravity=Gravity.CENTER},
        FrameLayout.LayoutParams(280,150,Gravity.BOTTOM or Gravity.START).apply {bottomMargin=90})
    root.addView(TextView(activity).apply {text="我的";textSize=28f;gravity=Gravity.CENTER},
        FrameLayout.LayoutParams(280,150,Gravity.BOTTOM or Gravity.END).apply {bottomMargin=90})
    activity.setContentView(root)
    root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
}
