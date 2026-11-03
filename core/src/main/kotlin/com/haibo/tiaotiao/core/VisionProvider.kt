package com.haibo.tiaotiao.core

import kotlinx.serialization.Serializable

@Serializable
enum class VisionProvider(val displayName: String,val endpoint: String,val defaultModel: String) {
    JEV_OFFICIAL("Jev 官方直连","https://api.typesafe.ai/v1/systemone","jev-latest"),
    DEEPSEEK_OFFICIAL("DeepSeek 官方直连","https://api.deepseek.com/chat/completions","deepseek-flash"),
    VERCEL_AI_GATEWAY("Vercel 网关","https://ai-gateway.vercel.sh/v1/chat/completions","typesafe-ai/jev-1.14.0");

    fun validateModel(model: String) {
        if(this==JEV_OFFICIAL) require(model==defaultModel) {"Jev 官方图片模型请使用 jev-latest"}
        else if(this==DEEPSEEK_OFFICIAL) require(model==defaultModel) {"DeepSeek 官方图片模型请使用 deepseek-flash"}
        else {
            require(model.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))) {"请检查网关图片模型名称"}
            require(!model.startsWith("deepseek/")) {"DeepSeek 请使用官方直连及独立密钥"}
            if(model.startsWith("typesafe-ai/jev")) require(model==defaultModel) {"Jev 图片请使用 typesafe-ai/jev-1.14.0"}
        }
    }
}
