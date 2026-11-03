package com.haibo.tiaotiao.touch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable data class TouchNetwork(val wifi: Int=-1,val wirelessAdb: Int=-1,val usbAdb: Int=-1,val active: String="UNKNOWN")
@Serializable data class TouchEvent(val id: String=UUID.randomUUID().toString(),val at: Long=System.currentTimeMillis(),
    val uptimeMs: Long=SystemClock.uptimeMillis(),val type: String,val reason: String,val packageName: String="",val ruleId: String="",
    val requestId: String="",val network: TouchNetwork=TouchNetwork(),val details: Map<String,String> = emptyMap())

/** Bounded local metadata only: never receives screen text, images, pairing codes or API credentials. */
class TouchJournal(context: Context,monitorNetwork: Boolean=true,fileName: String="touch_diagnostics.json") {
    private val context=context.applicationContext
    private val file=AtomicFile(File(context.filesDir,fileName))
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val writeLock=Mutex()
    private val writes=Channel<Unit>(Channel.CONFLATED)
    private val _events=MutableStateFlow<List<TouchEvent>>(emptyList())
    val events=_events.asStateFlow()
    private val _network=MutableStateFlow(TouchNetwork())
    val network=_network.asStateFlow()
    private var networkJob: Job?=null
    val snapshot get()=_network.value
    private val wifi=context.getSystemService(WifiManager::class.java)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    init {
        scope.launch {
            val old=runCatching {if(file.baseFile.exists() && file.baseFile.length()<2_000_000) json.decodeFromString<List<TouchEvent>>(String(file.readFully())) else emptyList()}.getOrDefault(emptyList())
            synchronized(this@TouchJournal) {_events.value=(_events.value+old).distinctBy {it.id}.sortedByDescending {it.at}.take(300)}
            for(ignored in writes) runCatching {flush()}
        }
        if(monitorNetwork) {
            runCatching {ContextCompat.registerReceiver(context,object : BroadcastReceiver() {
                override fun onReceive(context: Context?,intent: Intent?) {refreshNetwork()}
            },IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),ContextCompat.RECEIVER_EXPORTED)}
            runCatching {connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {refreshNetwork()}
                override fun onLost(network: Network) {refreshNetwork()}
                override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) {refreshNetwork()}
            })}
            val observer=object : ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange: Boolean) {refreshNetwork()}}
            for(key in listOf("adb_wifi_enabled","adb_enabled")) runCatching {context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key),false,observer)}
            refreshNetwork()
        }
    }
    @Synchronized fun refreshNetwork() {
        networkJob?.cancel()
        networkJob=scope.launch {
            delay(100)
            fun flag(key: String)=runCatching {Settings.Global.getInt(context.contentResolver,key,-1)}.getOrDefault(-1)
            val caps=runCatching {connectivity.getNetworkCapabilities(connectivity.activeNetwork)}.getOrNull()
            val value=TouchNetwork(runCatching {if(wifi.isWifiEnabled) 1 else 0}.getOrDefault(-1),flag("adb_wifi_enabled"),flag("adb_enabled"),when {
                caps==null ->"NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->"VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->"WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->"CELLULAR"
                else ->"OTHER"
            })
            if(value!=_network.value) {_network.value=value;record("NETWORK","network_changed")}
        }
    }
    @Synchronized fun record(type: String,reason: String,packageName: String="",ruleId: String="",requestId: String="",details: Map<String,String> = emptyMap()) {
        val event=TouchEvent(type=type,reason=reason,packageName=packageName,ruleId=ruleId,requestId=requestId,network=snapshot,
            details=details.entries.take(20).associate {it.key.take(40) to it.value.take(120)})
        _events.value=(listOf(event)+_events.value).take(300)
        writes.trySend(Unit)
    }
    suspend fun flush()=withContext(Dispatchers.IO) {writeLock.withLock {
        val stream=file.startWrite()
        try {stream.write(json.encodeToString(_events.value).toByteArray());file.finishWrite(stream)}
        catch(error: Exception) {file.failWrite(stream);throw error}
    }}
}

internal fun touchReason(reason: String): String=when(reason) {
    "injected","command_completed","native_accepted" ->"系统已执行，尚需检查广告结果"
    "native_target_missing" ->"点击前未找到匹配的原生控件"
    "native_handle_unavailable" ->"原生控件已变化，未发送点击"
    "native_handle_changed" ->"关闭控件刷新后不匹配，未发送点击"
    "native_fallback_unavailable" ->"原生关闭未生效，增强通道未就绪"
    "native_rejected" ->"原生控件未接受点击"
    "native_point_outside" ->"当前触点不在原生控件内"
    "foreground_changed","scene_changed","window_changed" ->"点击前应用或窗口已变化"
    "service_unavailable","binder_died" ->"增强点击服务连接已断开"
    "inject_permission_denied" ->"系统触控权限不可用"
    "down_rejected","move_rejected","up_rejected" ->"系统拒绝了触控事件"
    "foreground_check_timeout" ->"前台检查超时，未发送点击"
    "session_expired" ->"本轮检测已结束，未发送点击"
    "diagnostic_hold" ->"自动点击已临时暂停，正在排查误点"
    "window_unavailable","display_unavailable","focus_unavailable" ->"当前窗口不可确认，未发送点击"
    "ambiguous_target" ->"点击区域内有多个候选，未发送点击"
    "point_outside_window","invalid_coordinates","display_changed" ->"位置或显示尺寸不一致，未发送点击"
    "app_not_selected" ->"应用未启用，未发送点击"
    "rate_limited" ->"点击间隔过短，未重复点击"
    "gesture_completed" ->"无障碍手势已完成，尚需检查结果"
    "gesture_failed" ->"无障碍手势未完成"
    "request_expired","request_expired_after_check" ->"点击请求已过期，未发送点击"
    "target_changed" ->"点击前画面发生变化，未发送点击"
    "ad_closed_early" ->"已确认广告提前关闭"
    "ad_still_visible" ->"点击后广告关闭目标仍然可见"
    "result_unknown","no_result_frames" ->"点击结果尚未确认"
    else ->"触控状态异常，已记录诊断"
}
