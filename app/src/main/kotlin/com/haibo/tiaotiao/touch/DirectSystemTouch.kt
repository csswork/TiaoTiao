package com.haibo.tiaotiao.touch

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/** Available only in the explicitly authorized shell UserService, never in the app process. */
internal class DirectSystemTouch private constructor(private val manager: Any,private val inject: Method,private val display: Method) {
    var lastReason="not_attempted"
        private set
    companion object {
        fun resolve(): DirectSystemTouch? = runCatching {
            val type=Class.forName(if(Build.VERSION.SDK_INT>=34) "android.hardware.input.InputManagerGlobal" else "android.hardware.input.InputManager")
            DirectSystemTouch(requireNotNull(type.getMethod("getInstance").invoke(null)),
                type.getMethod("injectInputEvent",InputEvent::class.java,Int::class.javaPrimitiveType),
                MotionEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType))
        }.getOrNull()
    }
    fun tap(x: Int,y: Int,pressMs: Long): Boolean {
        lastReason="inject_started"
        val device=InputDevice.getDeviceIds().firstOrNull {id ->InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)==true} ?: 0
        val pointer=MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
        val down=SystemClock.uptimeMillis()
        fun send(action: Int): Boolean {
            val coordinates=MotionEvent.PointerCoords().apply {
                this.x=x.toFloat();this.y=y.toFloat();pressure=if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) 0f else 1f;size=1f
            }
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(pointer),arrayOf(coordinates),
                0,0,1f,1f,device,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            return try {display.invoke(event,0);inject.invoke(manager,event,1)==true} finally {event.recycle()}
        }
        var attempted=false;var released=false
        return try {
            attempted=true
            if(!send(MotionEvent.ACTION_DOWN)) {lastReason="down_rejected";return false}
            SystemClock.sleep(pressMs/2)
            if(!send(MotionEvent.ACTION_MOVE)) {lastReason="move_rejected";return false}
            SystemClock.sleep((pressMs-(SystemClock.uptimeMillis()-down)).coerceAtLeast(0))
            val accepted=send(MotionEvent.ACTION_UP);released=accepted
            lastReason=if(accepted) "injected" else "up_rejected";accepted
        } catch(error: Exception) {
            val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            lastReason=if(cause is SecurityException) "inject_permission_denied" else "inject_exception_${cause.javaClass.simpleName}";false
        }
        finally {if(attempted && !released) runCatching {send(MotionEvent.ACTION_CANCEL)}}
    }
}
