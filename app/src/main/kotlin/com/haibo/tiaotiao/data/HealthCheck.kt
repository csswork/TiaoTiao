package com.haibo.tiaotiao.data

enum class HealthLevel { CRITICAL, WARNING, INFO }
enum class HealthAction { ACCESSIBILITY_SETTINGS, ENABLE_AUTO_SKIP, SELECT_APPS, API_KEY, AI_SETTINGS, ENHANCED_TOUCH, BATTERY, AD_JUMP }
data class HealthIssue(val id: String,val level: HealthLevel,val title: String,val detail: String,
    val action: HealthAction?=null,val actionLabel: String="",val packageName: String="")

/** Everything the dashboard can observe about whether skipping actually works right now. */
data class HealthSnapshot(
    val sdk: Int,
    /** Listed in the system's enabled accessibility services (and the master switch is on). */
    val accessibilityEnabled: Boolean,
    /** The service instance is bound and has completed onServiceConnected. */
    val serviceBound: Boolean,
    /** Bound, yet no accessibility event arrived although the dashboard has been in front long enough to send one. */
    val serviceSilent: Boolean=false,
    val autoSkip: Boolean=true,
    val selectedApps: Int=1,
    val automaticLearning: Boolean=true,
    val cloudEnabled: Boolean=true,
    /** Name of the key that is missing for the selected image provider, or null when present. */
    val missingKey: String?=null,
    /** A provider error that blocks every request until the configuration is saved or tested again. */
    val blockingAiError: String?=null,
    /** The other official provider that requests are routed to while the selected one is blocked. */
    val aiFallback: String?=null,
    val enhancedTouch: Boolean=false,
    val enhancedReady: Boolean=false,
    val enhancedPaired: Boolean=false,
    val wirelessDebugging: Boolean=false,
    val batteryRestricted: Boolean=false,
    /** Most recent screenshot/OCR failure within the last few minutes. */
    val captureFailure: String?=null,
    /** Apps whose launch ad opened another app by itself, not yet acknowledged: package to label. */
    val adJumps: List<Pair<String,String>> = emptyList(),
    /** Apps confirmed to ignore accessibility touches: package to label. */
    val touchRefusals: List<Pair<String,String>> = emptyList(),
)

object HealthCheck {
    fun evaluate(s: HealthSnapshot): List<HealthIssue> = buildList {
        when {
            !s.accessibilityEnabled -> add(HealthIssue("a11y_off",HealthLevel.CRITICAL,"无障碍服务未开启",
                "系统已关闭 TiaoTiao 的无障碍服务（常见于系统回收、更新应用或省电清理），所有自动跳过都已停止。",
                HealthAction.ACCESSIBILITY_SETTINGS,"去开启"))
            !s.serviceBound -> add(HealthIssue("a11y_unbound",HealthLevel.CRITICAL,"无障碍服务已开启但没有运行",
                "系统显示已开启，但服务没有连上，检测实际已停止。请在无障碍设置中关闭 TiaoTiao，再重新打开。",
                HealthAction.ACCESSIBILITY_SETTINGS,"打开无障碍设置"))
            s.serviceSilent -> add(HealthIssue("a11y_silent",HealthLevel.CRITICAL,"无障碍服务没有响应",
                "服务显示已连接，但收不到任何界面变化，检测实际已停止。请在无障碍设置中关闭 TiaoTiao，再重新打开。",
                HealthAction.ACCESSIBILITY_SETTINGS,"打开无障碍设置"))
        }
        if(!s.autoSkip) add(HealthIssue("auto_skip_off",HealthLevel.WARNING,"自动跳过已暂停","开启后才会检测和跳过开屏广告。",
            HealthAction.ENABLE_AUTO_SKIP,"开启自动跳过"))
        if(s.selectedApps==0) add(HealthIssue("no_apps",HealthLevel.WARNING,"还没有选择应用","只有选中的应用会被检测。",
            HealthAction.SELECT_APPS,"选择应用"))
        s.missingKey?.let {add(HealthIssue("missing_key",HealthLevel.WARNING,"尚未填写 $it",
            "没有密钥时只能复用已学会的规则，新的开屏广告无法识别。",HealthAction.API_KEY,"填写密钥"))}
        if(s.missingKey==null && !s.cloudEnabled) add(HealthIssue("cloud_off",HealthLevel.INFO,"云端分析已关闭",
            "已学会的规则仍可使用，新的开屏广告不会再学习。",HealthAction.AI_SETTINGS,"打开设置"))
        s.blockingAiError?.let {add(if(s.aiFallback!=null) HealthIssue("ai_fallback",HealthLevel.INFO,"AI 服务已自动切换到 ${s.aiFallback}",
            "$it。新识别暂时由 ${s.aiFallback} 处理；每 5 分钟自动重测，恢复后切回。",HealthAction.AI_SETTINGS,"查看设置")
            else HealthIssue("ai_blocked",HealthLevel.WARNING,"AI 服务请求被拒绝",
            "$it。已暂停新的识别请求，网络或代理恢复后到设置中重新测试连接。",HealthAction.AI_SETTINGS,"重新测试"))}
        if(s.sdk<30 && s.automaticLearning) add(HealthIssue("old_android",HealthLevel.INFO,"智能识别需要 Android 11 及以上",
            "当前系统只能使用文字“跳过”规则，图片和图标类关闭按钮无法识别。"))
        s.captureFailure?.let {add(HealthIssue("capture",HealthLevel.WARNING,"截图识别暂不可用",
            "最近一次开屏截图失败：$it。如持续出现，请在无障碍设置中关闭 TiaoTiao 后重新打开。",HealthAction.ACCESSIBILITY_SETTINGS,"打开无障碍设置"))}
        s.adJumps.forEach {(pkg,label) -> add(HealthIssue("ad_jump_$pkg",HealthLevel.WARNING,"「$label」的开屏广告会自己跳转",
            "摇一摇 / 翻转类广告，不点也会打开广告页。",HealthAction.AD_JUMP,"查看解决方法",pkg))}
        if(!s.enhancedReady) s.touchRefusals.forEach {(pkg,label) -> add(HealthIssue("touch_refusal_$pkg",HealthLevel.WARNING,
            "「$label」会忽略无障碍点击","它会识别并拦截无障碍服务发出的点击，开屏广告只能等它自己结束。开启增强点击后可以自动跳过。",
            HealthAction.ENHANCED_TOUCH,if(s.enhancedTouch) "检查增强点击" else "开启增强点击",pkg))}
        if(s.enhancedTouch && !s.enhancedReady) add(HealthIssue("enhanced",HealthLevel.INFO,
            if(!s.enhancedPaired) "增强点击尚未配对" else if(!s.wirelessDebugging) "增强点击未运行：无线调试已关闭" else "增强点击未运行",
            "普通点击仍然可用；部分广告的跳转拦截和传感器暂停需要增强点击。",HealthAction.ENHANCED_TOUCH,"去处理"))
        if(s.batteryRestricted) add(HealthIssue("battery",HealthLevel.INFO,"后台运行可能受限",
            "建议在电池设置中把 TiaoTiao 设为“无限制”，减少服务被系统回收。",HealthAction.BATTERY,"打开电池设置"))
    }.sortedBy {it.level.ordinal}
}
