package com.aasa.eldercare.agent

import com.aasa.eldercare.model.ModelRunner
import com.aasa.eldercare.tools.SafetyKeywords
import com.aasa.eldercare.tools.ToolNames
import com.aasa.eldercare.tools.ToolRegistry

/**
 * Single entry point for "user said something" turns.
 *
 * The flow is intentionally small in Phase 3:
 *   1. Send the message to the local Gemma bridge via [ModelRunner].
 *   2. Convert the network DTO into a domain [AgentAction].
 *   3. Apply a defensive *safety override*: if the **user message**
 *      itself contains an obvious emergency or symptom phrase, force-
 *      route to [com.aasa.eldercare.tools.SafetyTool] regardless of
 *      what Gemma decided. This is critical: elder safety must never
 *      depend on prompt-time classification luck.
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
     * Belt-and-braces check on the *user input* itself.
     *
     *  - If the elder typed a known-emergency phrase (e.g. "cannot
     *    breathe", "chest pain") we override Gemma's routing to
     *    [ToolNames.SAFETY] with `riskLevel = HIGH`.
     *  - Otherwise, if the message contains a medium-tier symptom or
     *    missed-medication phrase (e.g. "feel weak", "missed my
     *    medicine") we override to [ToolNames.SAFETY] with
     *    `riskLevel = MEDIUM`.
     *  - In both cases we set `intent = SAFETY_CHECK` so the parsed
     *    response card on the home screen reflects the corrected
     *    classification.
     *
     * The original user message is also stashed in
     * `arguments["userMessage"]` so [com.aasa.eldercare.tools.SafetyTool]
     * can re-scan it.
     */
    private fun applySafetyOverride(
        userMessage: String,
        action: AgentAction
    ): AgentAction {
        val enrichedArgs = action.arguments + mapOf("userMessage" to userMessage)

        return when {
            SafetyKeywords.containsHighRiskPhrase(userMessage) -> action.copy(
                intent = INTENT_SAFETY_CHECK,
                tool = ToolNames.SAFETY,
                riskLevel = RISK_HIGH,
                arguments = enrichedArgs
            )
            SafetyKeywords.containsMediumRiskPhrase(userMessage) -> action.copy(
                intent = INTENT_SAFETY_CHECK,
                tool = ToolNames.SAFETY,
                riskLevel = RISK_MEDIUM,
                arguments = enrichedArgs
            )
            else -> action.copy(arguments = enrichedArgs)
        }
    }

    companion object {
        private const val INTENT_SAFETY_CHECK = "SAFETY_CHECK"
        private const val RISK_HIGH = "HIGH"
        private const val RISK_MEDIUM = "MEDIUM"
    }
}
