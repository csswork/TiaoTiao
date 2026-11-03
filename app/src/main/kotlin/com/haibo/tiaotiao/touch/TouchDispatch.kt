package com.haibo.tiaotiao.touch

enum class TouchDispatch { UNAVAILABLE, COMPLETED, FAILED }
data class TouchResult(val backend: String,val completed: Boolean)
@kotlinx.serialization.Serializable
data class TouchExecution(val ruleId: String,val backend: String,val x: Int,val y: Int,
    val width: Int,val height: Int,val pressMs: Long,val uptimeMs: Long,val completed: Boolean,val engine: String="",
    val requestId: String="",val reason: String="",val elapsedMs: Long=0)

/** Fallback is allowed only when the enhanced channel has not received an action. */
internal suspend fun routeTouch(enhanced: Boolean,system: suspend () -> TouchDispatch,
    accessibility: suspend () -> Boolean): TouchResult {
    if(enhanced) when(system()) {
        TouchDispatch.COMPLETED -> return TouchResult("内置系统触控",true)
        TouchDispatch.FAILED -> return TouchResult("内置系统触控",false)
        TouchDispatch.UNAVAILABLE -> Unit
    }
    return TouchResult("无障碍触控",accessibility())
}
