package com.haibo.tiaotiao.touch

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.haibo.tiaotiao.TiaoApplication

enum class TouchConnection { STOPPED, READY, FAILED }
data class TouchHealth(val checkedAt: Long=0,val available: Boolean=false,val uid: Int=-1,val pid: Int=-1,val permission: String="unknown",val reason: String="not_checked")
data class TouchAttempt(val requestId: String="",val reason: String="",val code: Int=0,val elapsedMs: Long=0,val at: Long=0)

/** Single-app Binder client; the only bootstrap is TiaoTiao's locally paired shell starter. */
class EnhancedTouch(private val context: Context) {
    private val journal=(context.applicationContext as TiaoApplication).touchJournal
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val probeLock=Mutex()
    private val _health=MutableStateFlow(TouchHealth())
    val health=_health.asStateFlow()
    private val _attempt=MutableStateFlow(TouchAttempt())
    val attempt=_attempt.asStateFlow()
    val clientLife=Binder()
    private val handler=Handler(Looper.getMainLooper())
    private val _state=MutableStateFlow(TouchConnection.STOPPED)
    val state=_state.asStateFlow()
    @Volatile private var service: ISystemTouch?=null
    @Volatile var lastEngine=""
        private set
    /** The shell process is attached. Whether it can actually touch the screen is [state] == READY. */
    val ownedReady get()=service?.asBinder()?.pingBinder()==true
    /** The one definition of "enhanced touch works now" for every screen and for the touch path. */
    val usable get()=_state.value==TouchConnection.READY && ownedReady
    /** The system refused an injected touch (e.g. MIUI without "USB 调试（安全设置）") although the shell holds the
     * permission on paper. Sticky until a touch succeeds, the service re-attaches or the user re-checks. */
    @Volatile private var injectDenied=false
    fun allowRestart() {if(_health.value.reason=="stopped_by_user") _health.value=TouchHealth(checkedAt=System.currentTimeMillis())}
    init {
        scope.launch {journal.network.drop(1).collect {probe("network_change")}}
        scope.launch {while(isActive) {delay(30_000);if(ownedReady) probe("heartbeat")}}
    }
    fun attachOwned(binder: IBinder) {
        service=ISystemTouch.Stub.asInterface(binder)
        binder.linkToDeath({handler.post {
            if(service?.asBinder()==binder) {
                service=null;_state.value=TouchConnection.STOPPED;_health.value=TouchHealth(checkedAt=System.currentTimeMillis(),reason="binder_died")
                journal.record("SERVICE","binder_died")
            }
        }},0)
        handler.post {refresh();journal.record("SERVICE","attached");scope.launch {probe("attached");restoreStaleSensorPauses()}}
    }
    private val sensorPauses=context.getSharedPreferences("sensor_pauses",Context.MODE_PRIVATE)
    /** Pause one app's motion sensors for its launch window. Recorded first, so a service that dies before its own
     * restore timer fires is corrected on the next attach rather than leaving the app sensor-idle until reboot. */
    suspend fun pauseSensors(pkg: String,durationMs: Long): Boolean {
        val target=service?.takeIf {it.asBinder().pingBinder()} ?: return false
        sensorPauses.edit().putLong(pkg,System.currentTimeMillis()+durationMs).apply()
        return withContext(NonCancellable+Dispatchers.IO) {
            val code=runCatching {target.pauseSensors(pkg,durationMs).getInt("code")}.getOrDefault(0)
            journal.record("SENSOR",if(code==1) "sensors_paused" else "sensor_pause_failed",pkg,details=mapOf("durationMs" to durationMs.toString()))
            code==1
        }
    }
    private suspend fun restoreStaleSensorPauses() {
        val target=service ?: return
        val stale=sensorPauses.all.keys.toList()
        if(stale.isEmpty()) return
        withContext(Dispatchers.IO) {stale.forEach {pkg ->
            if(runCatching {target.resumeSensors(pkg).getInt("code")}.getOrDefault(0)==1) sensorPauses.edit().remove(pkg).apply()
        }}
    }
    fun refresh() {_state.value=if(ownedReady) TouchConnection.READY else TouchConnection.STOPPED}
    suspend fun probe(trigger: String="manual")=probeLock.withLock {
        if(trigger in setOf("attached","user_check")) injectDenied=false
        val target=service
        val old=_health.value
        val value=try {
            if(target==null || !target.asBinder().pingBinder()) TouchHealth(checkedAt=System.currentTimeMillis(),reason="service_unavailable")
            else withContext(Dispatchers.IO) {
                val status=target.status()
                val uid=status.getInt("uid",-1);val permission=status.getString("permission","unknown")
                val denied=permission=="denied" || injectDenied
                TouchHealth(System.currentTimeMillis(),status.getInt("protocol")==2 && uid in setOf(0,2000) && !denied,
                    uid,status.getInt("pid",-1),if(denied) "denied" else permission,if(denied) "inject_permission_denied" else "service_responding")
            }
        } catch(error: Exception) {TouchHealth(checkedAt=System.currentTimeMillis(),reason="probe_${error.javaClass.simpleName}")}
        if(service!==target) return@withLock
        _health.value=value
        if(target!=null) _state.value=if(value.available) TouchConnection.READY else TouchConnection.FAILED
        if(trigger!="heartbeat" || old.copy(checkedAt=0)!=value.copy(checkedAt=0)) journal.record("HEALTH",value.reason,details=mapOf(
            "trigger" to trigger,"uid" to value.uid.toString(),"pid" to value.pid.toString(),"permission" to value.permission,"available" to value.available.toString()))
    }
    suspend fun stopOwned() {
        val previous=service;service=null
        refresh()
        _health.value=TouchHealth(checkedAt=System.currentTimeMillis(),reason="stopped_by_user")
        journal.record("SERVICE","stopped_by_user")
        withContext(NonCancellable+Dispatchers.IO) {runCatching {previous?.destroy()}}
    }
    suspend fun tap(pkg: String,x: Int,y: Int,width: Int,height: Int): TouchDispatch {
        val target=service?.takeIf {it.asBinder().pingBinder()} ?: run {journal.record("ACTION","service_unavailable",pkg);return TouchDispatch.UNAVAILABLE}
        val deadline=SystemClock.uptimeMillis()+600
        return withContext(NonCancellable+Dispatchers.IO) {
            try {
                val report=target.tapDetailed(pkg,x,y,width,height,deadline)
                val result=report.getInt("code")
                val reason=report.getString("reason","unknown")
                val requestId=report.getString("requestId","")
                _attempt.value=TouchAttempt(requestId,reason,result,report.getLong("elapsedMs"),System.currentTimeMillis())
                journal.record("ACTION",reason,pkg,requestId=requestId,details=mapOf("backend" to "system","code" to result.toString(),
                    "x" to x.toString(),"y" to y.toString(),"width" to width.toString(),"height" to height.toString(),
                    "pressMs" to SystemTouchService.PRESS_MS.toString(),"elapsedMs" to report.getLong("elapsedMs").toString(),
                    "uid" to report.getInt("uid").toString(),"pid" to report.getInt("pid").toString()))
                lastEngine=when(result) {1 -> "系统输入接口";2 -> "兼容系统命令";else -> "未完成"}
                if(result in 1..2) injectDenied=false
                else if(reason=="inject_permission_denied") {
                    injectDenied=true
                    _health.value=_health.value.copy(checkedAt=System.currentTimeMillis(),available=false,permission="denied",reason=reason)
                    handler.post {if(service===target) _state.value=TouchConnection.FAILED}
                }
                if(result in 1..2) TouchDispatch.COMPLETED else if(reason in touchNotSent) TouchDispatch.NOT_SENT else TouchDispatch.FAILED
            } catch(error: Exception) {
                val reason="rpc_${error.javaClass.simpleName}"
                _attempt.value=TouchAttempt(reason=reason,at=System.currentTimeMillis())
                journal.record("ACTION",reason,pkg,details=mapOf("backend" to "system","x" to x.toString(),"y" to y.toString()))
                lastEngine="未完成"
                handler.post {if(service==target) {service=null;_state.value=TouchConnection.FAILED}}
                TouchDispatch.FAILED
            }
        }
    }
}
