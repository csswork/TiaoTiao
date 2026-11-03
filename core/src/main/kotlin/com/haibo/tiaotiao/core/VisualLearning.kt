package com.haibo.tiaotiao.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class VisualRuleState { CANDIDATE, ACTIVE, PAUSED }
@Serializable enum class TapMethod { AUTO, ACCESSIBILITY, GESTURE }
@Serializable data class AutoEvidence(val session: String, val behavior: String, val at: Long,
    val earlyClick: Boolean, val normalHome: Boolean, val sameApplication: Boolean, val timingBasis: String = "countdown")
@Serializable data class VisualConfirmation(val behavior: String,val at: Long)
@Serializable data class VisualRule(
    val id: String = UUID.randomUUID().toString(), val packageName: String, val versionCode: Long,
    val scene: String, val target: Area, val targetHash: String, val label: String,
    val source: String, val createdAt: Long, val state: VisualRuleState = VisualRuleState.CANDIDATE,
    val evidence: List<AutoEvidence> = emptyList(), val lastResult: String = "等待自动尝试",
    val homeScene: String = "",
    val homeHash: String = "",
    val awaitingLocalReview: Boolean = false,
    val automaticFailures: Int = 0,
    val sceneVersion: Int = 1,
    val nodeTarget: TargetSelector? = null,
    val nodeScene: String = "",
    val contextHash: String = "",
    val tapMethod: TapMethod = TapMethod.AUTO,
    val retryAfter: Long = 0,
    val userAdjusted: Boolean = false,
    val learningRevision: Long = 0,
    val manualConfirmation: VisualConfirmation? = null,
) {
    fun behavior() = digest("$packageName|$versionCode|$scene|$target|$targetHash|$label" +
        (if(sceneVersion==1 && nodeTarget==null && nodeScene.isEmpty() && contextHash.isEmpty()) "" else "|$sceneVersion|$nodeTarget|$nodeScene|$contextHash") +
        (if(tapMethod==TapMethod.AUTO) "" else "|tap:$tapMethod") + (if(userAdjusted) "|manual" else "") +
        if(learningRevision==0L) "" else "|revision:$learningRevision")
    fun successfulSessions() = evidence.filter { it.behavior == behavior() && it.earlyClick && it.normalHome && it.sameApplication }.map { it.session }.distinct().size
    fun manuallyConfirmed() = manualConfirmation?.let {it.behavior==behavior() && it.at>0}==true
    fun active() = state == VisualRuleState.ACTIVE && (successfulSessions() >= 2 || manuallyConfirmed())
}
@Serializable data class VisualBundle(val format: String = "TiaoTiaoVisualRules", val schemaVersion: Int = 2,
    val rules: List<VisualRule>)
@Serializable data class VisualSignals(val advertisement: Boolean, val countdown: Int?, val skips: List<Area>, val home: Boolean,
    val explicitSkips: List<Area> = emptyList())

