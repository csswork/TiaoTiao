package com.haibo.tiaotiao.core

import kotlinx.serialization.Serializable

@Serializable
enum class JevProvider(val displayName: String, val endpoint: String, val defaultModel: String) {
    TYPE_SAFE("TypeSafe 直连", "https://api.typesafe.ai/v1/systemone", "jev-latest"),
    VERCEL_AI_GATEWAY("Vercel AI Gateway", "https://ai-gateway.vercel.sh/typesafe/v1/systemone", "typesafe-ai/jev");

    fun validateModel(model: String) {
        val pattern = if (this == TYPE_SAFE) "jev-[A-Za-z0-9._-]{1,60}" else "typesafe-ai/jev(?:-[A-Za-z0-9._-]{1,60})?"
        require(model.matches(Regex(pattern))) {
            "${displayName} 的模型名格式不正确，建议使用 $defaultModel"
        }
    }
    fun migrateModel(model: String): String = when (this) {
        TYPE_SAFE -> if (model == "typesafe-ai/jev") defaultModel else model.removePrefix("typesafe-ai/")
        VERCEL_AI_GATEWAY -> if (model == "jev-latest" || model == "jev-preview") defaultModel
            else if (model.startsWith("jev-")) "typesafe-ai/$model" else model
    }
}
