package com.haibo.tiaotiao.core

/** Presentation groups never change execution matching or combine verification evidence. */
data class VisualTask(val variants: List<VisualRule>) {
    val id get() = variants.first().id
    val representative get() = variants.maxWith(compareBy<VisualRule> {it.state==VisualRuleState.CANDIDATE}
        .thenBy {it.successfulSessions()}
        .thenBy { !it.awaitingLocalReview }.thenBy { it.createdAt })
}

object VisualProposals {
    /** Same app and scene kind. A v2 scene digest embeds the app version, so digests of different versions cannot be
     * compared; rules of one app learned on different versions are treated as the same scene (see
     * AdRecognition.matchesScene), which is what lets a rule survive an app update instead of being duplicated. */
    fun sameScene(a: VisualRule, b: VisualRule) = a.packageName == b.packageName && a.sceneVersion == b.sceneVersion &&
        (a.scene == b.scene || (a.sceneVersion == 2 && a.versionCode != b.versionCode))
    fun sameTarget(a: VisualRule, b: VisualRule) = sameScene(a,b) &&
        VisualPolicy.controlTarget(a.target,listOf(b.target))!=null

    private fun similarHash(a: String, b: String, tolerance: Int): Boolean {
        if (a.length != 64 || b.length != 64) return false
        if (a == b) return true
        return a.count { it == '1' } in 4..60 && b.count { it == '1' } in 4..60 &&
            a.zip(b).count { it.first != it.second } <= tolerance
    }

    /** Keep the original rule and its proof when another frame describes the same action. */
    fun repeatedSample(a: VisualRule, b: VisualRule): Boolean {
        if (!sameTarget(a, b) || a.tapMethod != b.tapMethod) return false
        val first = a.nodeTarget
        val second = b.nodeTarget
        if (first != null && second != null) return (a.nodeScene == b.nodeScene || a.versionCode != b.versionCode) &&
            first.path == second.path && first.resourceId == second.resourceId && first.className == second.className &&
            SignalText.canonical(first.label) == SignalText.canonical(second.label)
        if (first != null || second != null) return false
        return similarHash(a.targetHash, b.targetHash, 5) &&
            (a.contextHash == b.contextHash || similarHash(a.contextHash, b.contextHash, 4))
    }

    /** Different ad creatives and recognition engines are variants of one pending target. */
    fun tasks(rules: List<VisualRule>): List<VisualTask> {
        val groups = mutableListOf<MutableList<VisualRule>>()
        rules.sortedWith(compareBy<VisualRule> { it.createdAt }.thenBy { it.id }).forEach { rule ->
            val existing = if (rule.state != VisualRuleState.ACTIVE && !VisualPolicy.userPaused(rule)) groups.firstOrNull { group ->
                group.first().state != VisualRuleState.ACTIVE && !VisualPolicy.userPaused(group.first()) && sameTarget(group.first(), rule)
            } else null
            if (existing == null) groups.add(mutableListOf(rule)) else existing.add(rule)
        }
        return groups.map { VisualTask(it.toList()) }
    }
}
