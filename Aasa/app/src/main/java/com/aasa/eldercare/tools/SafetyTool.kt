package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Combines Gemma's [AgentAction.riskLevel] with a deterministic
 * keyword-based check on the user message / assistant response /
 * arguments. We deliberately *do not* diagnose – we only describe the
 * risk level and suggest the next step.
 */
class SafetyTool : AgentTool {
    override val name: String = ToolNames.SAFETY

    override suspend fun execute(action: AgentAction): ToolResult {
        val haystack = buildHaystack(action).lowercase()
        val containsHighRiskPhrase = HIGH_RISK_PHRASES.any { haystack.contains(it) }

        val effectiveRisk = when {
            containsHighRiskPhrase -> RISK_HIGH
            else -> action.riskLevel.uppercase()
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
                "matchedHighRiskPhrase" to containsHighRiskPhrase
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

        private val HIGH_RISK_PHRASES = listOf(
            "chest pain",
            "cannot breathe",
            "can't breathe",
            "fell down",
            "fainted",
            "severe weakness"
        )
    }
}