object VisualPolicy {
    const val MAX_RULES=500
    const val MAX_BYTES=4_194_304
    /** A wrong automatic target stops after this many consecutive failures until the user acts. */
    const val MAX_AUTOMATIC_FAILURES=3
    /** Sessions that skip clicking to time a natural ending; afterwards clicking resumes without timing proof. */
    const val MAX_BASELINE_OBSERVATIONS=2
    fun confirm(rule: VisualRule,now: Long): VisualRule {
        require(now>0)
        val revised=rule.copy(learningRevision=rule.learningRevision+1)
        return revised.copy(state=VisualRuleState.ACTIVE,manualConfirmation=VisualConfirmation(revised.behavior(),now),
            awaitingLocalReview=false,retryAfter=0,automaticFailures=0,lastResult="用户已确认方案正确，已启用")
    }
    private fun binaryHash(value: String) = value.matches(Regex("[01]{64}"))
    private fun distance(a: String, b: String) = a.zip(b).count { it.first != it.second }
    fun userPaused(rule: VisualRule) = rule.state==VisualRuleState.PAUSED && rule.lastResult.startsWith("用户")
    fun canRelearn(rule: VisualRule,now: Long) = !userPaused(rule) && (rule.state!=VisualRuleState.PAUSED || now>=rule.retryAfter)
    fun pauseAutomatically(rule: VisualRule,message: String,now: Long): VisualRule {
        if(userPaused(rule)) return rule
        val failures=(rule.automaticFailures+1).coerceAtMost(100)
        if(failures>=MAX_AUTOMATIC_FAILURES) return rule.copy(state=VisualRuleState.PAUSED,automaticFailures=failures,retryAfter=Long.MAX_VALUE,
            lastResult="连续 $failures 次自动检查未通过，已停止自动重试；请调整位置或重新学习")
        val backoff=if(failures==1) 15_000L else 60_000L
        return rule.copy(state=VisualRuleState.PAUSED,automaticFailures=failures,retryAfter=now+backoff,lastResult=message)
    }
    /** A literal "跳过" at the target is never held back for a timing-only session: closing right after that
     * label is visible is its own timing evidence ("explicit_label"). */
    fun observeNaturalEnding(rule: VisualRule,countdown: Int?,baseline: Long,observations: Int,explicitLabel: Boolean=false) =
        !explicitLabel && !rule.active() && countdown==null && baseline==0L && observations<MAX_BASELINE_OBSERVATIONS && rule.evidence.any {it.timingBasis=="unknown"}
    /** Fresh AI recognition is required to recover an automatically paused target. */
    fun replaceProposal(old: VisualRule?,incoming: VisualRule,now: Long): VisualRule? {
        if(old==null) return incoming
        if(old.active() || !canRelearn(old,now)) return null
        if(old.state!=VisualRuleState.PAUSED && !old.awaitingLocalReview) return null
        return incoming.copy(id=old.id,automaticFailures=old.automaticFailures,retryAfter=0,learningRevision=old.learningRevision)
    }
    /** OCR text may be much tighter than a model's button box; compare centres, not equal edges. */
    fun controlTarget(expected: Area, controls: List<Area>): Area? {
        val x=(expected.left+expected.right)/2;val y=(expected.top+expected.bottom)/2
        val matching=controls.filter { area -> area.valid() && area.right-area.left<=.4f && area.bottom-area.top<=.18f &&
            area.left<expected.right && area.right>expected.left && area.top<expected.bottom && area.bottom>expected.top &&
            kotlin.math.abs((area.left+area.right)/2-x)<=.025f && kotlin.math.abs((area.top+area.bottom)/2-y)<=.025f }
        val precise=matching.minByOrNull {(it.right-it.left)*(it.bottom-it.top)} ?: return null
        val px=(precise.left+precise.right)/2;val py=(precise.top+precise.bottom)/2
        // Nested OCR/node boxes describe one control. Separate neighbouring controls are ambiguous.
        return precise.takeIf {matching.all {px in it.left..it.right && py in it.top..it.bottom}}
    }
    /** Only an informative, stable home patch distinct from the ad can become a template.
     * The caller may persist it only after an independent AI verdict accepts the home frames. */
    fun homeTemplate(before: String, first: String, second: String): String {
        if (!listOf(before,first,second).all(::binaryHash) || first.count { it=='1' } !in 4..60 ||
            second.count { it=='1' } !in 4..60) return ""
        return second.takeIf { distance(first,second)<=3 && distance(before,first)>=10 && distance(before,second)>=10 }.orEmpty()
    }
    fun matchesHome(rule: VisualRule, before: String, first: String, second: String): Boolean =
        rule.active() && binaryHash(rule.homeHash) && homeTemplate(before,first,second).isNotEmpty() &&
            distance(rule.homeHash,first)<=3 && distance(rule.homeHash,second)<=3
    fun clickableTarget(screen: ScreenSnapshot, area: Area): ScreenNode? {
        val x=(area.left+area.right)/2;val y=(area.top+area.bottom)/2
        return (if(screen.complete) screen.candidates() else AdRecognition.candidates(screen)).filter { n ->
            val b=n.bounds
            b.right-b.left<=.25f && b.bottom-b.top<=.15f && x in b.left..b.right && y in b.top..b.bottom
        }.singleOrNull()
    }
    /** An image supplies a touch point, not proof that a nearby accessibility action works. */
    fun nativeTarget(rule: VisualRule, screen: ScreenSnapshot, area: Area): ScreenNode? = when(rule.tapMethod) {
        TapMethod.GESTURE -> null
        TapMethod.AUTO -> clickableTarget(screen,area)
        TapMethod.ACCESSIBILITY -> rule.nodeTarget?.takeIf {AdRecognition.matchesNode(it,screen)}?.let {target ->
            screen.nodes.singleOrNull {it.path==target.path}
        }
    }
    /** Changing the action invalidates its old evidence. Never resume a user-disabled target. */
    fun migrateExecution(rule: VisualRule): VisualRule {
        if(rule.tapMethod!=TapMethod.AUTO) return rule
        val retry=rule.nodeTarget==null && rule.state==VisualRuleState.PAUSED && rule.automaticFailures<=1 &&
            rule.lastResult=="广告或目标仍存在，已暂停"
        val paused=rule.state==VisualRuleState.PAUSED && !retry
        return rule.copy(tapMethod=if(rule.nodeTarget==null) TapMethod.GESTURE else TapMethod.ACCESSIBILITY,
            state=if(paused) VisualRuleState.PAUSED else VisualRuleState.CANDIDATE,evidence=emptyList(),homeScene="",homeHash="",
            automaticFailures=if(retry) 0 else rule.automaticFailures,
            lastResult=if(paused) rule.lastResult else "点击方式已更新，等待自动检查")
    }
    fun validate(rule: VisualRule) {
        require(rule.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(rule.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && rule.versionCode >= 0)
        require(rule.scene.matches(Regex("[a-f0-9]{64}")) && rule.target.valid())
        require(rule.targetHash.matches(Regex("[01]{64}")) && rule.label in setOf("skip", "close_ad"))
        require(rule.source.length <= 120 && rule.evidence.size <= 20)
        require(rule.automaticFailures in 0..100)
        require(rule.retryAfter>=0)
        require(rule.learningRevision>=0)
        rule.manualConfirmation?.let {require(it.behavior.matches(Regex("[a-f0-9]{64}")) && it.at>0)}
        require(rule.sceneVersion in 1..2)
        require(rule.nodeScene.isEmpty() || rule.nodeScene.matches(Regex("[a-f0-9]{64}")))
        require(rule.contextHash.isEmpty() || binaryHash(rule.contextHash))
        rule.nodeTarget?.let {
            require(it.bounds.valid() && it.bounds.near(rule.target) && it.path.matches(Regex("0(?:\\.\\d{1,6}){0,25}")))
            require(it.resourceId.length<=240 && it.className.length<=160 && it.label.length<=160)
        }
        require(rule.homeScene.isEmpty() || rule.homeScene.matches(Regex("[a-f0-9]{64}")))
        require(rule.homeHash.isEmpty() || binaryHash(rule.homeHash))
        require(rule.target.right - rule.target.left <= .4f && rule.target.bottom - rule.target.top <= .18f)
        if (rule.state == VisualRuleState.ACTIVE) require(rule.active())
    }
    fun canAttempt(rule: VisualRule, screen: ScreenSnapshot, signals: VisualSignals, patchHash: String, contextHash: String=""): Boolean {
        if (rule.state == VisualRuleState.PAUSED || rule.awaitingLocalReview ||
            (rule.sceneVersion==1 && !screen.complete) || !AdRecognition.matchesScene(rule,screen) || signals.home) return false
        val node=rule.nodeTarget?.let { rule.nodeScene==AdRecognition.nodeScene(screen) && AdRecognition.matchesNode(it,screen) }==true
        val context=binaryHash(rule.contextHash) && binaryHash(contextHash) &&
            rule.contextHash.count {it=='1'} in 4..60 && contextHash.count {it=='1'} in 4..60 && distance(rule.contextHash,contextHash)<=4
        // A Jev-confirmed structural scene can supply ad context for an unlabeled dismiss icon. A literal
        // skip at the learned target is its own launch-screen evidence; most splashes show no "广告" badge.
        val explicit = controlTarget(rule.target,signals.explicitSkips)!=null
        if (!signals.advertisement && !node && !context && !explicit && !(rule.sceneVersion==1 && signals.skips.isNotEmpty() && signals.countdown != null)) return false
        val labelled = controlTarget(rule.target,signals.skips)!=null
        val patch = patchHash.length == 64 && patchHash.zip(rule.targetHash).count { it.first != it.second } <= 5
        val informative = rule.targetHash.count { it=='1' } in 4..60 && patchHash.count { it=='1' } in 4..60
        return labelled || node || (rule.userAdjusted && context && signals.advertisement) || ((signals.advertisement || context) && patch && informative)
    }
    fun record(rule: VisualRule, session: String, early: Boolean, home: Boolean, sameApp: Boolean, now: Long, timingBasis: String = "countdown"): VisualRule {
        if (!home || !sameApp) return pauseAutomatically(rule,"自动检查未通过，稍后自动重新识别",now)
        require(timingBasis in setOf("countdown","observed_baseline","explicit_label","unknown"))
        val proof = AutoEvidence(session, rule.behavior(), now, early, home, sameApp,timingBasis)
        val next = rule.copy(evidence = (rule.evidence.filterNot { it.session == session } + proof).takeLast(20),
            automaticFailures = if (early) 0 else rule.automaticFailures)
        return next.copy(state = if (next.successfulSessions() >= 2 || next.manuallyConfirmed()) VisualRuleState.ACTIVE else VisualRuleState.CANDIDATE,
            lastResult = if(next.manuallyConfirmed()) "用户确认仍有效，本次结果检查完成" else if (early) "自动检查通过（${next.successfulSessions()}/2）" else "页面已变化，时序证据不足，保留候选")
    }
    fun decode(text: String): VisualBundle {
        require(text.toByteArray().size <= MAX_BYTES)
        val bundle = ruleJson.decodeFromString<VisualBundle>(text)
        require(bundle.format == "TiaoTiaoVisualRules" && bundle.schemaVersion in 1..2 && bundle.rules.size <= MAX_RULES)
        require(bundle.rules.map { it.id }.distinct().size == bundle.rules.size)
        bundle.rules.forEach(::validate)
        return bundle
    }
    fun import(text: String): List<VisualRule> = decode(text).rules.map {
        it.copy(state = VisualRuleState.CANDIDATE, evidence = emptyList(), manualConfirmation=null,homeScene = "", homeHash = "", awaitingLocalReview = true, lastResult = "已导入，等待本机 AI 重新识别")
    }
}
