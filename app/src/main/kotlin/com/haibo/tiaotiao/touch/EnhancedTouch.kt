package com.haibo.tiaotiao.touch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class TouchConnection(val text: String) {
    MISSING("尚未安装 Shizuku"), STOPPED("请启动 Shizuku"), NEEDS_PERMISSION("等待 Shizuku 授权"),
    CONNECTING("正在连接增强触控"), READY("增强触控已就绪"), FAILED("增强触控连接失败，可重试")
}

class EnhancedTouch(private val context: Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val _state=MutableStateFlow(TouchConnection.STOPPED)
    val state=_state.asStateFlow()
    @Volatile private var service: ISystemTouch?=null
    private var binding=false
    private var bindingTimeout: Job?=null
    @Volatile var lastEngine=""
        private set
    private val args=Shizuku.UserServiceArgs(ComponentName(context.packageName,SystemTouchService::class.java.name))
        .tag("tiaotiao-system-touch").daemon(false).processNameSuffix("touch").version(2)
    private val connection=object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,binder: IBinder?) {
            bindingTimeout?.cancel();binding=false
            service=binder?.takeIf {it.pingBinder()}?.let(ISystemTouch.Stub::asInterface)
            _state.value=if(service!=null) TouchConnection.READY else TouchConnection.FAILED
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service=null;binding=false;_state.value=TouchConnection.STOPPED
        }
    }
    private val received=Shizuku.OnBinderReceivedListener {handler.post {refresh()}}
    private val dead=Shizuku.OnBinderDeadListener {handler.post {
        service=null;binding=false;bindingTimeout?.cancel();_state.value=TouchConnection.STOPPED
    }}
    private val permission=Shizuku.OnRequestPermissionResultListener {code,_ ->if(code==7341) handler.post {refresh()}}
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    fun refresh() {
        if(!Shizuku.pingBinder()) {
            service=null
            _state.value=if(runCatching {context.packageManager.getPackageInfo("moe.shizuku.privileged.api",0)}.isSuccess)
                TouchConnection.STOPPED else TouchConnection.MISSING
            return
        }
        if(runCatching {Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED}.getOrDefault(false).not()) {
            service=null;_state.value=TouchConnection.NEEDS_PERMISSION;return
        }
        if(service?.asBinder()?.pingBinder()==true) {_state.value=TouchConnection.READY;return}
        if(binding) return
        binding=true;_state.value=TouchConnection.CONNECTING
        try {
            Shizuku.bindUserService(args,connection)
            bindingTimeout=scope.launch {
                delay(5000)
                if(binding) {binding=false;_state.value=TouchConnection.FAILED}
            }
        } catch(_: Exception) {binding=false;_state.value=TouchConnection.FAILED}
    }
    fun authorize() {
        try {
            if(!Shizuku.pingBinder()) {refresh();return}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) refresh()
            else Shizuku.requestPermission(7341)
        } catch(_: Exception) {_state.value=TouchConnection.FAILED}
    }
    /** null service is the sole no-input fallback case. Once called, never send a second backend. */
    suspend fun tap(pkg: String,x: Int,y: Int,width: Int,height: Int): TouchDispatch {
        val target=service ?: return TouchDispatch.UNAVAILABLE
        if(!Shizuku.pingBinder() || runCatching {Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED}.getOrDefault(true)) {
            handler.post {refresh()};return TouchDispatch.UNAVAILABLE
        }
        val deadline=SystemClock.uptimeMillis()+600
        return withContext(NonCancellable+Dispatchers.IO) {
            try {
                val result=target.tap(pkg,x,y,width,height,deadline)
                lastEngine=when(result) {1 -> "系统输入接口";2 -> "兼容系统命令";else -> "未完成"}
                if(result in 1..2) TouchDispatch.COMPLETED else TouchDispatch.FAILED
            }
            catch(_: Exception) {handler.post {service=null;binding=false;_state.value=TouchConnection.FAILED};TouchDispatch.FAILED}
        }
    }
}
