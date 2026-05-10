package com.aasa.eldercare.agent

import com.aasa.eldercare.network.AgentMessageResponse
import com.aasa.eldercare.tools.ToolResult
import com.google.gson.JsonElement

/**
 * Plain Kotlin model the rest of the app reasons about. We deliberately
 * use [Any] for argument values (instead of Gson's [JsonElement]) so the
 * tools layer never has to know about the wire format.
 */
data class AgentAction(
    val intent: String,
    val riskLevel: String,
    val tool: String,
    val arguments: Map<String, Any?>,
    val assistantResponse: String,
    val rawResponse: String? = null
)

/**
 * Combined output of one agent turn: what Gemma decided ([action]) and
 * what the matching local tool actually did ([toolResult]).
 */
data class AgentExecutionResult(
    val action: AgentAction,
    val toolResult: ToolResult
)

/**
 * Map the network DTO to the domain [AgentAction], filling in safe
 * defaults for any field the server omitted.
 */
fun AgentMessageResponse.toAgentAction(): AgentAction = AgentAction(
    intent = intent?.takeIf { it.isNotBlank() } ?: DEFAULT_INTENT,
    riskLevel = riskLevel?.takeIf { it.isNotBlank() } ?: DEFAULT_RISK_LEVEL,
    tool = tool?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL,
    arguments = arguments?.mapValues { (_, v) -> v.toKotlinAnyOrNull() } ?: emptyMap(),
    assistantResponse = assistantResponse.orEmpty(),
    rawResponse = rawResponse
)

private const val DEFAULT_INTENT = "UNKNOWN"
private const val DEFAULT_RISK_LEVEL = "LOW"
private const val DEFAULT_TOOL = "ChatTool"

/**
 * Recursively unwrap a Gson [JsonElement] into the closest Kotlin
 * primitive / collection so tools can read [AgentAction.arguments]
 * with `as String`, `as Number`, etc.
 */
private fun JsonElement.toKotlinAnyOrNull(): Any? = when {
    isJsonNull -> null
    isJsonPrimitive -> {
        val p = asJsonPrimitive
        when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asNumber
            p.isString -> p.asString
            else -> p.asString
        }
    }
    isJsonArray -> asJsonArray.map { it.toKotlinAnyOrNull() }
    isJsonObject -> asJsonObject.entrySet()
        .associate { (k, v) -> k to v.toKotlinAnyOrNull() }
    else -> null
}
