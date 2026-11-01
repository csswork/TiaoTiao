package com.haibo.tiaotiao.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.haibo.tiaotiao.R
import com.haibo.tiaotiao.service.SkipAccessibilityService

class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showAd(restart = false) }
    private fun showAd(restart: Boolean = true) {
        if (restart) SkipAccessibilityService.instance?.restartSession()
        setContentView(LinearLayout(this).apply {
            id = R.id.demo_ad; orientation = LinearLayout.VERTICAL
            setPadding(36, 72, 36, 72); setBackgroundColor(Color.rgb(228, 239, 232))
            addView(Button(context).apply { id = R.id.demo_skip; text = "跳过 5"; setOnClickListener { showHome() } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            addView(TextView(context).apply { text = "广告"; textSize = 16f; gravity = Gravity.CENTER })
            addView(TextView(context).apply { text = "一小步，\n跳过等待。"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(Color.rgb(39, 103, 81)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(context).apply { text = "本地演示页面 · 无需 Jev 密钥\n首次试跳并确认，再打开验证自动复用。"; textSize = 16f; gravity = Gravity.CENTER })
        })
    }
    private fun showHome() {
        setContentView(LinearLayout(this).apply {
            id = R.id.demo_home; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(40, 80, 40, 80); setBackgroundColor(Color.WHITE)
            addView(TextView(context).apply { text = "已进入演示首页"; textSize = 30f })
            addView(TextView(context).apply { text = "可在底部浮层确认刚才的结果。"; textSize = 16f })
            addView(Button(context).apply { id = R.id.demo_login_skip; text = "跳过登录"; setOnClickListener { } })
            addView(Button(context).apply { text = "再看一次模拟开屏"; setOnClickListener { showAd() } })
            addView(Button(context).apply { text = "返回 TiaoTiao"; setOnClickListener { finish() } })
        })
    }
}
