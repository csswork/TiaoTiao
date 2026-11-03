package com.haibo.tiaotiao.core

object VisualExecution {
    const val LOCAL_SOURCE="local:explicit-ad-skip"
    /** An explicit skip plus an ad badge in two frames needs no remote coordinate guess. */
    fun explicitTarget(signals: VisualSignals,previous: Area?): Area? {
        if(!signals.advertisement || signals.home || previous==null) return null
        val area=signals.explicitSkips.singleOrNull() ?: return null
        if(!area.valid() || area.right-area.left>.25f || area.bottom-area.top>.12f || (area.top>=.2f && area.bottom<=.8f)) return null
        return VisualPolicy.controlTarget(previous,listOf(area))
    }
    /** A second gesture requires a fresh, unchanged advertisement and the same observed control. */
    fun retryTarget(rule: VisualRule,before: ScreenSnapshot,after: ScreenSnapshot,area: Area,
        signals: VisualSignals,difference: Double,elapsed: Long,attempts: Int,nativeFallback: Boolean=false): Area? {
        if((rule.tapMethod!=TapMethod.GESTURE && !(rule.tapMethod==TapMethod.ACCESSIBILITY && nativeFallback)) || attempts!=1 || elapsed !in 900..2500 ||
            !difference.isFinite() || difference>.02 || signals.home || !signals.advertisement ||
            before.packageName!=after.packageName || before.versionCode!=after.versionCode || before.windowId!=after.windowId ||
            before.width!=after.width || before.height!=after.height) return null
        val observed=VisualPolicy.controlTarget(area,signals.skips) ?: return null
        return if(rule.userAdjusted) area else observed
    }
}
