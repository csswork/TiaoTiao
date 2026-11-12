package com.haibo.tiaotiao.touch

/** NOT_SENT: the enhanced channel answered, but refused before any touch event reached the screen. */
enum class TouchDispatch { UNAVAILABLE, NOT_SENT, COMPLETED, FAILED }
/** Service replies that prove no DOWN event was delivered, and that are not the guard objecting to the screen
 * (wrong foreground app, changed display): only these may be repeated on the accessibility lane. */
internal val touchNotSent=setOf("foreground_check_timeout","request_expired","request_expired_after_check","inject_permission_denied","down_rejected")
data class TouchResult(val backend: String,val completed: Boolean)
@kotlinx.serialization.Serializable
data class TouchExecution(val ruleId: String,val backend: String,val x: Int,val y: Int,
    val width: Int,val height: Int,val pressMs: Long,val uptimeMs: Long,val completed: Boolean,val engine: String="",
    val requestId: String="",val reason: String="",val elapsedMs: Long=0)

/** Fallback is allowed only when the enhanced channel sent no touch: it was unavailable, or it refused beforehand. */
internal suspend fun routeTouch(enhanced: Boolean,system: suspend () -> TouchDispatch,
    accessibility: suspend () -> Boolean): TouchResult {
    if(enhanced) when(system()) {
        TouchDispatch.COMPLETED -> return TouchResult("内置系统触控",true)
        TouchDispatch.FAILED -> return TouchResult("内置系统触控",false)
        TouchDispatch.UNAVAILABLE, TouchDispatch.NOT_SENT -> Unit
    }
    return TouchResult("无障碍触控",accessibility())
}
