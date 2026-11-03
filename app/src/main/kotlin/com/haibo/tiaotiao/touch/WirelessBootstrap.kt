package com.haibo.tiaotiao.touch

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import com.haibo.tiaotiao.TiaoApplication
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.io.IOException

enum class WirelessPhase { IDLE, DISCOVERING, WAITING_CODE, PAIRING, STARTING, READY, FAILED }
data class WirelessState(val phase: WirelessPhase=WirelessPhase.IDLE,val message: String="尚未启动内置增强点击",
    val paired: Boolean=false,val pairingPort: Int=0,val connectPort: Int=0,val assisting: Boolean=false)

class WirelessBootstrap(private val context: Context) {
    companion object {
        fun settingsIntent(context: Context): Intent {
            val direct=Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            val fragment=Intent().setClassName("com.android.settings","com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment","com.android.settings.development.WirelessDebuggingFragment")
            return (when {
                direct.resolveActivity(context.packageManager)!=null ->direct
                fragment.resolveActivity(context.packageManager)!=null ->fragment
                else ->Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    private val app get()=context.applicationContext as TiaoApplication
    private val prefs=context.getSharedPreferences("wireless_bootstrap",Context.MODE_PRIVATE)
    private val _state=MutableStateFlow(WirelessState(paired=prefs.getBoolean("paired",false)))
    val state=_state.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutex=Mutex()
    private var operation: Job?=null
    private var discoveryTimeout: Job?=null
    private var assistanceTimeout: Job?=null
    private var lastAutoPort=0
    private var startupStage="连接本机"
    private var restart: Job?=null
    private val pairDiscovery=AdbMdns(context,AdbMdns.SERVICE_TYPE_TLS_PAIRING) {_,port ->
        _state.update {it.copy(pairingPort=port.coerceAtLeast(0),phase=if(port>0 && it.phase in setOf(WirelessPhase.DISCOVERING,WirelessPhase.WAITING_CODE)) WirelessPhase.WAITING_CODE else it.phase)}
    }
    private val connectDiscovery=AdbMdns(context,AdbMdns.SERVICE_TYPE_TLS_CONNECT) {_,port ->
        if(port>0) prefs.edit().putInt("connectPort",port).apply()
        _state.update {it.copy(connectPort=port.coerceAtLeast(0))}
    }
    init {
        scope.launch {app.enhancedTouch.state.collect {
        if(app.enhancedTouch.ownedReady) {
            _state.update {s ->s.copy(phase=WirelessPhase.READY,message="内置增强点击已就绪",assisting=false)}
            assistanceTimeout?.cancel()
            stopDiscovery()
        }
        else if(_state.value.phase==WirelessPhase.READY) {
            _state.update {s ->s.copy(phase=WirelessPhase.IDLE,message="内置服务已停止，开启无线调试后可重新启动")}
            scheduleRestart("服务停止")
        }
        }}
        // A VPN or Wi-Fi change restarts adbd and kills the shell-launched touch process. Relaunch it with the saved
        // pairing whenever wireless debugging is on again, instead of silently degrading to ACTION_CLICK.
        scope.launch {app.touchJournal.network.collect {if(it.wirelessAdb==1 && !app.enhancedTouch.ownedReady) scheduleRestart("网络变化")}}
        // Turning the preference back on should not require pressing "启动" again when a pairing is saved.
        scope.launch {app.repository.settings.map {it.enhancedTouch}.distinctUntilChanged().collect {
            if(it && !app.enhancedTouch.ownedReady) scheduleRestart("已开启增强点击")
        }}
    }
    private fun canRestart()=Build.VERSION.SDK_INT>=30 && _state.value.paired && !_state.value.assisting &&
        app.repository.settings.value.enhancedTouch && app.enhancedTouch.health.value.reason!="stopped_by_user" &&
        runCatching {Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1}.getOrDefault(false)
    private fun scheduleRestart(trigger: String) {
        if(restart?.isActive==true || !canRestart()) return
        restart=scope.launch {
            for(wait in listOf(2_000L,10_000L,30_000L)) {
                delay(wait)
                if(app.enhancedTouch.ownedReady || !canRestart()) return@launch
                app.repository.log("","增强点击已断开（$trigger），正在用已保存的配对自动重新启动")
                // The connect port changes whenever adbd restarts; rediscover it rather than trusting the saved one.
                _state.update {it.copy(connectPort=0)}
                if(!connectDiscovery.isRunning) runCatching {connectDiscovery.start()}
                startPaired(quiet=true)
                operation?.join()
                if(app.enhancedTouch.ownedReady) return@launch
            }
            stopDiscovery()
        }
    }
    fun beginAssistedPairing(): Boolean {
        if(!app.repository.connected.value) {
            _state.update {it.copy(phase=WirelessPhase.FAILED,message="请先开启 TiaoTiao 无障碍服务，再开始配对")}
            return false
        }
        if(operation?.isActive==true) return false
        beginDiscovery()
        if(Build.VERSION.SDK_INT<30) return false
        lastAutoPort=0
        _state.update {it.copy(assisting=true,message="请在系统打开配对码弹窗并保持显示，TiaoTiao 将在本机自动完成配对")}
        assistanceTimeout?.cancel()
        assistanceTimeout=scope.launch {delay(120_000);cancelAssistance("超时")}
        return true
    }
    fun cancelAssistance(reason: String="用户结束") {
        val active=_state.value.assisting
        assistanceTimeout?.cancel()
        _state.update {it.copy(assisting=false,message=if(it.phase==WirelessPhase.READY) it.message else "本次自动配对已结束，可重新开始")}
        if(operation?.isActive!=true) stopDiscovery()
        if(active) app.repository.log("","配对辅助结束：$reason")
    }
    internal fun detectedPairing(input: PairingInput): Boolean {
        if(!_state.value.assisting || operation?.isActive==true || input.port==lastAutoPort) return false
        if(_state.value.phase !in setOf(WirelessPhase.DISCOVERING,WirelessPhase.WAITING_CODE,WirelessPhase.FAILED)) return false
        if(_state.value.pairingPort>0 && _state.value.pairingPort!=input.port) return false
        lastAutoPort=input.port
        pairingInput(input.code,input.port)
        return true
    }
    fun beginDiscovery() {
        if(Build.VERSION.SDK_INT<30) {_state.update {it.copy(phase=WirelessPhase.FAILED,message="内置无线启动需要 Android 11 及以上")};return}
        if(operation?.isActive==true) return
        val fresh=!pairDiscovery.isRunning && !connectDiscovery.isRunning
        _state.update {it.copy(phase=WirelessPhase.DISCOVERING,message="填写系统无线调试弹窗中的配对码，端口可自动发现或直接填写",
            pairingPort=if(fresh) 0 else it.pairingPort,connectPort=if(fresh) 0 else it.connectPort)}
        runCatching {pairDiscovery.start();connectDiscovery.start()}.onFailure {
            _state.update {s ->s.copy(phase=WirelessPhase.FAILED,message="自动发现暂不可用，可手动填写配对端口")}
        }
        discoveryTimeout?.cancel()
        discoveryTimeout=scope.launch {delay(600_000);timeout()}
    }
    fun stopDiscovery() {discoveryTimeout?.cancel();runCatching {pairDiscovery.stop()};runCatching {connectDiscovery.stop()}}
    fun pairingInput(text: String,manualPort: Int?=null,connectPort: Int?=null) {
        val input=PairingInput.parse(text,_state.value.pairingPort,manualPort)
        if(input==null) {
            _state.update {it.copy(phase=WirelessPhase.WAITING_CODE,message="请输入6位配对码，并在未自动发现时填写配对端口")};return
        }
        val code=input.code;val port=input.port
        if(connectPort!=null && connectPort in 1..65535) {_state.update {it.copy(connectPort=connectPort)};prefs.edit().putInt("connectPort",connectPort).apply()}
        if(operation?.isActive==true) return
        operation=scope.launch {mutex.withLock {
            _state.update {it.copy(phase=WirelessPhase.PAIRING,message="正在与本机安全配对…")}
            var client: AbsAdbConnectionManager?=null
            try {
                val connection=withContext(Dispatchers.IO) {client()}
                client=connection
                startupStage="配对握手"
                withContext(Dispatchers.IO) {check(connection.pair("127.0.0.1",port,code))}
                prefs.edit().putBoolean("paired",true).apply()
                _state.update {it.copy(paired=true,phase=WirelessPhase.STARTING,message="配对成功，正在启动内置触控服务…")}
                launchServer(connection)
                Toast.makeText(context,"内置增强点击已就绪",Toast.LENGTH_SHORT).show()
            } catch(c: CancellationException) {throw c}
            catch(error: Exception) {
                app.repository.log("","内置配对或启动未完成：$startupStage / ${error.javaClass.simpleName}")
                _state.update {it.copy(phase=WirelessPhase.FAILED,message=if(it.paired) "已保存配对；$startupStage 未完成，可保持无线调试开启后再次启动"
                    else "配对未完成，请核对配对码与端口；弹窗关闭后请重新获取")}
            }
            finally {withContext(NonCancellable+Dispatchers.IO) {runCatching {client?.disconnect()}}}
        }}
    }
    fun startPaired(manualConnectPort: Int?=null,quiet: Boolean=false) {
        if(manualConnectPort!=null && manualConnectPort in 1..65535) {
            _state.update {it.copy(connectPort=manualConnectPort)};prefs.edit().putInt("connectPort",manualConnectPort).apply()
        }
        if(operation?.isActive==true) return
        operation=scope.launch {mutex.withLock {
            _state.update {it.copy(phase=WirelessPhase.STARTING,message="正在连接本机并启动增强点击…")}
            var client: AbsAdbConnectionManager?=null
            try {val connection=withContext(Dispatchers.IO) {client()};client=connection;launchServer(connection)
                if(quiet) app.repository.log("","增强点击已自动恢复") else Toast.makeText(context,"内置增强点击已就绪",Toast.LENGTH_SHORT).show()}
            catch(c: CancellationException) {throw c}
            catch(error: Exception) {
                app.repository.log("","内置启动未完成：$startupStage / ${error.javaClass.simpleName}")
                _state.update {it.copy(phase=WirelessPhase.FAILED,message="启动未完成，请开启无线调试；首次使用需先配对")}
            }
            finally {withContext(NonCancellable+Dispatchers.IO) {runCatching {client?.disconnect()}}}
        }}
    }
    fun timeout() {
        if(_state.value.phase!=WirelessPhase.READY) _state.update {it.copy(phase=WirelessPhase.FAILED,message="本次设置已结束，可重新开始配对或启动",assisting=false)}
        stopDiscovery()
    }
    private suspend fun launchServer(client: AbsAdbConnectionManager) {
        if(app.enhancedTouch.ownedReady) {completeReady();return}
        startupStage="发现本机连接端口"
        val port=withTimeoutOrNull(6000) {state.first {it.connectPort>0}.connectPort} ?: prefs.getInt("connectPort",0)
        check(port in 1..65535)
        withContext(Dispatchers.IO) {
            startupStage="建立本机连接"
            check(client.connect("127.0.0.1",port) || client.isConnected)
        }
        val path=context.applicationInfo.sourceDir
        val quoted="'"+path.replace("'","'\\''")+"'"
        val debug=context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0
        val output=if(debug) "/data/local/tmp/tiaotiao-touch-${context.applicationInfo.uid}.log" else "/dev/null"
        val command="umask 077\nCLASSPATH=$quoted nohup setsid app_process /system/bin --nice-name=tiaotiao_touch com.haibo.tiaotiao.touch.OwnedTouchServer </dev/null >$output 2>&1 &\n"
        startupStage="打开本机启动会话"
        val stream=withContext(Dispatchers.IO) {client.openStream("shell:")}
        try {
            startupStage="提交服务启动命令"
            withContext(Dispatchers.IO) {stream.openOutputStream().apply {write(command.toByteArray(Charsets.UTF_8));flush()}}
            // Keep the launching shell alive until the detached process has attached its Binder.
            // An EOF/closed command stream alone is never evidence that the service is ready.
            startupStage="等待内置服务就绪"
            val ready=withTimeoutOrNull(8000) {app.enhancedTouch.state.first {app.enhancedTouch.ownedReady};true} ?: false
            if(!ready) throw IOException("Local touch service did not attach")
        } finally {withContext(NonCancellable+Dispatchers.IO) {runCatching {stream.close()}}}
        app.repository.updateSettings {it.copy(enhancedTouch=true)}
        completeReady()
    }
    private fun completeReady() {
        assistanceTimeout?.cancel()
        _state.update {it.copy(phase=WirelessPhase.READY,message="内置增强点击已就绪",assisting=false)}
        stopDiscovery()
    }
    private fun client(): AbsAdbConnectionManager {
        val keys=WirelessAdbKeys(context)
        return object : AbsAdbConnectionManager() {
            override fun getPrivateKey(): PrivateKey=keys.privateKey
            override fun getCertificate(): Certificate=keys.certificate
            override fun getDeviceName()="TiaoTiao"
        }.apply {setApi(Build.VERSION.SDK_INT);setHostAddress("127.0.0.1");setTimeout(5,TimeUnit.SECONDS)}
    }
}
