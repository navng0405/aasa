package com.aasa.eldercare.agent

import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.model.ModelRunner
import com.aasa.eldercare.tools.SafetyKeywords
import com.aasa.eldercare.tools.ToolNames
import com.aasa.eldercare.tools.ToolRegistry

/**
 * Single entry point for "user said something" turns.
 *
 * The flow:
 *   1. Persist the user message (USER role) into [ConversationRepository].
 *   2. Send the message to the local Gemma bridge via [ModelRunner].
 *   3. Convert the network DTO into a domain [AgentAction].
 *   4. Apply a defensive *safety override*: if the **user message**
 *      itself contains an obvious emergency or symptom phrase, force-
 *      route to [com.aasa.eldercare.tools.SafetyTool]. Elder safety must
 *      not depend on prompt-time classification luck.
 *   5. Run the matching tool through [ToolRegistry] (which is the layer
 *      that actually writes medications / memories / etc. to Room).
 *   6. Persist the post-override assistant response (ASSISTANT role)
 *      with intent / riskLevel / tool for audit.
 *   7. Return both the action and the tool result for the UI.
 */
class AgentOrchestrator(
    private val modelRunner: ModelRunner,
    private val toolRegistry: ToolRegistry,
    private val conversationRepository: ConversationRepository
) {

    suspend fun handleUserMessage(message: String): AgentExecutionResult {
        conversationRepository.saveUserMessage(message)

        val rawResponse = modelRunner.sendMessage(message)
        val parsedAction = rawResponse.toAgentAction()
        val safeAction = applySafetyOverride(message, parsedAction)
        val toolResult = toolRegistry.execute(safeAction)

        conversationRepository.saveAssistantMessage(
            message = safeAction.assistantResponse,
            intent = safeAction.intent,
            riskLevel = safeAction.riskLevel,
            tool = safeAction.tool
        )

        return AgentExecutionResult(action = safeAction, toolResult = toolResult)
    }

    /**
     * Belt-and-braces check on the *user input* itself.
     *
     *  - HIGH-risk phrase match -> force [ToolNames.SAFETY] + `HIGH`.
     *  - MEDIUM-risk phrase match -> force [ToolNames.SAFETY] + `MEDIUM`.
     *  - In both cases set `intent = SAFETY_CHECK` so the parsed
     *    response card reflects the corrected classification.
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
