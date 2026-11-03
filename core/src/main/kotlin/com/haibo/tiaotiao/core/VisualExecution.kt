package com.haibo.tiaotiao.core

object VisualExecution {
    const val LOCAL_SOURCE="local:explicit-ad-skip"
    /** Nested OCR/node boxes of one control collapse to the tightest box; separate controls stay ambiguous. */
    fun singleControl(areas: List<Area>): Area? {
        val valid=areas.filter {it.valid()}
        val precise=valid.minByOrNull {(it.right-it.left)*(it.bottom-it.top)} ?: return null
        val x=(precise.left+precise.right)/2;val y=(precise.top+precise.bottom)/2
        return precise.takeIf {valid.all {x in it.left..it.right && y in it.top..it.bottom}}
    }
    /** A literal skip at a screen edge in two launch frames needs no remote coordinate guess. Many splash
     * ads and branded launch pages show no readable "广告" badge, so the badge is not required here. */
    fun explicitTarget(signals: VisualSignals,previous: Area?): Area? {
        if(signals.home || previous==null) return null
        val area=singleControl(signals.explicitSkips) ?: return null
        if(!area.valid() || area.right-area.left>.25f || area.bottom-area.top>.12f || (area.top>=.2f && area.bottom<=.8f)) return null
        return VisualPolicy.controlTarget(previous,listOf(area))
    }
    /** A second touch requires a fresh, unchanged launch screen and the same observed control. */
    fun retryTarget(rule: VisualRule,before: ScreenSnapshot,after: ScreenSnapshot,area: Area,
        signals: VisualSignals,difference: Double,elapsed: Long,attempts: Int,nativeFallback: Boolean=false): Area? {
        if((rule.tapMethod!=TapMethod.GESTURE && !(rule.tapMethod==TapMethod.ACCESSIBILITY && nativeFallback)) || attempts!=1 || elapsed !in 900..2500 ||
            !difference.isFinite() || difference>.02 || signals.home ||
            (!signals.advertisement && VisualPolicy.controlTarget(area,signals.explicitSkips)==null) ||
            before.packageName!=after.packageName || before.versionCode!=after.versionCode || before.windowId!=after.windowId ||
            before.width!=after.width || before.height!=after.height) return null
        val observed=VisualPolicy.controlTarget(area,signals.skips) ?: return null
        return if(rule.userAdjusted) area else observed
    }
}
