package com.aasa.eldercare.model

import com.aasa.eldercare.network.AgentMessageResponse
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * Phase 9 helper. Builds the system+user prompt for on-device Gemma 4 (LiteRT-LM)
 * and parses Gemma's output back into [AgentMessageResponse] — the same shape the
 * FastAPI bridge produces, so downstream `AgentOrchestrator` code stays unchanged.
 *
 * The system prompt mirrors `aasa-gemma-server/prompt_builder.py` but is trimmed
 * aggressively for the Pixel 4a's slower decode. Anything not strictly required
 * for tool routing has been removed; the server prompt stays canonical and richer
 * for the bridge path.
 */
internal object OnDevicePromptBuilder {

    private val gson: Gson = Gson()

    fun build(userMessage: String): String = """
You are Aasa, a Gemma 4 powered elder safety agent running on-device.

Return ONLY a single JSON object. No prose. No markdown. No code fences.

Schema:
{
  "intent": "CHAT | SAVE_MEMORY | LOG_MEDICATION | CHECK_MEDICATION | CREATE_REMINDER | CALL_CONTACT | SAFETY_CHECK | ALERT_TRUSTED_CONTACT | ANALYZE_SCAM | FALL_TRIAGE | MOBILITY_CHECK",
  "riskLevel": "LOW | MEDIUM | HIGH",
  "tool": "ChatTool | MemoryTool | MedicationTool | ReminderTool | TrustedContactTool | SafetyTool | ScamShieldTool | FallTriageTool | MobilityShieldTool",
  "arguments": {},
  "assistantResponse": "short, warm, elder-friendly reply"
}

Rules:
- Never diagnose any medical condition.
- HIGH risk for: chest pain, can't breathe, fell, fainted, severe weakness, bleeding, head injury.
- MEDIUM risk for: dizzy, weak, missed medicine, confused, lonely.
- LOW risk for everything else.
- "I took my <medicine>" → LOG_MEDICATION / MedicationTool, arguments.medicineName.
- "Did I take my medicine?" → CHECK_MEDICATION / MedicationTool.
- "My <person>'s <fact>" → SAVE_MEMORY / MemoryTool, arguments.title and arguments.value.
- "Call <name>" → CALL_CONTACT / TrustedContactTool, arguments.contactName.
- Suspicious SMS / scam text → ANALYZE_SCAM / ScamShieldTool, arguments.messageText.

User message:
$userMessage
""".trimIndent()

    /**
     * Robustly parse a Gemma JSON reply. Real-world LLM output sometimes wraps
     * JSON in ```json fences or trails a sentence; we tolerate both.
     */
    fun parse(rawModelOutput: String): AgentMessageResponse {
        val cleaned = stripFences(rawModelOutput)
        val jsonSlice = extractFirstJsonObject(cleaned) ?: cleaned

        val element: JsonElement = try {
            gson.fromJson(jsonSlice, JsonElement::class.java)
        } catch (e: JsonSyntaxException) {
            // Fall back to a ChatTool reply so the UI still gets something useful.
            return AgentMessageResponse(
                intent = "CHAT",
                riskLevel = "LOW",
                tool = "ChatTool",
                arguments = null,
                assistantResponse = rawModelOutput.trim().ifBlank {
                    "Sorry — I had trouble understanding that. Could you say it again?"
                },
                rawResponse = rawModelOutput
            )
        }

        if (!element.isJsonObject) {
            return AgentMessageResponse(
                intent = "CHAT",
                riskLevel = "LOW",
                tool = "ChatTool",
                arguments = null,
                assistantResponse = rawModelOutput.trim(),
                rawResponse = rawModelOutput
            )
        }

        val obj: JsonObject = element.asJsonObject

        val argumentsMap: Map<String, JsonElement>? = obj.get("arguments")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.entrySet()
            ?.associate { (k, v) -> k to v }

        return AgentMessageResponse(
            intent = obj.get("intent")?.takeIf { !it.isJsonNull }?.asString,
            riskLevel = obj.get("riskLevel")?.takeIf { !it.isJsonNull }?.asString,
            tool = obj.get("tool")?.takeIf { !it.isJsonNull }?.asString,
            arguments = argumentsMap,
            assistantResponse = obj.get("assistantResponse")
                ?.takeIf { !it.isJsonNull }
                ?.asString,
            rawResponse = rawModelOutput
        )
    }

    private fun stripFences(text: String): String {
        // Drop ```json ... ``` or ``` ... ``` wrappers.
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val match = fence.find(text)
        return (match?.groupValues?.getOrNull(1) ?: text).trim()
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
