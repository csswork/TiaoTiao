package com.haibo.tiaotiao.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.haibo.tiaotiao.TiaoApplication
import com.haibo.tiaotiao.core.*
import com.haibo.tiaotiao.network.JevClient
import com.haibo.tiaotiao.network.modelErrorMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.haibo.tiaotiao.touch.routeTouch
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference

class SkipAccessibilityService : AccessibilityService() {
    companion object {
        internal const val GESTURE_PRESS_MS=100L
        /** MIUI/HyperOS asks before one app opens another; shake/flip launch ads trigger it without any touch. */
        private const val WAKEPATH_PACKAGE="com.miui.securitycenter"
        private const val JUMP_WINDOW_MS=15_000L
        private const val SENSOR_PAUSE_MS=10_000L
        @Volatile private var serviceRef = WeakReference<SkipAccessibilityService>(null)
        val instance: SkipAccessibilityService? get() = serviceRef.get()
    }
    private val repository get() = (application as TiaoApplication).repository
    private val touchJournal get()=(application as TiaoApplication).touchJournal
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = SessionGate()
    private val checkBubble by lazy {CheckBubble(this,scope)}
    private var pairingAssistant: com.haibo.tiaotiao.touch.WirelessPairingAssistant?=null
    private var bubbleSession: Pair<String,Long>?=null
    internal val diagnosticBubbleVisible get()=checkBubble.visible
    internal fun bubbleBounds()=checkBubble.bounds
    internal val diagnosticBubbleShows get()=checkBubble.shows
    internal fun checkStatus(pkg: String,text: String,finished: Boolean=false) {
        if(text!="正在检查" || finished) return
        if(!repository.settings.value.showCheckBubble || dashboardVisible() ||
            !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            knownOwner?.second!=pkg) return
        checkBubble.show("TiaoTiao 正在检查",1200)
    }
    private val jev by lazy { JevClient() }
    private var scanJob: Job? = null
    private var overlay: LinearLayout? = null
    private var overlayRuleId: String? = null
    private var overlayIsResult = false
    private var expireJob: Job? = null
    private var diagnosticPackage: String? = null
    private var eventWindowId: Int? = null
    private var targetEventPackage: String? = null
    private var targetEventAt = 0L
    private var lastReadState = "尚未读取界面"
    private var autoLearning: AutoLearning? = null
    private var knownOwner: Pair<Int,String>? = null
    private var ownerRevision=0L
    private var ownerProbe: Job?=null
    private var nodeRead: Job?=null
    private var nodeSnapshot: ScreenSnapshot?=null
    private var nodeObservation: Observation?=null
    private var nodeSnapshotAt=0L
    private var nextNodeRead=0L
    private var cachedVersion: Pair<String,Long>?=null
    @Volatile private var tracePackage: String?=null
    private var traceSnapshotPending=false
    @Synchronized fun claimTraceSnapshot(pkg: String): Boolean {
        if(tracePackage!=pkg || !traceSnapshotPending) return false
        traceSnapshotPending=false;return true
    }
    @Synchronized fun claimTrace(pkg: String): Boolean {
        if(tracePackage!=pkg) return false
        tracePackage=null;return true
    }
    @Volatile var diagnosticTreeReads=0L
        private set
    private var ignoredDashboardEvents=0L
    private var resettingResults=false
    @Volatile internal var diagnosticHeldPackage: String?=null
        private set
    private val touchLane=Mutex()
    @Volatile internal var touchExecutions: List<com.haibo.tiaotiao.touch.TouchExecution> = emptyList()
        private set
    private fun dashboardVisible()=(application as TiaoApplication).foregroundScreen==TiaoApplication.ForegroundScreen.DASHBOARD
    private fun stopDashboardScan() {
        scanJob?.cancel();nodeRead?.cancel();gate.observe(null,SystemClock.elapsedRealtime());hideOverlay();checkBubble.hide()
        targetEventPackage=null;eventWindowId=null
        knownOwner=null;ownerRevision++;clearNodes()
        lastReadState="管理界面不参与广告识别"
    }
    private fun visualDemo(screen: ScreenSnapshot) = screen.packageName == packageName &&
        ((application as TiaoApplication).foregroundScreen==TiaoApplication.ForegroundScreen.VISUAL_DEMO || screen.nodes.any { it.resourceId.endsWith(":id/smart_demo_ad") })
    private fun learning() = autoLearning ?: AutoLearning(this,
        currentWindow = { visualWindow() },
        current = {
            // A just-closed splash window can remain in the accessibility cache; querying its
            // root on the main thread otherwise waits for a framework timeout before seeing home.
            visualWindow()
        },
        open = { generation, pkg -> repository.settings.value.enabled && gate.generation == generation && gate.isOpen(pkg,SystemClock.elapsedRealtime()) },
        tap = { generation, screen, area, rule, retry -> smartTap(generation,screen,area,rule,retry) }).also { autoLearning = it }
    /** The selected app's launch session: generation, package and start time. Activity classes it showed since. */
    private var launchSession: Triple<Long,String,Long>?=null
    private val launchClasses=mutableListOf<Pair<String,Long>>()
    private var jumpUntil=0L
    private var jumpBack: Job?=null
    private var jumpBackDone=false
    private var jumpRecorded: Long?=null
    private var jumpRecovered: Long?=null
    /** Evidence that decides whether a jump was the ad's doing: a launch ad was seen in this session, and the user
     * did not click anything in the app (clicks within a second of TiaoTiao's own touch are TiaoTiao's). */
    private var launchAdSeen=false
    private var userClickedAt=0L
    private var tiaoTouchAt=0L
    internal fun noteLaunchAd(pkg: String) {if(launchSession?.second==pkg) launchAdSeen=true}
    private fun adCausedJump(source: String,kind: String): Boolean {
        val session=launchSession ?: return false
        val reason=when {
            !launchAdSeen -> "no_launch_ad"
            userClickedAt>=session.third -> "user_clicked"
            else -> return true
        }
        touchJournal.record("JUMP","jump_ignored_$reason",source,details=mapOf("kind" to kind))
        return false
    }
    /** Newest activity class per selected app; unlike launchClasses it survives the app's session being replaced. */
    private val lastClass=mutableMapOf<String,String>()
    private fun sessionObserved(pkg: String) {
        if(pkg==packageName || pkg !in repository.settings.value.packages || launchSession?.first==gate.generation) return
        launchSession=Triple(gate.generation,pkg,SystemClock.elapsedRealtime());launchClasses.clear();jumpBackDone=false;launchAdSeen=false
        (application as TiaoApplication).visualLearner.warm(pkg)
        val touch=(application as TiaoApplication).enhancedTouch
        // Prevention needs the shell service; refusing the dialog afterwards (below) works without it.
        if(repository.adJumps.known(pkg) && repository.settings.value.enhancedTouch && touch.ownedReady) scope.launch {
            if(touch.pauseSensors(pkg,SENSOR_PAUSE_MS)) repository.log(pkg,"此应用的开屏广告曾自行跳转，开屏 ${SENSOR_PAUSE_MS/1000} 秒内已暂停其传感器")
        }
    }
    private suspend fun label(pkg: String)=withContext(Dispatchers.IO) {runCatching {
        @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg,0).loadLabel(packageManager).toString()
    }.getOrNull()}
    /** A landing page is the newest activity of the app, opened shortly before the jump and after its launch screen. */
    private fun landingClass(now: Long)=launchClasses.lastOrNull()?.takeIf {now-it.second<4000 && launchClasses.any {c ->c.first!=it.first}}?.first
    private fun recordJump(source: String,target: String,how: String) {
        // An ad may ask twice in one launch; count the launch once.
        if(jumpRecorded==launchSession?.first) return
        jumpRecorded=launchSession?.first
        repository.adJumps.record(source,target)
        repository.reportDetection(source,"开屏广告未经点击就要打开「$target」（摇一摇/翻转类广告），$how")
    }
    private fun interceptAdJump() {
        val (_,source,startedAt)=launchSession ?: return
        val now=SystemClock.elapsedRealtime()
        if(now-startedAt>JUMP_WINDOW_MS) return
        // A "微信登录"-style open the user asked for must be allowed, not refused.
        if(!adCausedJump(source,"dialog")) return
        jumpUntil=now+3000
        val landing=landingClass(now)
        scope.launch {
            val label=label(source) ?: return@launch
            // A second dialog may stack on one that is still closing; retry briefly instead of giving up.
            var refusal: Pair<String,android.graphics.Rect?>?=null
            for(attempt in 0 until 8) {
                delay(if(attempt==0) 150 else 200)
                refusal=withContext(Dispatchers.IO) {refuseJump(label)}
                if(refusal!=null) break
            }
            val (target,fallback)=refusal ?: run {repository.log(source,"出现应用跳转确认，但来源不是「$label」或找不到“拒绝”，未处理");return@launch}
            fallback?.let {dispatchTouch(it.centerX(),it.centerY())}
            touchJournal.record("JUMP","ad_jump_refused",source,details=mapOf("target" to target,"landing" to landing.orEmpty()))
            recordJump(source,target,"已自动拒绝")
            leaveLanding(source,label)
        }
    }
    /** Without a system dialog (target already running) the other app simply comes to the front. Bring the launch app
     * back; the caller has checked that it was in front just before and is within its launch window. */
    private fun recoverDirectJump(source: String,targetPackage: String) {
        if(jumpRecovered==launchSession?.first) return
        jumpRecovered=launchSession?.first
        val landing=landingClass(SystemClock.elapsedRealtime())
        val seen=launchClasses.joinToString(">") {it.first.substringAfterLast('.')}
        jumpUntil=SystemClock.elapsedRealtime()+6000
        scope.launch {
            val label=label(source) ?: return@launch
            val target=label(targetPackage) ?: targetPackage
            val launch=packageManager.getLaunchIntentForPackage(source)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) ?: return@launch
            // Deep links route through a loading page and then take the front again; let that settle, then verify.
            for(attempt in 0 until 2) {
                delay(if(attempt==0) 700 else 1500)
                val front=withContext(Dispatchers.IO) {runCatching {selectedWindow(windows)?.root?.packageName?.toString()}.getOrNull()}
                if(attempt>0 && front==source) break
                runCatching {startActivity(launch)}.onFailure {repository.log(source,"开屏广告打开了「$target」，未能返回「$label」");return@launch}
                touchJournal.record("JUMP","ad_jump_returned",source,details=mapOf("target" to targetPackage,"landing" to landing.orEmpty(),"seen" to seen,"attempt" to attempt.toString()))
            }
            jumpUntil=SystemClock.elapsedRealtime()+1500
            recordJump(source,target,"已返回「$label」")
            leaveLanding(source,label)
        }
    }
    private fun leaveLanding(source: String,label: String) {
        if(jumpBackDone) return
        jumpBack?.cancel()
        jumpBack=scope.launch {
            delay(900)
            val stillDialog=withContext(Dispatchers.IO) {windows.any {w -> runCatching {w.root?.packageName==WAKEPATH_PACKAGE}.getOrDefault(false)}}
            if(stillDialog || jumpBackDone || knownOwner?.second!=source) return@launch
            // Activity events are unreliable here, so look: an ad landing page lacks the app's bottom navigation.
            val home=if(android.os.Build.VERSION.SDK_INT>=30) learning().homeVisible() else null
            touchJournal.record("JUMP",if(home==false) "landing_left" else "landing_kept",source,details=mapOf("home" to home.toString()))
            if(home!=false) return@launch
            jumpBackDone=true
            performGlobalAction(GLOBAL_ACTION_BACK)
            repository.log(source,"已从广告落地页返回「$label」")
        }
    }
    /** The launch app itself handed the screen to another app (not the launcher) inside its launch window. */
    private fun checkDirectJump(previousOwner: String?,owner: String) {
        // TiaoTiao's own return to the launch app (and the jump target's brief session) is not another jump.
        val session=launchSession ?: return
        if(previousOwner==session.second && SystemClock.elapsedRealtime()<jumpUntil) {
            touchJournal.record("JUMP","jump_check_suppressed",session.second,details=mapOf("owner" to owner));return
        }
        if(previousOwner!=session.second || SystemClock.elapsedRealtime()-session.third>JUMP_WINDOW_MS || owner in homePackages ||
            // System choosers, permission prompts and security dialogs are the app's own flow, not an ad jump.
            owner in setOf("android","com.android.systemui",WAKEPATH_PACKAGE,packageName) || owner.contains("permissioncontroller") ||
            owner.startsWith("com.miui.") || owner.startsWith("com.xiaomi.")) return
        if(adCausedJump(session.second,"direct")) recoverDirectJump(session.second,owner)
    }
    private val homePackages by lazy {
        packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),0)
            .map {it.activityInfo.packageName}.toSet()
    }
    /** Clicks "拒绝" only when a dialog names the launch app as the one asking. Returns the requested app's name,
     * plus the button bounds when ACTION_CLICK was not accepted and a gesture is needed instead. */
    private fun refuseJump(label: String): Pair<String,android.graphics.Rect?>? {
        for(root in windows.mapNotNull {runCatching {it.root}.getOrNull()}.filter {it.packageName==WAKEPATH_PACKAGE}) {
            val title=root.findAccessibilityNodeInfosByText("想要打开").firstOrNull()?.text?.toString()?.trim() ?: continue
            if(!title.startsWith(label)) continue
            val target=title.substringAfter("想要打开").substringBefore('，').substringBefore(',').trim().ifBlank {"其他应用"}
            val refuse=root.findAccessibilityNodeInfosByText("拒绝").firstOrNull {it.text?.toString()?.trim()=="拒绝" && it.isClickable && it.isVisibleToUser} ?: continue
            if(refuse.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return target to null
            return target to android.graphics.Rect().also(refuse::getBoundsInScreen)
        }
        return null
    }
    private var screenReceiver=false
    // The foreground package does not change across screen-off/unlock, but its hot-start splash is a new session.
    private val screenOff=object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            scanJob?.cancel();nodeRead?.cancel();gate.reset();hideOverlay();checkBubble.hide();bubbleSession=null
            clearNodes();nextNodeRead=0
        }
    }
    private val reportedReasons = mutableSetOf<String>()
    private fun report(pkg: String, reason: String, message: String) {
        if (diagnosticPackage != pkg) { diagnosticPackage = pkg; reportedReasons.clear() }
        if (reportedReasons.add(reason)) repository.reportDetection(pkg, message)
    }

    override fun onServiceConnected() {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
            diagnosticHeldPackage=getSharedPreferences("touch_diagnostic_controls",MODE_PRIVATE).getString("heldPackage",null)
        serviceRef = WeakReference(this)
        repository.connected.value = true
        repository.log("", "无障碍服务已连接")
        if(!screenReceiver) {
            ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver=true
        }
        pairingAssistant?.close()
        pairingAssistant=com.haibo.tiaotiao.touch.WirelessPairingAssistant(this,scope,(application as TiaoApplication).wirelessBootstrap)
        (application as TiaoApplication).visualLearner.resume()
        if(android.os.Build.VERSION.SDK_INT>=30) learning().warmUp()
        scope.launch {
            var previous: com.haibo.tiaotiao.data.AppSettings?=null
            repository.settings.collectLatest { settings ->
                val old=previous;previous=settings
                if(!settings.showCheckBubble || !settings.enabled) checkBubble.hide()
                if(old==null || old.enabled!=settings.enabled || old.automaticLearning!=settings.automaticLearning || old.packages!=settings.packages) {
                    scanJob?.cancel()
                    nodeRead?.cancel();clearNodes();nextNodeRead=0;cachedVersion=null
                    gate.reset();hideOverlay()
                    if(settings.enabled) scheduleScan()
                } else if(settings.enabled && targetEventPackage!=null) scheduleScan()
            }
        }
    }
    /** Health signals read by the dashboard: the last event of any kind, and the last screenshot failure. */
    @Volatile var lastEventAt=0L
        private set
    @Volatile internal var captureFailure: Pair<Long,String>?=null
    private var captureFailures=0
    /** One timeout while a splash window disappears is normal; only repeated failures with no success are reported. */
    internal fun noteCapture(failure: String?) {
        if(failure==null) {captureFailures=0;captureFailure=null;return}
        if(++captureFailures>=3) captureFailure=SystemClock.elapsedRealtime() to failure
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt=SystemClock.elapsedRealtime()
        if (event == null || resettingResults || !repository.settings.value.enabled) return
        if((application as TiaoApplication).wirelessBootstrap.state.value.assisting) {
            stopDashboardScan()
            lastReadState="本机配对辅助期间暂停广告识别"
            return
        }
        val eventPackage = event.packageName?.toString().orEmpty()
        if(event.eventType==AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val now=SystemClock.elapsedRealtime()
            if(eventPackage==launchSession?.second && now-tiaoTouchAt>1000) userClickedAt=now
            return
        }
        if(eventPackage==WAKEPATH_PACKAGE && event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString()?.contains("wakepath")==true) {
            // A system "app wants to open another app" dialog is not the launch session leaving its app.
            interceptAdJump();return
        }
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.className?.toString()?.let {
            if(eventPackage==launchSession?.second) {launchClasses+=it to SystemClock.elapsedRealtime();if(launchClasses.size>12) launchClasses.removeAt(0)}
            if(eventPackage in repository.settings.value.packages) lastClass[eventPackage]=it
        }
        if(dashboardVisible()) {
            ignoredDashboardEvents++
            stopDashboardScan()
            return
        }
        if (eventPackage in repository.settings.value.packages) {
            targetEventPackage = eventPackage
            targetEventAt = SystemClock.elapsedRealtime()
        }
        val appWindow=selectedWindow(windows)
        if(appWindow!=null && appWindow.id==event.windowId && eventPackage.isNotBlank() &&
            (event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventPackage in repository.settings.value.packages ||
                (eventPackage==packageName && (application as TiaoApplication).foregroundScreen==TiaoApplication.ForegroundScreen.VISUAL_DEMO))) {
            if(knownOwner!=(appWindow.id to eventPackage)) {
                if(knownOwner?.second!=eventPackage) {autoLearning?.leaving(eventPackage);scanJob?.cancel()}
                nodeRead?.cancel();nextNodeRead=0;clearNodes();cachedVersion=null
            }
            val previousOwner=knownOwner?.second
            knownOwner=appWindow.id to eventPackage;ownerRevision++
            if(eventPackage in repository.settings.value.packages || eventPackage==packageName) {
                eventWindowId=appWindow.id
                if(targetEventPackage!=eventPackage) cachedVersion=null
                targetEventPackage=eventPackage;targetEventAt=SystemClock.elapsedRealtime()
                // Ad jumps usually land in shopping apps that are themselves selected (JD, Taobao); check before
                // the target's own launch session replaces the source's.
                if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && previousOwner!=eventPackage) checkDirectJump(previousOwner,eventPackage)
                gate.observeWindow(eventPackage,true,targetEventAt)
                sessionObserved(eventPackage)
            } else {
                if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) checkDirectJump(previousOwner,eventPackage)
                autoLearning?.leaving(eventPackage)
                scanJob?.cancel();gate.observeWindow(eventPackage,true,SystemClock.elapsedRealtime());hideOverlay();checkBubble.hide()
                targetEventPackage=null;clearNodes();cachedVersion=null
                diagnosticPackage=null;reportedReasons.clear()
                return
            }
        }
        // Foreground changes must cancel a request immediately rather than waiting for its result.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = eventPackage
            eventWindowId = event.windowId.takeIf { it >= 0 }
            if (pkg in repository.settings.value.packages) report(pkg, "event", "收到目标应用窗口事件，等待可读取的开屏界面")
            if (pkg.isNotBlank() && pkg != packageName && pkg !in repository.settings.value.packages) {
                // Toasts/system-owned windows are not proof that the target application left the foreground.
                probeOwner()
            }
        }
        if(eventPackage.isNotBlank() && eventPackage!=packageName && eventPackage !in repository.settings.value.packages &&
            event.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && targetEventPackage==null) return
        scheduleScan()
    }
    private fun scheduleScan() {
        if(resettingResults) return
        if((application as TiaoApplication).wirelessBootstrap.state.value.assisting) return
        if(dashboardVisible() && targetEventPackage==null) return
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            // Retry temporarily missing roots/content even if the OEM sends no further content event.
            // This loop never extends the session or retries a dispatched click.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            try {
                do {
                    scan()
                    val now = SystemClock.elapsedRealtime()
                    val target = targetEventPackage
                    if (target == null || (target !in repository.settings.value.packages && target != packageName) || !repository.settings.value.enabled ||
                        now >= deadline || now - targetEventAt >= 10_000 ||
                        !gate.canAwait(target,now)) break
                    delay(160)
                } while (isActive)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report(gate.packageName.orEmpty(), "error", "检测未完成，已结束本轮；可重新打开应用重试") }
            finally {if(!gate.actionDispatched) targetEventPackage?.let {checkStatus(it,"本轮检查结束",true)}}
        }
    }
    fun restartSession() {
        scanJob?.cancel(); gate.reset(); hideOverlay();checkBubble.hide();bubbleSession=null
        targetEventPackage = null; eventWindowId = null
        diagnosticPackage = null; reportedReasons.clear()
        clearNodes();cachedVersion=null;nextNodeRead=0
    }
    private fun clearNodes() {nodeObservation?.close();nodeObservation=null;nodeSnapshot=null}
    private fun probeOwner() {
        if(ownerProbe?.isActive==true || dashboardVisible()) return
        val window=selectedWindow(windows) ?: return
        val revision=ownerRevision
        ownerProbe=scope.launch {
            val owner=try {withContext(Dispatchers.IO) {
                val root=if(android.os.Build.VERSION.SDK_INT>=33) window.getRoot(0) else window.root
                try {root?.packageName?.toString()} finally {@Suppress("DEPRECATION") root?.recycle()}
            }} catch(c: CancellationException) {throw c} catch(_: Exception) {null} ?: return@launch
            if(ownerRevision!=revision || selectedWindow(windows)?.id!=window.id) return@launch
            // Keep the launch session (and its landing-page record) while the jump dialog is being refused.
            if(owner==WAKEPATH_PACKAGE && SystemClock.elapsedRealtime()<jumpUntil) return@launch
            if(knownOwner?.second!=owner) {
                checkDirectJump(knownOwner?.second,owner)
                autoLearning?.leaving(owner);scanJob?.cancel();nodeRead?.cancel()
                clearNodes();cachedVersion=null;nextNodeRead=0
            }
            knownOwner=window.id to owner
            if(owner!=packageName && owner !in repository.settings.value.packages) {
                autoLearning?.leaving(owner);gate.observeWindow(owner,true,SystemClock.elapsedRealtime())
                scanJob?.cancel();targetEventPackage=null;clearNodes();cachedVersion=null;hideOverlay();checkBubble.hide()
                diagnosticPackage=null;reportedReasons.clear()
            } else {
                gate.observeWindow(owner,true,SystemClock.elapsedRealtime())
                targetEventPackage=owner;targetEventAt=SystemClock.elapsedRealtime();eventWindowId=window.id
                scheduleScan()
            }
        }
    }
    private fun readNodes(screen: ScreenSnapshot) {
        val now=SystemClock.elapsedRealtime()
        if(nodeRead?.isActive==true || now<nextNodeRead) return
        nextNodeRead=now+700
        val gen=gate.generation
        nodeRead=scope.launch {
            var owned: Observation?=null
            try {
            withContext(Dispatchers.IO) {
                val window=windows.firstOrNull {it.id==screen.windowId} ?: return@withContext null
                val root=(if(android.os.Build.VERSION.SDK_INT>=33) window.getRoot(0) else window.root) ?: return@withContext null
                if(root.packageName?.toString()!=screen.packageName) {
                    @Suppress("DEPRECATION") root.recycle();return@withContext null
                }
                if(dashboardVisible()) {@Suppress("DEPRECATION") root.recycle();return@withContext null}
                diagnosticTreeReads++
                owned=ScreenReader.read(root,screen.versionCode,screen.width,screen.height,160,150)
            }
            val observation=owned ?: return@launch
            val snapshot=observation.screen
            if(gate.generation!=gen || !gate.isOpen(screen.packageName,SystemClock.elapsedRealtime()) ||
                selectedWindow(windows)?.id!=screen.windowId || dashboardVisible()) return@launch
            clearNodes();nodeObservation=observation;owned=null
            nodeSnapshot=snapshot;nodeSnapshotAt=SystemClock.elapsedRealtime()
            lastReadState="后台读取 ${snapshot.nodes.size} 个节点，完整=${snapshot.complete}；截图检测独立进行"
            if(RuleEngine.resolveVerified(repository.rules.value,snapshot)!=VerifiedSceneLookup.Unknown ||
                repository.rules.value.any {it.packageName==snapshot.packageName && it.versionCode==snapshot.versionCode &&
                    it.status==RuleStatus.DISABLED && AdRecognition.matchesNode(it.target,snapshot)}) return@launch
            learning().inspectNodes(snapshot,gen)
            } catch(c: CancellationException) {throw c}
            catch(_: Exception) {report(screen.packageName,"node-read-failed","控件暂不可读取，画面识别继续运行")}
            finally {owned?.close()}
        }
    }
    private fun selectedWindow(available: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val id = WindowSelection.choose(available.map {
            WindowCandidate(it.id, it.type == AccessibilityWindowInfo.TYPE_APPLICATION, it.isFocused, it.isActive)
        }, eventWindowId)
        return available.firstOrNull { it.id == id }
    }
    private fun unreadable(reason: String, message: String) {
        lastReadState = message
        val target = targetEventPackage ?: return
        if (SystemClock.elapsedRealtime() - targetEventAt < 10_000) report(target, reason, message)
    }
    private fun observe(): Observation? {
        if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            gate.observeWindow(null, false, SystemClock.elapsedRealtime(), deviceLocked = true)
            unreadable("locked", "屏幕关闭或设备锁定，已暂停检测")
            return null
        }
        val availableWindows = windows
        val applicationWindow = selectedWindow(availableWindows)
        val root = (if (android.os.Build.VERSION.SDK_INT>=33) applicationWindow?.getRoot(0) ?: getRootInActiveWindow(0)
            else applicationWindow?.root ?: rootInActiveWindow) ?: run {
            unreadable("no-root", "系统尚未提供可读取的应用界面，正在短时重试（未调用 Jev）")
            return null
        }
        val pkg = root.packageName?.toString().orEmpty()
        if(pkg==packageName && (application as TiaoApplication).foregroundScreen !in setOf(TiaoApplication.ForegroundScreen.DEMO,TiaoApplication.ForegroundScreen.VISUAL_DEMO)) {
            @Suppress("DEPRECATION") root.recycle()
            if(dashboardVisible()) stopDashboardScan()
            return null
        }
        val belongsToApplication = availableWindows.any { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (belongsToApplication) {
            val oldPackage = gate.packageName
            gate.observeWindow(pkg, true, SystemClock.elapsedRealtime())
            if (oldPackage != pkg) { diagnosticPackage = null; reportedReasons.clear() }
        }
        if (pkg != packageName && pkg !in repository.settings.value.packages) {
            targetEventPackage = null
            lastReadState = "当前为未选择的应用"
            @Suppress("DEPRECATION") root.recycle()
            return null
        }
        val version = runCatching {
            @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(pkg, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrElse {
            lastReadState = "无法读取应用版本（${it.javaClass.simpleName}），未执行点击或请求 Jev"
            report(pkg, "version-unavailable", lastReadState)
            @Suppress("DEPRECATION") root.recycle()
            return null
        }
        val metrics = DisplayCoordinates.size(this)
        diagnosticTreeReads++
        return ScreenReader.read(root, version, metrics.x, metrics.y).also {
            lastReadState = "已读取 ${it.screen.nodes.size} 个节点，界面完整=${it.screen.complete}"
        }
    }
    private fun visualWindow(): ScreenSnapshot? {
        if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val window=selectedWindow(windows) ?: return null
        if(android.os.Build.VERSION.SDK_INT>=30 && window.displayId!=android.view.Display.DEFAULT_DISPLAY) return null
        val pkg=knownOwner?.takeIf {it.first==window.id}?.second ?: run {probeOwner();return null}
        val version=cachedVersion?.takeIf {it.first==pkg}?.second ?: runCatching { @Suppress("DEPRECATION") packageManager.getPackageInfo(pkg,0).let {
            if (android.os.Build.VERSION.SDK_INT>=28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        } }.getOrNull()?.also {cachedVersion=pkg to it} ?: return null
        val m=DisplayCoordinates.size(this)
        val nodes=nodeSnapshot?.takeIf {!gate.actionDispatched && it.packageName==pkg && it.versionCode==version && it.windowId==window.id && SystemClock.elapsedRealtime()-nodeSnapshotAt<900}
        // Metadata only: visual verification must not wait for every home-page node/provider.
        return ScreenSnapshot(pkg,version,m.x,m.y,nodes?.nodes.orEmpty(),System.currentTimeMillis(),window.id,complete=nodes?.complete==true)
    }
    private fun isDemo(screen: ScreenSnapshot) = screen.packageName == packageName && screen.nodes.any { it.resourceId.endsWith(":id/demo_skip") }
    private fun eligible(screen: ScreenSnapshot) = (screen.packageName in repository.settings.value.packages && screen.packageName != packageName) || isDemo(screen) || visualDemo(screen)

    private suspend fun scan() {
        if (!repository.settings.value.enabled) return
        if(android.os.Build.VERSION.SDK_INT>=30 && (repository.settings.value.automaticLearning ||
            (application as TiaoApplication).foregroundScreen==TiaoApplication.ForegroundScreen.VISUAL_DEMO)) {
            val screen=visualWindow()
            if(screen!=null && (visualDemo(screen) || AdRecognition.eligible(screen.packageName,repository.settings.value.packages,packageName,repository.settings.value.automaticLearning))) {
                val now=SystemClock.elapsedRealtime()
                gate.observe(screen.packageName,now)
                sessionObserved(screen.packageName)
                if(!gate.isOpen(screen.packageName,now)) return
                if(bubbleSession!=(screen.packageName to gate.generation)) {
                    bubbleSession=screen.packageName to gate.generation
                    checkStatus(screen.packageName,"正在检查")
                }
                report(screen.packageName,"begin",if(repository.settings.value.visionProvider==VisionProvider.DEEPSEEK_OFFICIAL)
                    "开屏检测已启动：DeepSeek 官方图片判断与端侧识别并行" else "开屏检测已启动：Jev 控件分析与端侧画面识别并行")
                readNodes(screen)
                if(repository.rules.value.any {it.packageName==screen.packageName && it.versionCode==screen.versionCode &&
                    it.status==RuleStatus.DISABLED && AdRecognition.matchesNode(it.target,screen)}) {
                    report(screen.packageName,"disabled-target","此目标已有用户停用记录，本轮不重新学习或点击")
                    return
                }
                // Existing manually verified rules retain their original stricter matching path.
                if(screen.complete) when(val local=RuleEngine.resolveVerified(repository.rules.value,screen)) {
                    is VerifiedSceneLookup.Matched -> {dispatch(local.rule,false);return}
                    VerifiedSceneLookup.UnmatchedTarget -> return
                    VerifiedSceneLookup.Unknown -> Unit
                }
                learning().scan(screen,gate.generation)
                return
            }
            if(screen==null) {
                targetEventPackage?.let {report(it,"await-owner","当前窗口尚不可读取，正在继续等待；未执行点击")}
                return
            }
            if(screen.packageName!=packageName ||
                (application as TiaoApplication).foregroundScreen!=TiaoApplication.ForegroundScreen.DEMO) return
        }
        val observation = observe()
        if (observation == null) {
            // A transient null root during a successful page transition must not discard the result question.
            if (!overlayIsResult || !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) hideOverlay()
            return
        }
        val screen = observation.use { it.screen }
        if (!eligible(screen)) {
            gate.observe(null, SystemClock.elapsedRealtime())
            val resultPackage = repository.rules.value.find { it.id == overlayRuleId }?.packageName
            if (!overlayIsResult || resultPackage != screen.packageName) hideOverlay()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (gate.packageName != screen.packageName) { diagnosticPackage = null; reportedReasons.clear() }
        gate.observe(screen.packageName, now)
        if (!gate.isOpen(screen.packageName, now)) {
            if (!gate.actionDispatched) report(screen.packageName, "timeout", "本轮开屏检测已结束；未识别到目标时不会点击，可在记录页查看原因")
            return
        }
        report(screen.packageName, "begin", "开始检测开屏界面")
        if (android.os.Build.VERSION.SDK_INT >= 30 && visualDemo(screen)) {
            if (visualDemo(screen)) { targetEventPackage = packageName; targetEventAt = now }
            learning().scan(screen,gate.generation)
            return
        }
        val fingerprint = screen.fingerprint()
        val rules = repository.rules.value
        when (val local = RuleEngine.resolveVerified(rules, screen)) {
            is VerifiedSceneLookup.Matched -> {
                report(screen.packageName, "local-match", "本地已验证规则匹配成功，未请求 Jev")
                dispatch(local.rule, false); return
            }
            VerifiedSceneLookup.UnmatchedTarget -> {
                report(screen.packageName, "local-wait", "已识别本地验证界面，等待目标位置匹配；本轮不重复请求 Jev")
                return
            }
            VerifiedSceneLookup.Unknown -> Unit
        }
        val existing = rules.filter { it.packageName == screen.packageName && it.screenFingerprint == fingerprint }
        // Disabled/ambiguous rules never trigger new AI proposals behind the user's back.
        if (existing.any { it.status == RuleStatus.DISABLED } || existing.count { it.isVerified() } > 1) {
            report(screen.packageName, "blocked-rule", "当前界面的规则已停用或存在冲突，未执行点击")
            return
        }
        existing.firstOrNull { it.status == RuleStatus.TRIALLED }?.let { showResult(it); return }
        existing.firstOrNull { it.status == RuleStatus.PENDING || it.status == RuleStatus.IMPORTED }?.let {
            if (RuleEngine.locate(it, screen) != null) showProposal(it)
            return
        }
        if (!screen.complete) {
            report(screen.packageName, "incomplete", "界面节点读取达到上限，本轮未点击；请反馈该应用以便适配")
            return
        }
        if (screen.candidates().none { SignalText.hasSkip(it.label) }) {
            report(screen.packageName, "no-target", "读取 ${screen.nodes.size} 个节点、${screen.candidates().size} 个可点击控件，尚未找到文字跳过按钮；图片按钮暂不支持")
            return
        }
        if (rules.any { it.packageName == screen.packageName && it.isVerified() }) {
            report(screen.packageName, "local-miss", "已有验证规则与当前界面不匹配，将按新界面处理")
        }
        val demo = isDemo(screen)
        val settings = repository.settings.value
        if (!demo && !settings.cloudEnabled) {
            report(screen.packageName, "cloud-off", "云端分析未开启，仅匹配已验证的本地规则")
            return
        }
        if (!demo && repository.vault.get("jevKey").isBlank()) {
            report(screen.packageName, "no-key", "未填写模型密钥，无法分析新界面，请到首页完成配置")
            return
        }
        if (!gate.claimRequest(fingerprint)) return
        if (!demo) repository.reportDetection(screen.packageName, "正在通过 ${settings.jevProvider.displayName} 分析跳过目标…")
        val candidate = if (demo) screen.candidates().singleOrNull { SignalText.hasSkip(it.label) } else try {
            withContext(Dispatchers.IO) { jev.select(screen, repository.vault.get("jevKey"), settings.model, settings.jevProvider) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = modelErrorMessage(error)
            repository.recordModelResponse(false, message)
            report(screen.packageName, "model-error", message)
            return
        }
        if (!demo) repository.recordModelResponse(true, "${settings.jevProvider.displayName} 已正常响应")
        if (candidate == null) { report(screen.packageName, "uncertain", "模型已响应，但未确认合适的跳过目标，本轮不处理"); return }
        val rule = RuleEngine.compile(screen, candidate, if (demo) "demo-local" else "${settings.jevProvider.name}:${settings.model}", System.currentTimeMillis())
        repository.put(rule)
        repository.addSample(rule, screen)
        report(screen.packageName, "proposal", if (demo) "演示方案待试跳；未请求云端模型" else "Jev 候选方案已保存，等待试跳与人工确认")
        val fresh = observe()?.use { it.screen }
        if (fresh != null && gate.isOpen(screen.packageName, SystemClock.elapsedRealtime()) && RuleEngine.locate(rule, fresh) != null) showProposal(rule)
        else repository.log(screen.packageName, "页面已变化或检测超时；候选保留，下次打开时试跳")
    }
    private fun dispatch(proposal: SkipRule, trial: Boolean) {
        hideOverlay()
        val current = repository.rules.value.find { it.id == proposal.id } ?: return
        if (current.behaviorDigest() != proposal.behaviorDigest() || !repository.settings.value.enabled) return
        if (!trial && !current.isVerified()) return
        if (trial && current.status !in setOf(RuleStatus.PENDING, RuleStatus.IMPORTED)) return
        val observation = observe() ?: return
        observation.use {
            if (!eligible(it.screen) || !gate.isOpen(it.screen.packageName, SystemClock.elapsedRealtime())) return
            val target = RuleEngine.locate(current, it.screen)
            if (target == null) { repository.log(current.packageName, "页面或目标已变化，取消点击"); return }
            gate.dispatched();tiaoTouchAt=SystemClock.elapsedRealtime()
            val accepted = it.handles[target.path]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (trial && accepted) {
                val updated = RuleEngine.recordTrial(current, true, System.currentTimeMillis())
                repository.put(updated)
                repository.log(current.packageName, "试跳已发起，等待你确认实际结果")
                showResult(updated)
            } else {
                repository.reportDetection(current.packageName, if (accepted) "已按验证规则发起跳过点击" else "系统未接受点击；未重试")
                if (accepted) observeClickEffect(current, gate.generation)
            }
        }
    }
    private suspend fun smartTap(generation: Long,screen: ScreenSnapshot,area: Area,rule: VisualRule,retry: Boolean=false): Boolean =
        touchLane.withLock {smartTapLocked(generation,screen,area,rule,retry)}
    private suspend fun smartTapLocked(generation: Long, screen: ScreenSnapshot, area: Area, rule: VisualRule,retry: Boolean): Boolean {
        fun reject(reason: String,details: Map<String,String> = emptyMap()): Boolean {
            touchJournal.record("PRECHECK",reason,screen.packageName,rule.id,details=details)
            repository.log(screen.packageName,"点击前取消：${com.haibo.tiaotiao.touch.touchReason(reason)}；方案=${rule.id.take(8)}")
            return false
        }
        val now=SystemClock.elapsedRealtime()
        if (!repository.settings.value.enabled || gate.generation != generation ||
            !(if(retry) gate.canRetry(screen.packageName,now) else gate.isOpen(screen.packageName,now))) return reject("session_expired")
        if(diagnosticHeldPackage==screen.packageName) return reject("diagnostic_hold")
        val fresh=visualWindow() ?: return reject("window_unavailable")
        if(fresh.windowId!=screen.windowId || AdRecognition.scene(fresh)!=AdRecognition.scene(screen) || !area.valid() ||
            area.right-area.left>.4f || area.bottom-area.top>.18f) return reject("scene_changed",mapOf("expectedWindow" to screen.windowId.toString(),"actualWindow" to fresh.windowId.toString()))
        if(!AdRecognition.eligible(screen.packageName,repository.settings.value.packages,packageName,repository.settings.value.automaticLearning) && !visualDemo(screen)) return reject("app_not_selected")
        val x=(area.left+area.right)/2;val y=(area.top+area.bottom)/2
        val pixelX=(x*screen.width).toInt();val pixelY=(y*screen.height).toInt()
        val bounds=android.graphics.Rect()
        selectedWindow(windows)?.takeIf {it.id==screen.windowId}?.getBoundsInScreen(bounds) ?: return reject("window_changed")
        if(!bounds.contains(pixelX,pixelY)) return reject("point_outside_window")
        val enhanced=(application as TiaoApplication).enhancedTouch
        val useEnhanced=repository.settings.value.enhancedTouch && enhanced.state.value==com.haibo.tiaotiao.touch.TouchConnection.READY
        val recentNodes=nodeObservation?.takeIf {it.screen.packageName==fresh.packageName && it.screen.windowId==fresh.windowId &&
            SystemClock.elapsedRealtime()-nodeSnapshotAt<1500}
        val nativeTarget=VisualPolicy.nativeTarget(rule,if(fresh.complete) fresh else recentNodes?.screen ?: fresh,area)
        // Node trees of launch ads are often rebuilt or hidden; the rule's learned bounds (already matched on the
        // current frame by the caller) still locate a coordinate touch, so a missing node no longer cancels the action.
        if(rule.tapMethod==TapMethod.ACCESSIBILITY && nativeTarget==null) touchJournal.record("PRECHECK","native_target_missing",screen.packageName,rule.id,details=mapOf(
            "freshComplete" to fresh.complete.toString(),"nodeAgeMs" to (now-nodeSnapshotAt).toString(),"recentNodes" to (recentNodes!=null).toString(),"fallback" to "coordinate"))
        if(rule.tapMethod==TapMethod.ACCESSIBILITY && nativeTarget!=null &&
            (x !in nativeTarget.bounds.left..nativeTarget.bounds.right || y !in nativeTarget.bounds.top..nativeTarget.bounds.bottom)) return reject("native_point_outside")
        // The first touch is always a coordinate touch (enhanced injection, else an accessibility gesture). Device trials
        // on two ad SDKs: the gesture closed every ad, while ACTION_CLICK was ignored by one of them. ACTION_CLICK on
        // the learned node remains the single guarded retry for node rules after the screen was observed unchanged.
        val node=if(retry && rule.tapMethod==TapMethod.ACCESSIBILITY) nativeTarget else null
        val handle=node?.let {recentNodes?.handles?.get(it.path)}?.let {
            @Suppress("DEPRECATION") AccessibilityNodeInfo.obtain(it)
        }
        if(handle!=null) {
            val started=SystemClock.elapsedRealtime()
            var valid=false
            val accepted=try {withContext(Dispatchers.IO) {
                val currentBounds=android.graphics.Rect()
                valid=handle.refresh() && handle.packageName?.toString()==screen.packageName && handle.windowId==screen.windowId &&
                    handle.viewIdResourceName.orEmpty()==nativeTarget?.resourceId.orEmpty() && handle.className?.toString()==nativeTarget?.className
                if(valid) {handle.getBoundsInScreen(currentBounds);valid=handle.isVisibleToUser && currentBounds.contains(pixelX,pixelY)}
                if(!valid) false else {gate.dispatched();tiaoTouchAt=SystemClock.elapsedRealtime();handle.performAction(AccessibilityNodeInfo.ACTION_CLICK)}
            }}
                finally {@Suppress("DEPRECATION") handle.recycle()}
            if(!valid) return reject("native_handle_changed")
            val requestId="native-${android.os.Process.myPid()}-$started"
            touchExecutions=(touchExecutions+com.haibo.tiaotiao.touch.TouchExecution(rule.id,"原生控件",pixelX,pixelY,
                screen.width,screen.height,0,started,accepted,"ACTION_CLICK",requestId,if(accepted) "native_accepted" else "native_rejected",SystemClock.elapsedRealtime()-started)).takeLast(8)
            touchJournal.record("ACTION",if(accepted) "native_accepted" else "native_rejected",screen.packageName,rule.id,
                requestId=requestId,details=mapOf("backend" to "native","x" to pixelX.toString(),"y" to pixelY.toString()))
            repository.reportDetection(screen.packageName,if(accepted) "已对 Jev 选中的原生控件发起关闭，等待结果检查" else "原生控件未接受关闭，已停止本轮")
            return accepted
        }
        // A skip label inside its clickable container is one control; only side-by-side controls are ambiguous.
        val under=AdRecognition.candidates(fresh).filter {x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom}.map {it.bounds}
        if(under.size>1 && VisualExecution.singleControl(under)==null) return reject("ambiguous_target")
        gate.dispatched();tiaoTouchAt=SystemClock.elapsedRealtime()
        val touchStarted=SystemClock.elapsedRealtime()
        val result=routeTouch(useEnhanced,
            system={enhanced.tap(screen.packageName,pixelX,pixelY,screen.width,screen.height)},
            accessibility={dispatchTouch(pixelX,pixelY)})
        val pressMs=if(result.backend=="内置系统触控") com.haibo.tiaotiao.touch.SystemTouchService.PRESS_MS else GESTURE_PRESS_MS
        val report=enhanced.attempt.value.takeIf {result.backend=="内置系统触控"}
        touchExecutions=(touchExecutions+com.haibo.tiaotiao.touch.TouchExecution(rule.id,result.backend,pixelX,pixelY,
            screen.width,screen.height,pressMs,touchStarted,result.completed,if(result.backend=="内置系统触控") enhanced.lastEngine else "dispatchGesture",
            report?.requestId.orEmpty(),report?.reason.orEmpty(),report?.elapsedMs ?: 0)).takeLast(8)
        touchJournal.record("DISPATCH",report?.reason ?: if(result.completed) "gesture_completed" else "gesture_failed",screen.packageName,rule.id,report?.requestId.orEmpty(),mapOf(
            "backend" to result.backend,"ruleMethod" to rule.tapMethod.name,"completed" to result.completed.toString(),"x" to pixelX.toString(),"y" to pixelY.toString()))
        repository.reportDetection(screen.packageName,"${result.backend}${if(result.backend=="内置系统触控") "（${enhanced.lastEngine}）" else ""}：屏幕 ${screen.width}×${screen.height}，实际触点 $pixelX,$pixelY，短点 $pressMs ms，"+
            if(result.completed) "系统执行完成，等待检查广告是否提前关闭" else "执行未完成：${com.haibo.tiaotiao.touch.touchReason(report?.reason ?: "gesture_failed")}；本轮不改用其他通道补点")
        return result.completed
    }
    private suspend fun dispatchTouch(x: Int,y: Int): Boolean=dispatchStroke(listOf(x.toFloat() to y.toFloat()),GESTURE_PRESS_MS)
    /** Debug-only comparison of input lanes on a live launch screen; never used by automatic detection. */
    @Volatile private var probeResult="none"
    private fun probeTap(spec: String) {
        val parts=spec.split(',');val mode=parts[0];val x=parts.getOrNull(1)?.toIntOrNull() ?: return;val y=parts.getOrNull(2)?.toIntOrNull() ?: return
        probeResult="$mode running"
        scope.launch {
            val started=SystemClock.elapsedRealtime()
            val outcome=when(mode) {
                "gesture" -> dispatchTouch(x,y).toString()
                "gesture-long" -> dispatchStroke(listOf(x-1f to y-1f,x+1f to y+1f),220).toString()
                "gesture-jitter" -> dispatchStroke(listOf(x-2f to y+1f,x+0f to y-1f,x+2f to y+0f,x+1f to y+2f),140).toString()
                "native", "nodes" -> withContext(Dispatchers.IO) {
                    val root=rootInActiveWindow ?: return@withContext "no-root"
                    val hits=mutableListOf<AccessibilityNodeInfo>();val bounds=android.graphics.Rect();var total=0
                    fun walk(n: AccessibilityNodeInfo,depth: Int) {
                        total++
                        n.getBoundsInScreen(bounds)
                        if(bounds.contains(x,y)) hits+=n
                        if(depth<40) for(i in 0 until n.childCount) n.getChild(i)?.let {walk(it,depth+1)}
                    }
                    walk(root,0)
                    val describe=hits.joinToString(" / ") {n -> n.getBoundsInScreen(bounds)
                        "${n.className?.toString()?.substringAfterLast('.')}[click=${n.isClickable},vis=${n.isVisibleToUser},${bounds.width()}x${bounds.height()},text=${n.text?.length ?: 0},desc=${n.contentDescription?.length ?: 0}]"}
                    if(mode=="nodes") "pkg=${root.packageName} total=$total hits=${hits.size}: $describe"
                    else hits.lastOrNull {it.isClickable}?.performAction(AccessibilityNodeInfo.ACTION_CLICK)?.toString() ?: "no-clickable: $describe"
                }
                else -> "unknown mode"
            }
            probeResult="$mode $x,$y -> $outcome (${SystemClock.elapsedRealtime()-started} ms)"
            repository.log("","诊断点击：$probeResult")
        }
    }
    private suspend fun dispatchStroke(points: List<Pair<Float,Float>>,durationMs: Long): Boolean = withContext(NonCancellable) {
        val path=Path().apply {moveTo(points[0].first,points[0].second);points.drop(1).forEach {lineTo(it.first,it.second)}}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,durationMs))
            .apply {if(android.os.Build.VERSION.SDK_INT>=30) setDisplayId(android.view.Display.DEFAULT_DISPLAY)}.build()
        withTimeoutOrNull(1500) {suspendCancellableCoroutine { c ->
            val accepted = dispatchGesture(gesture,object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (c.isActive) c.resumeWith(Result.success(true))
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (c.isActive) c.resumeWith(Result.success(false))
                }
            },null)
            if (!accepted && c.isActive) c.resumeWith(Result.success(false))
        }} ?: false
    }
    private fun observeClickEffect(rule: SkipRule, generation: Long) {
        scope.launch {
            delay(900)
            if (!repository.settings.value.enabled || gate.generation != generation) return@launch
            val observation = observe() ?: return@launch
            observation.use {
                if (gate.generation != generation || it.screen.packageName != rule.packageName) return@use
                repository.reportDetection(rule.packageName, if (RuleEngine.locate(rule, it.screen) != null)
                    "规则命中，但点击后跳过目标仍存在；已停止重复点击，请反馈该规则"
                    else "已发起规则点击，观察到页面发生变化")
            }
        }
    }
    private fun showProposal(rule: SkipRule) {
        if (overlayRuleId == rule.id && !overlayIsResult) return
        showPanel(rule, false, "发现新的跳过方案", "本次试跳后，请确认实际结果。", listOf(
            "试跳" to { dispatch(rule, true) },
            "忽略" to { repository.put(rule.copy(status = RuleStatus.DISABLED)); hideOverlay(); gate.dispatched() },
        ))
    }
    private fun showResult(rule: SkipRule) {
        if (overlayRuleId == rule.id && overlayIsResult) return
        showPanel(rule, true, "刚才是否正确跳过广告？", "确认成功后，下次相同界面自动跳过。", listOf(
            "确实跳过" to { confirm(rule, true) }, "没有跳过" to { confirm(rule, false) }, "稍后确认" to { hideOverlay() },
        ))
    }
    private fun confirm(rule: SkipRule, success: Boolean) {
        runCatching { repository.confirm(rule.id, success) }.onFailure { repository.log(rule.packageName, "确认记录过期或规则变化，请重新试跳") }
        hideOverlay()
    }
    private fun showPanel(rule: SkipRule, result: Boolean, title: String, text: String, actions: List<Pair<String, () -> Unit>>) {
        hideOverlay()
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            setBackgroundColor(Color.rgb(239, 247, 242))
            addView(TextView(context).apply { this.text = title; textSize = 16f; setTextColor(Color.rgb(23, 54, 42)) })
            addView(TextView(context).apply { this.text = text; textSize = 12f; setTextColor(Color.DKGRAY) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                actions.forEach { (label, action) -> addView(Button(context).apply {
                    this.text = label; textSize = 13f; setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            })
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        runCatching { getSystemService(WindowManager::class.java).addView(panel, params) }.onSuccess {
            overlay = panel; overlayRuleId = rule.id; overlayIsResult = result
            expireJob = scope.launch { delay(if (result) 30_000 else 8_000); hideOverlay() }
        }.onFailure { repository.log(rule.packageName, "确认浮层不可用，请到规则页查看") }
    }
    private fun hideOverlay() {
        expireJob?.cancel(); expireJob = null
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; overlayRuleId = null; overlayIsResult = false
    }
    override fun onInterrupt() { scanJob?.cancel(); gate.reset(); hideOverlay();checkBubble.hide() }
    private fun resetResults() {
        if(resettingResults) return
        resettingResults=true
        scope.launch {
            try {
                scanJob?.cancelAndJoin();scanJob=null
                nodeRead?.cancelAndJoin();nodeRead=null
                ownerProbe?.cancelAndJoin();ownerProbe=null
                clearNodes();autoLearning?.close();autoLearning=null
                gate.reset();hideOverlay();checkBubble.hide();bubbleSession=null;targetEventPackage=null;eventWindowId=null;knownOwner=null;ownerRevision++
                diagnosticPackage=null;reportedReasons.clear();targetEventAt=0
                tracePackage=null;traceSnapshotPending=false;cachedVersion=null;nextNodeRead=0
                val app=application as TiaoApplication
                val worker=app.visualLearner
                worker.closeAndJoin()
                withContext(Dispatchers.IO) {worker.clearPending();repository.clearResults()}
                app.visualLearner=com.haibo.tiaotiao.data.VisualLearner(this@SkipAccessibilityService,repository)
                lastReadState="全部规则、验证证据、样本及学习缓存已清空；配置保留"
                if(android.os.Build.VERSION.SDK_INT>=30) learning().warmUp()
            } catch(_: Exception) {lastReadState="结果清空未完成，请检查存储状态"}
            finally {resettingResults=false}
        }
    }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            args.firstOrNull {it.startsWith("hold-actions=")}?.substringAfter('=')?.let {pkg ->
                if(pkg in repository.settings.value.packages) {
                    diagnosticHeldPackage=pkg
                    getSharedPreferences("touch_diagnostic_controls",MODE_PRIVATE).edit().putString("heldPackage",pkg).apply()
                    writer.println("Temporarily holding automatic actions for $pkg; configuration is unchanged")
                }
            }
            args.firstOrNull {it.startsWith("probe-tap=")}?.let {probeTap(it.substringAfter('='));writer.println("Probe tap requested")}
            if("probe-result" in args) writer.println("probe=$probeResult")
            if("clear-ad-jumps" in args) {repository.adJumps.clear();writer.println("Cleared launch-ad jump records")}
            args.firstOrNull {it.startsWith("bench-vision=")}?.substringAfter('=')?.toIntOrNull()?.coerceIn(1,3)?.let {repeats ->
                scope.launch(Dispatchers.IO) {com.haibo.tiaotiao.network.VisionBench.run(java.io.File(filesDir,"bench"),repository,repeats)}
                writer.println("Vision benchmark started ($repeats round)")
            }
            args.firstOrNull {it.startsWith("bench-deepseek=")}?.substringAfter('=')?.split(',')?.filter {it.matches(Regex("\\d{3,4}-(base|new|ocr)"))}?.takeIf {it.isNotEmpty()}?.let {variants ->
                scope.launch(Dispatchers.IO) {com.haibo.tiaotiao.network.VisionBench.runDeepSeek(java.io.File(filesDir,"bench"),repository,variants)}
                writer.println("DeepSeek benchmark started: $variants")
            }
            args.firstOrNull {it.startsWith("vision-provider=")}?.substringAfter('=')?.let {name ->
                runCatching {VisionProvider.valueOf(name)}.getOrNull()?.let {provider ->
                    runCatching {repository.configureVision(provider,provider.defaultModel,"")}
                        .onSuccess {writer.println("Vision provider set to $provider")}.onFailure {writer.println("Vision provider unchanged: ${it.message}")}
                }
            }
            if("bench-status" in args) writer.println("bench=${com.haibo.tiaotiao.network.VisionBench.status}")
            args.firstOrNull {it.startsWith("enhanced-touch=")}?.substringAfter('=')?.let {value ->
                repository.updateSettings {it.copy(enhancedTouch=value=="on")};writer.println("Enhanced touch preference set to $value")
            }
            if("release-actions" in args) {diagnosticHeldPackage=null;getSharedPreferences("touch_diagnostic_controls",MODE_PRIVATE).edit().remove("heldPackage").apply();writer.println("Automatic actions resumed")}
            if("start-owned-touch" in args && repository.settings.value.enhancedTouch &&
                (application as TiaoApplication).wirelessBootstrap.state.value.paired) {
                scope.launch {
                    val bootstrap=(application as TiaoApplication).wirelessBootstrap
                    bootstrap.beginDiscovery();bootstrap.startPaired()
                }
                writer.println("Requested app-local startup using its saved pairing identity")
            }
        }
        if("test-vision" in args && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            scope.launch {com.haibo.tiaotiao.network.testVisionConnection(repository)}
            writer.println("Requested a fixed-image connection test for the selected provider")
        }
        if("self-demo" in args) scope.launch {
            if(getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                startActivity(Intent(this@SkipAccessibilityService,com.haibo.tiaotiao.ui.SmartDemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if("clear-demo" in args) scope.launch {withContext(Dispatchers.IO) {
            repository.visualRules.value.filter {it.packageName==packageName && it.source=="bundled-ocr-demo"}.forEach {repository.removeVisual(it.id)}
        }}
        if("reset-results" in args) {resetResults();writer.println("Requested clearing all learning results; configuration is retained")}
        args.firstOrNull {it.startsWith("trace-next=")}?.substringAfter('=')?.let {
            if(it in repository.settings.value.packages || it==packageName) {tracePackage=it;traceSnapshotPending=true;writer.println("Armed one masked execution trace for $it")}
        }
        writer.println("TiaoTiao detection diagnostics (no credentials or screen text)")
        writer.println("enabled=${repository.settings.value.enabled}, scanning=${scanJob?.isActive == true}, session=${gate.generation}, dispatched=${gate.actionDispatched}")
        writer.println("sessionPackage=${gate.packageName}, eventPackage=$targetEventPackage, eventWindow=$eventWindowId")
        writer.println("lastRead=$lastReadState")
        writer.println("treeReads=$diagnosticTreeReads, ignoredDashboardEvents=$ignoredDashboardEvents")
        writer.println("rules=${repository.rules.value.size}, visualRules=${repository.visualRules.value.size}, samples=${repository.sampleCount.value}, resetting=$resettingResults")
        writer.println("checkBubble=${checkBubble.visible}")
        writer.println("enhancedTouch=${repository.settings.value.enhancedTouch}, channel=${(application as TiaoApplication).enhancedTouch.state.value}")
        writer.println("ownedTouch=${(application as TiaoApplication).enhancedTouch.ownedReady}")
        writer.println("touchHealth=${(application as TiaoApplication).enhancedTouch.health.value}")
        writer.println("touchNetwork=${touchJournal.snapshot}, held=$diagnosticHeldPackage")
        touchExecutions.takeLast(4).forEach {writer.println("touchExecution=$it")}
        if("probe-touch" in args) scope.launch {(application as TiaoApplication).enhancedTouch.probe("diagnostic")}
        if("flush-touch-log" in args) scope.launch {touchJournal.flush()}
        (application as TiaoApplication).wirelessBootstrap.state.value.let {
            writer.println("wirelessSetup=${it.phase}, paired=${it.paired}, pairingPort=${it.pairingPort}, connectPort=${it.connectPort}, assisting=${it.assisting}")
        }
        pairingAssistant?.let {writer.println("pairingDialogs=${it.inspectedDialogs}, autoDetected=${it.detectedDialogs}")}
        autoLearning?.let { writer.println(it.summary()) }
        windows.forEach { writer.println("window=${it.id}, type=${it.type}, focused=${it.isFocused}, active=${it.isActive}") }
    }
    override fun onDestroy() {
        (application as TiaoApplication).wirelessBootstrap.cancelAssistance("无障碍服务断开")
        pairingAssistant?.close();pairingAssistant=null
        if(screenReceiver) {runCatching {unregisterReceiver(screenOff)};screenReceiver=false}
        clearNodes()
        autoLearning?.close()
        hideOverlay(); scope.cancel(); repository.connected.value = false
        checkBubble.hide()
        if (instance === this) serviceRef = WeakReference(null)
        super.onDestroy()
    }
}
