package com.haibo.tiaotiao.core

import kotlinx.serialization.encodeToString

object RuleCodec {
    const val MAX_BYTES = 1_048_576
    fun encode(rules: List<SkipRule>, now: Long) = ruleJson.encodeToString(RuleBundle(exportedAt = now, rules = rules))
    fun decode(text: String): RuleBundle {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "规则文件不得超过 1 MB" }
        val bundle = ruleJson.decodeFromString<RuleBundle>(text)
        require(bundle.schemaVersion == 1) { "不支持的规则格式版本" }
        require(bundle.rules.size <= 500) { "规则数量超出限制" }
        require(bundle.rules.map { it.id }.distinct().size == bundle.rules.size) { "规则 ID 重复" }
        bundle.rules.forEach(::validate)
        return bundle
    }
    fun validate(rule: SkipRule) {
        require(Regex("[A-Za-z0-9_.-]{1,100}").matches(rule.id)) { "规则 ID 无效" }
        require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(rule.packageName)) { "应用包名无效" }
        require(rule.versionCode >= 0 && rule.revision in 1..1_000_000)
        require(Regex("[a-f0-9]{64}").matches(rule.screenFingerprint)) { "界面指纹无效" }
        require(Regex("0(?:\\.\\d{1,3}){0,25}").matches(rule.target.path)) { "控件路径无效" }
        require(rule.target.resourceId.length <= 240 && rule.target.className.length <= 160 && rule.source.length <= 120)
        require(rule.target.bounds.valid() && rule.target.label.length <= 160 && SignalText.hasSkip(rule.target.label)) { "目标不是有效跳过控件" }
        if (rule.status == RuleStatus.VERIFIED) require(rule.isVerified()) { "规则验证凭据与内容不一致" }
    }
    // External verification history is informational. Never turn imported bytes into executable approval.
    fun importRules(text: String) = decode(text).rules.map {
        it.copy(status = RuleStatus.IMPORTED, trial = null, validation = null)
    }
}
