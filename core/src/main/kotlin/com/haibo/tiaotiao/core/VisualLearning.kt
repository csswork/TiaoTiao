package com.haibo.tiaotiao.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class VisualRuleState { CANDIDATE, ACTIVE, PAUSED }
@Serializable data class AutoEvidence(val session: String, val behavior: String, val at: Long,
    val earlyClick: Boolean, val normalHome: Boolean, val sameApplication: Boolean, val timingBasis: String = "countdown")
@Serializable data class VisualRule(
    val id: String = UUID.randomUUID().toString(), val packageName: String, val versionCode: Long,
    val scene: String, val target: Area, val targetHash: String, val label: String,
    val source: String, val createdAt: Long, val state: VisualRuleState = VisualRuleState.CANDIDATE,
    val evidence: List<AutoEvidence> = emptyList(), val lastResult: String = "等待自动尝试",
    val homeScene: String = "",
    val awaitingLocalReview: Boolean = false,
) {
    fun behavior() = digest("$packageName|$versionCode|$scene|$target|$targetHash|$label")
    fun successfulSessions() = evidence.filter { it.behavior == behavior() && it.earlyClick && it.normalHome && it.sameApplication }.map { it.session }.distinct().size
    fun active() = state == VisualRuleState.ACTIVE && successfulSessions() >= 2
}
@Serializable data class VisualBundle(val format: String = "TiaoTiaoVisualRules", val schemaVersion: Int = 1,
    val rules: List<VisualRule>)
data class VisualSignals(val advertisement: Boolean, val countdown: Int?, val skips: List<Area>, val home: Boolean)

object VisualPolicy {
    fun clickableTarget(screen: ScreenSnapshot, area: Area): ScreenNode? {
        val x=(area.left+area.right)/2;val y=(area.top+area.bottom)/2
        return screen.candidates().filter { n ->
            val b=n.bounds
            b.right-b.left<=.25f && b.bottom-b.top<=.15f && x in b.left..b.right && y in b.top..b.bottom
        }.singleOrNull()
    }
    fun validate(rule: VisualRule) {
        require(rule.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(rule.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && rule.versionCode >= 0)
        require(rule.scene.matches(Regex("[a-f0-9]{64}")) && rule.target.valid())
        require(rule.targetHash.matches(Regex("[01]{64}")) && rule.label in setOf("skip", "close_ad"))
        require(rule.source.length <= 120 && rule.evidence.size <= 20)
        require(rule.homeScene.isEmpty() || rule.homeScene.matches(Regex("[a-f0-9]{64}")))
        require(rule.target.right - rule.target.left <= .4f && rule.target.bottom - rule.target.top <= .18f)
        if (rule.state == VisualRuleState.ACTIVE) require(rule.active())
    }
    fun canAttempt(rule: VisualRule, screen: ScreenSnapshot, signals: VisualSignals, patchHash: String): Boolean {
        if (rule.state == VisualRuleState.PAUSED || rule.awaitingLocalReview || !screen.complete || screen.packageName != rule.packageName ||
            screen.versionCode != rule.versionCode || screen.fingerprint() != rule.scene || signals.home) return false
        // Fresh visible advertising evidence is required even for a previously successful visual rule.
        if (!signals.advertisement && !(signals.skips.isNotEmpty() && signals.countdown != null)) return false
        val labelled = signals.skips.any { it.near(rule.target) }
        val patch = patchHash.length == 64 && patchHash.zip(rule.targetHash).count { it.first != it.second } <= 5
        val informative = rule.targetHash.count { it=='1' } in 4..60 && patchHash.count { it=='1' } in 4..60
        return labelled || (signals.advertisement && patch && informative)
    }
    fun record(rule: VisualRule, session: String, early: Boolean, home: Boolean, sameApp: Boolean, now: Long, timingBasis: String = "countdown"): VisualRule {
        if (!home || !sameApp) return rule.copy(state = VisualRuleState.PAUSED, lastResult = "自动检查未通过，已暂停")
        require(timingBasis in setOf("countdown","observed_baseline","unknown"))
        val proof = AutoEvidence(session, rule.behavior(), now, early, home, sameApp,timingBasis)
        val next = rule.copy(evidence = (rule.evidence.filterNot { it.session == session } + proof).takeLast(20))
        return next.copy(state = if (next.successfulSessions() >= 2) VisualRuleState.ACTIVE else VisualRuleState.CANDIDATE,
            lastResult = if (early) "自动检查通过（${next.successfulSessions()}/2）" else "页面已变化，时序证据不足，保留候选")
    }
    fun decode(text: String): VisualBundle {
        require(text.toByteArray().size <= RuleCodec.MAX_BYTES)
        val bundle = ruleJson.decodeFromString<VisualBundle>(text)
        require(bundle.format == "TiaoTiaoVisualRules" && bundle.schemaVersion == 1 && bundle.rules.size <= 100)
        require(bundle.rules.map { it.id }.distinct().size == bundle.rules.size)
        bundle.rules.forEach(::validate)
        return bundle
    }
    fun import(text: String): List<VisualRule> = decode(text).rules.map {
        it.copy(state = VisualRuleState.CANDIDATE, evidence = emptyList(), awaitingLocalReview = true, lastResult = "已导入，等待本机 AI 重新识别")
    }
}
