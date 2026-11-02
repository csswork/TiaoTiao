package com.haibo.tiaotiao.core

/** Application-independent bounds for AI proposals. Geometry alone never authorizes a click. */
object AdRecognition {
    fun eligible(pkg: String, selected: Set<String>, ownPackage: String, enabled: Boolean) =
        enabled && pkg != ownPackage && pkg in selected

    fun candidates(screen: ScreenSnapshot): List<ScreenNode> = screen.nodes.filter {
        val a=it.bounds
        it.clickable && a.valid() && a.right-a.left<=.4f && a.bottom-a.top<=.18f &&
            it.label.split(" | ").none { word -> word in setOf("跳过登录","跳过引导","跳过教程","安装","下载","购买","打开","立即查看","支付","允许","拒绝","登录","注册","引导","教程","授权","权限") }
    }.take(40)

    // Visual matching uses current ad evidence plus the target, not a full accessibility-tree traversal.
    fun scene(screen: ScreenSnapshot) = digest("visual-v2|${screen.packageName}|${screen.versionCode}|${screen.width}|${screen.height}")
    fun nodeScene(screen: ScreenSnapshot) = digest(buildString {
        append(scene(screen))
        candidates(screen).sortedBy {it.path}.forEach {append("|${it.path}:${it.resourceId}:${it.className}:${SignalText.canonical(it.label)}")}
        append(screen.nodes.map {SignalText.canonical(it.label)}.filter {it.isNotBlank()}.distinct().sorted().joinToString(";"))
    })
    fun matchesScene(rule: VisualRule, screen: ScreenSnapshot) = rule.packageName==screen.packageName &&
        rule.versionCode==screen.versionCode && rule.scene==if(rule.sceneVersion==2) scene(screen) else screen.fingerprint()

    fun matchesNode(selector: TargetSelector, screen: ScreenSnapshot) = candidates(screen).any {
        it.path==selector.path && it.resourceId==selector.resourceId && it.className==selector.className &&
            SignalText.canonical(it.label)==SignalText.canonical(selector.label) && it.bounds.near(selector.bounds)
    }
}
