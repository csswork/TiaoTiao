package com.haibo.tiaotiao

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.haibo.tiaotiao.data.AppRepository
import com.haibo.tiaotiao.data.AppLabels
import com.haibo.tiaotiao.ui.MainActivity
import com.haibo.tiaotiao.ui.DemoActivity
import com.haibo.tiaotiao.ui.SmartDemoActivity
import java.lang.ref.WeakReference

class TiaoApplication : Application() {
    enum class ForegroundScreen { OTHER, DASHBOARD, DEMO, VISUAL_DEMO }
    @Volatile var foregroundScreen=ForegroundScreen.OTHER
        private set
    val repository by lazy { AppRepository(this) }
    val appLabels by lazy { AppLabels(this) }
    val appClassification by lazy { com.haibo.tiaotiao.data.AppClassification(this, repository) }
    @Volatile private var learner: com.haibo.tiaotiao.data.VisualLearner?=null
    var visualLearner: com.haibo.tiaotiao.data.VisualLearner
        get()=learner ?: com.haibo.tiaotiao.data.VisualLearner(this,repository).also {learner=it}
        internal set(value) {learner=value}
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumed=WeakReference<Activity>(null)
            override fun onActivityResumed(activity: Activity) {
                resumed=WeakReference(activity)
                foregroundScreen=when(activity) {
                    is MainActivity -> ForegroundScreen.DASHBOARD
                    is DemoActivity -> ForegroundScreen.DEMO
                    is SmartDemoActivity -> ForegroundScreen.VISUAL_DEMO
                    else -> ForegroundScreen.OTHER
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if(resumed.get()===activity) {foregroundScreen=ForegroundScreen.OTHER;resumed.clear()}
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
