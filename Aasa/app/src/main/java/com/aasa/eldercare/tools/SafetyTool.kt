package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Combines Gemma's [AgentAction.riskLevel] with a deterministic
 * keyword-based check on the user message / assistant response /
 * arguments. We deliberately *do not* diagnose – we only describe the
 * risk level and suggest the next step.
 *
 * Phrase lists live in [SafetyKeywords] so the orchestrator and this
 * tool can never disagree.
 */
class SafetyTool : AgentTool {
    override val name: String = ToolNames.SAFETY

    override suspend fun execute(action: AgentAction): ToolResult {
        val haystack = buildHaystack(action)
        val containsHigh = SafetyKeywords.containsHighRiskPhrase(haystack)
        val containsMedium = SafetyKeywords.containsMediumRiskPhrase(haystack)
        val baseRisk = action.riskLevel.uppercase()

        // Risk aggregation: take the *worst* of (deterministic phrase
        // scan, Gemma's risk level). We never downgrade.
        val effectiveRisk = when {
            containsHigh || baseRisk == RISK_HIGH -> RISK_HIGH
            containsMedium || baseRisk == RISK_MEDIUM -> RISK_MEDIUM
            else -> baseRisk
        }

        val message = when (effectiveRisk) {
            RISK_HIGH ->
                "High safety concern detected. Recommend contacting emergency help or trusted contact immediately."
            RISK_MEDIUM ->
                "Medium safety concern detected. Ask permission before alerting trusted contact."
            else ->
                "Low safety concern. Continuing normally."
        }

        return ToolResult.ok(
            message = message,
            data = mapOf(
                "originalRiskLevel" to action.riskLevel,
                "effectiveRiskLevel" to effectiveRisk,
                "matchedHighRiskPhrase" to containsHigh,
                "matchedMediumRiskPhrase" to containsMedium
            )
        )
    }

    private fun buildHaystack(action: AgentAction): String {
        val argText = action.arguments.values
            .filterNotNull()
            .joinToString(separator = " ")
        return listOf(
            action.assistantResponse,
            argText
        ).filter { it.isNotBlank() }.joinToString(separator = " ")
    }

    companion object {
        private const val RISK_HIGH = "HIGH"
        private const val RISK_MEDIUM = "MEDIUM"
    }
}
