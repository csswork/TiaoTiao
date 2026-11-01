package com.haibo.tiaotiao.core

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val ruleJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && right > left && bottom > top
    fun near(other: Area) = listOf(left - other.left, top - other.top, right - other.right, bottom - other.bottom).all { kotlin.math.abs(it) <= .025f }
}

@Serializable
data class ScreenNode(
    val path: String,
    val resourceId: String = "",
    val className: String,
    val label: String = "",
    val clickable: Boolean,
    val bounds: Area,
)

@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val versionCode: Long,
    val width: Int,
    val height: Int,
    val nodes: List<ScreenNode>,
    val capturedAt: Long,
    val windowId: Int,
    val complete: Boolean = true,
) {
    // Only structural data and allowlisted semantic signals contribute, never ad creative copy.
    fun fingerprint(): String = digest(buildString {
        append("$packageName|$versionCode|$width|$height\n")
        nodes.forEach {
            append("${it.path}|${it.resourceId}|${it.className}|${it.clickable}|${SignalText.canonical(it.label)}\n")
        }
    })
    fun candidates() = if (complete) nodes.filter { it.clickable && it.bounds.valid() }.take(60) else emptyList()
}

object SignalText {
    private const val SKIP_WORD = "(?:跳过(?:广告)?|关闭广告|skip(?:\\s+ad)?)"
    private const val COUNTDOWN = "[（(\\[]?\\s*\\d+\\s*(?:s|秒)?\\s*[)）\\]]?"
    private val skip = Regex("^(?:$SKIP_WORD(?:\\s*[|｜·:：]?\\s*$COUNTDOWN)?|$COUNTDOWN\\s*(?:后)?\\s*[|｜·:：]?\\s*$SKIP_WORD)$", RegexOption.IGNORE_CASE)
    fun isSkip(value: String) = skip.matches(value.trim().take(80))
    fun sanitize(value: String): String {
        val text = value.trim().take(200)
        return when {
            isSkip(text) -> "跳过"
            text == "广告" || text.equals("advertisement", true) || text == "AD" -> "广告"
            text in setOf("跳过登录", "跳过引导", "跳过教程", "安装", "下载", "购买", "打开", "立即查看") -> text
            else -> ""
        }
    }
    fun canonical(label: String) = label.split(" | ").map(::sanitize).filter(String::isNotBlank).distinct().sorted().joinToString("|")
    fun hasSkip(label: String): Boolean {
        val parts = label.split(" | ").filter(String::isNotBlank)
        return parts.isNotEmpty() && parts.all(::isSkip)
    }
}

@Serializable enum class RuleStatus { PENDING, TRIALLED, VERIFIED, DISABLED, IMPORTED }
@Serializable
data class TargetSelector(val path: String, val resourceId: String, val className: String, val label: String, val bounds: Area)
@Serializable
data class TrialReceipt(val id: String, val behaviorDigest: String, val acceptedAt: Long)
@Serializable
data class HumanValidation(val trialId: String, val behaviorDigest: String, val confirmedAt: Long)
@Serializable
data class SkipRule(
    val id: String = UUID.randomUUID().toString(),
    val revision: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val screenFingerprint: String,
    val target: TargetSelector,
    val status: RuleStatus = RuleStatus.PENDING,
    val source: String,
    val createdAt: Long,
    val trial: TrialReceipt? = null,
    val validation: HumanValidation? = null,
) {
    fun behaviorDigest(): String = digest("$packageName|$versionCode|$screenFingerprint|${ruleJson.encodeToString(target)}|$revision")
    fun isVerified() = status == RuleStatus.VERIFIED && validation?.behaviorDigest == behaviorDigest() &&
        trial?.behaviorDigest == behaviorDigest() && validation.trialId == trial.id
}

@Serializable
data class RuleBundle(val schemaVersion: Int = 1, val exportedAt: Long, val rules: List<SkipRule>)

sealed interface VerifiedSceneLookup {
    data class Matched(val rule: SkipRule) : VerifiedSceneLookup
    data object UnmatchedTarget : VerifiedSceneLookup
    data object Unknown : VerifiedSceneLookup
}

object RuleEngine {
    fun compile(screen: ScreenSnapshot, candidate: ScreenNode, source: String, now: Long): SkipRule {
        require(candidate in screen.candidates() && SignalText.hasSkip(candidate.label)) { "目标不是当前页面中可确认的跳过控件" }
        return SkipRule(packageName = screen.packageName, versionCode = screen.versionCode,
            screenFingerprint = screen.fingerprint(),
            target = TargetSelector(candidate.path, candidate.resourceId, candidate.className, candidate.label, candidate.bounds),
            source = source, createdAt = now)
    }
    fun locate(rule: SkipRule, screen: ScreenSnapshot): ScreenNode? {
        if (!screen.complete) return null
        if (rule.packageName != screen.packageName || rule.versionCode != screen.versionCode ||
            rule.screenFingerprint != screen.fingerprint()) return null
        val targets = screen.candidates().filter {
            it.path == rule.target.path && it.resourceId == rule.target.resourceId &&
                it.className == rule.target.className && SignalText.hasSkip(it.label) &&
                SignalText.canonical(it.label) == SignalText.canonical(rule.target.label) && it.bounds.near(rule.target.bounds)
        }
        return targets.singleOrNull()
    }
    /** Knowing the scene and being able to click it are separate decisions. Position changes
     * during launch must not send a known scene back through the model or weaken the selector. */
    fun resolveVerified(rules: List<SkipRule>, screen: ScreenSnapshot): VerifiedSceneLookup {
        val fingerprint = screen.fingerprint()
        val known = rules.filter { it.isVerified() && it.packageName == screen.packageName &&
            it.versionCode == screen.versionCode && it.screenFingerprint == fingerprint }
        val match = known.filter { locate(it, screen) != null }.singleOrNull()
        return when {
            match != null -> VerifiedSceneLookup.Matched(match)
            known.isNotEmpty() -> VerifiedSceneLookup.UnmatchedTarget
            else -> VerifiedSceneLookup.Unknown
        }
    }
    fun autoMatch(rules: List<SkipRule>, screen: ScreenSnapshot): SkipRule? =
        (resolveVerified(rules, screen) as? VerifiedSceneLookup.Matched)?.rule
    fun recordTrial(rule: SkipRule, accepted: Boolean, now: Long): SkipRule {
        require(accepted) { "系统未接受点击，不能进入结果确认" }
        require(rule.status in setOf(RuleStatus.PENDING, RuleStatus.IMPORTED, RuleStatus.TRIALLED))
        return rule.copy(status = RuleStatus.TRIALLED,
            trial = TrialReceipt(UUID.randomUUID().toString(), rule.behaviorDigest(), now), validation = null)
    }
    fun confirm(rule: SkipRule, success: Boolean, now: Long): SkipRule {
        if (!success) return rule.copy(status = RuleStatus.DISABLED, validation = null)
        val receipt = requireNotNull(rule.trial) { "需要先试跳" }
        require(rule.status == RuleStatus.TRIALLED && receipt.behaviorDigest == rule.behaviorDigest()) { "规则已变化，请重新试跳" }
        require(now - receipt.acceptedAt in 0..3_600_000L) { "试跳记录已过期，请重新试跳" }
        return rule.copy(status = RuleStatus.VERIFIED,
            validation = HumanValidation(receipt.id, rule.behaviorDigest(), now))
    }
}
