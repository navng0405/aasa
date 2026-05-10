package com.aasa.eldercare.agent

import com.google.gson.JsonElement

data class AgentAction(
    val intent: String,
    val riskLevel: String,
    val tool: String,
    val arguments: Map<String, JsonElement> = emptyMap(),
    val assistantResponse: String
)
