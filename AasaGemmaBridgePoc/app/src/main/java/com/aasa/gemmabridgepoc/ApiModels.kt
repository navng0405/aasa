package com.aasa.gemmabridgepoc

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class AgentMessageRequest(
    @SerializedName("message")
    val message: String
)

data class AgentMessageResponse(
    @SerializedName("intent")
    val intent: String? = null,
    @SerializedName("riskLevel")
    val riskLevel: String? = null,
    @SerializedName("tool")
    val tool: String? = null,
    @SerializedName("arguments")
    val arguments: Map<String, JsonElement>? = null,
    @SerializedName("assistantResponse")
    val assistantResponse: String? = null,
    @SerializedName("rawResponse")
    val rawResponse: String? = null
)
