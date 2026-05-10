package com.aasa.eldercare.agent

import com.aasa.eldercare.model.ModelRunner
import com.aasa.eldercare.tools.ToolNames
import com.aasa.eldercare.tools.ToolRegistry

/**
 * Single entry point for "user said something" turns.
 *
 * The flow is intentionally small in Phase 3:
 *   1. Send the message to the local Gemma bridge via [ModelRunner].
 *   2. Convert the network DTO into a domain [AgentAction].
 *   3. Apply a defensive *safety override*: if the **user message**
 *      itself contains an obvious emergency phrase, force-route to
 *      [com.aasa.eldercare.tools.SafetyTool] regardless of what Gemma
 *      decided.
 *   4. Run the matching tool through [ToolRegistry].
 *   5. Return both the action and the tool result for the UI.
 */
class AgentOrchestrator(
    private val modelRunner: ModelRunner,
    private val toolRegistry: ToolRegistry
) {

    suspend fun handleUserMessage(message: String): AgentExecutionResult {
        val rawResponse = modelRunner.sendMessage(message)
        val parsedAction = rawResponse.toAgentAction()
        val safeAction = applySafetyOverride(message, parsedAction)
        val toolResult = toolRegistry.execute(safeAction)
        return AgentExecutionResult(action = safeAction, toolResult = toolResult)
    }

    /**
     * Defensive belt-and-braces check on the *user input* itself. If the
     * elder typed a known-emergency phrase but Gemma routed it elsewhere
     * (e.g. ChatTool), we override the routing to SafetyTool/HIGH and
     * stash the original message in arguments so SafetyTool can see it.
     */
    private fun applySafetyOverride(
        userMessage: String,
        action: AgentAction
    ): AgentAction {
        val lower = userMessage.lowercase()
        val matchesEmergency = HIGH_RISK_PHRASES.any { lower.contains(it) }
        val enrichedArgs = action.arguments + mapOf("userMessage" to userMessage)

        return if (matchesEmergency) {
            action.copy(
                tool = ToolNames.SAFETY,
                riskLevel = "HIGH",
                arguments = enrichedArgs
            )
        } else {
            action.copy(arguments = enrichedArgs)
        }
    }

    companion object {
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
