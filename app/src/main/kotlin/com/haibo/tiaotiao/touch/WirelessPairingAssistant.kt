package com.haibo.tiaotiao.touch

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** An explicitly armed, local-only setup session. Never uses the ad capture/AI pipeline. */
internal class WirelessPairingAssistant(private val service: AccessibilityService,scope: CoroutineScope,
    private val bootstrap: WirelessBootstrap) {
    private val manager=service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout?=null
    private var status: TextView?=null
    private var digits: TextView?=null
    private var portLabel: TextView?=null
    private var confirm: Button?=null
    private var keypad: LinearLayout?=null
    private var code=""
    private var port=""
    private var editingPort=false
    private var manual=false
    private var protectedArea: Rect?=null
    private var movedByUser=false
    @Volatile var inspectedDialogs=0L
        private set
    @Volatile var detectedDialogs=0L
        private set
    private data class Scan(val settingsVisible: Boolean,val input: PairingInput?=null,val protectedArea: Rect?=null,val windowId: Int=-1)
    private val job=scope.launch {
        bootstrap.state.map {it.assisting}.distinctUntilChanged().collectLatest {armed ->
            if(!armed) {hide();return@collectLatest}
            var previous: PairingInput?=null
            var previousWindow=-1
            try {
                while(isActive) {
                    val state=bootstrap.state.value
                    if(state.phase in setOf(WirelessPhase.PAIRING,WirelessPhase.STARTING)) {
                        previous=null
                        hide(clear=false)
                    } else {
                        val scan=withContext(Dispatchers.IO) {runCatching {inspect()}.getOrDefault(Scan(false))}
                        protectedArea=scan.protectedArea
                        if(scan.input!=null && !manual) {
                            if(previousWindow==scan.windowId && previous?.port==scan.input.port && previous?.code==scan.input.code) {
                                if(bootstrap.detectedPairing(scan.input)) detectedDialogs++
                            }
                            previous=scan.input;previousWindow=scan.windowId
                        } else if(scan.settingsVisible) show(state) else hide(clear=false)
                        if(scan.input==null || manual) previous=null
                    }
                    delay(250)
                }
            } finally {hide()}
        }
    }
    @Suppress("DEPRECATION") private fun inspect(): Scan {
        val windows=service.windows
        try {
            val window=windows.firstOrNull {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused}
                ?: return Scan(false)
            val root=window.root ?: return Scan(false)
            val fields=mutableListOf<PairingDialog.Field>()
            var protected: Rect?=null
            try {
                if(root.packageName?.toString()!=PairingDialog.SETTINGS) return Scan(false)
                for(id in listOf(PairingDialog.CODE,PairingDialog.ADDRESS)) {
                    val nodes=root.findAccessibilityNodeInfosByViewId(id)
                    try {
                        for(node in nodes) {
                            if(!node.refresh()) continue
                            val bounds=Rect().also(node::getBoundsInScreen)
                            if(bounds.isEmpty || node.packageName?.toString()!=PairingDialog.SETTINGS) continue
                            if(protected==null) protected=Rect(bounds) else protected.union(bounds)
                            fields.add(PairingDialog.Field(id,node.text?.toString().orEmpty(),PairingDialog.SETTINGS,node.isPassword,node.isEditable))
                        }
                    } finally {nodes.forEach {it.recycle()}}
                }
                if(fields.any {it.id==PairingDialog.CODE}) inspectedDialogs++
                return Scan(true,PairingDialog.parse(PairingDialog.SETTINGS,fields),protected,window.id)
            } finally {root.recycle()}
        } finally {windows.forEach {it.recycle()}}
    }
    private fun dp(n: Int)=(service.resources.displayMetrics.density*n).toInt()
    private fun label(value: String)=TextView(service).apply {text=value;textSize=14f;setTextColor(Color.rgb(24,58,43));setPadding(dp(6),dp(5),dp(6),dp(5))}
    private fun button(value: String,action: () -> Unit)=Button(service).apply {
        text=value;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
        setOnClickListener {action()}
    }
    private fun row()=LinearLayout(service).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun LinearLayout.cell(view: View) {addView(view,LinearLayout.LayoutParams(0,dp(44),1f))}
    private fun show(state: WirelessState) {
        if(panel==null) {
            val view=LinearLayout(service).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))
                background=GradientDrawable().apply {setColor(Color.rgb(240,248,243));cornerRadius=dp(18).toFloat();setStroke(dp(1),Color.rgb(180,205,188))}
            }
            status=label("TiaoTiao · 等待系统配对弹窗").also {header ->
                var lastX=0f;var lastY=0f
                header.setOnTouchListener {_,event ->
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->{lastX=event.rawX;lastY=event.rawY;true}
                        MotionEvent.ACTION_MOVE ->{
                            val params=panel?.layoutParams as? WindowManager.LayoutParams
                            if(params!=null) {
                                params.x=(params.x+(event.rawX-lastX).toInt()).coerceIn(0,(service.resources.displayMetrics.widthPixels-view.width).coerceAtLeast(0))
                                params.y=(params.y+(event.rawY-lastY).toInt()).coerceIn(dp(32),(service.resources.displayMetrics.heightPixels-view.height-dp(24)).coerceAtLeast(dp(32)))
                                lastX=event.rawX;lastY=event.rawY;movedByUser=true
                                runCatching {manager.updateViewLayout(view,params)}
                            };true
                        }
                        MotionEvent.ACTION_UP ->{header.performClick();true}
                        else ->true
                    }
                }
                view.addView(header)
            }
            view.addView(row().apply {
                cell(button("手动输入") {manual=!manual;keypad?.visibility=if(manual) View.VISIBLE else View.GONE;update()})
                cell(button("取消") {bootstrap.cancelAssistance()})
            })
            keypad=LinearLayout(service).apply {
                orientation=LinearLayout.VERTICAL;visibility=if(manual) View.VISIBLE else View.GONE
                addView(row().apply {
                    digits=label("").apply {setOnClickListener {editingPort=false;update()}};cell(requireNotNull(digits))
                    portLabel=label("").apply {setOnClickListener {editingPort=true;update()}};cell(requireNotNull(portLabel))
                })
                for(values in listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("删除","0","清空"))) addView(row().apply {
                    values.forEach {value ->cell(button(value) {
                        val old=if(editingPort) port else code
                        val next=when(value) {"删除" ->old.dropLast(1);"清空" ->"";else ->(old+value).take(if(editingPort) 5 else 6)}
                        if(editingPort) port=next else code=next
                        update()
                    })}
                })
                confirm=button("配对并启动") {bootstrap.pairingInput(code,port.toIntOrNull());code="";update()}.also {addView(it)}
                addView(label("点选配对码或端口，再用上方数字键输入；保持系统弹窗打开。").apply {textSize=12f})
            }.also {view.addView(it)}
            val params=WindowManager.LayoutParams((service.resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(dp(200)),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.START;x=dp(16);y=dp(40);title="TiaoTiao 配对辅助"}
            if(runCatching {manager.addView(view,params)}.isFailure) return
            panel=view
        }
        status?.text=if(state.phase==WirelessPhase.FAILED) "TiaoTiao · ${state.message}" else "TiaoTiao · 配对辅助（拖动这里可移动）"
        update()
    }
    private fun update() {
        digits?.text="${if(!editingPort) "› " else ""}配对码：${"•".repeat(code.length)}"
        val discovered=bootstrap.state.value.pairingPort
        portLabel?.text="${if(editingPort) "› " else ""}端口：${port.ifEmpty {if(discovered>0) discovered.toString() else "待输入"}}"
        confirm?.isEnabled=code.length==6 && (port.toIntOrNull() ?: discovered) in 1..65535
        panel?.post {
            val view=panel ?: return@post
            if(movedByUser) return@post
            val params=view.layoutParams as WindowManager.LayoutParams
            val protected=protectedArea
            val bottom=service.resources.displayMetrics.heightPixels-dp(32)
            params.y=when {
                protected==null ->dp(40)
                protected.bottom+dp(12)+view.height<=bottom ->protected.bottom+dp(12)
                protected.top-dp(12)-view.height>=dp(32) ->protected.top-dp(12)-view.height
                else ->dp(32)
            }
            runCatching {manager.updateViewLayout(view,params)}
        }
    }
    private fun hide(clear: Boolean=true) {
        panel?.let {runCatching {manager.removeView(it)}}
        panel=null;status=null;digits=null;portLabel=null;confirm=null;keypad=null
        if(clear) {code="";port="";manual=false;editingPort=false;protectedArea=null;movedByUser=false}
    }
    fun close() {job.cancel();hide()}
}
