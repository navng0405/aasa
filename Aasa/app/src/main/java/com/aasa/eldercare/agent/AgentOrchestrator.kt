package com.aasa.eldercare.agent

import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.model.ModelRunner
import com.aasa.eldercare.tools.IntentKeywords
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
     *   4. Apply *deterministic overrides* on the user message:
 *        - emergency / symptom phrases     -> SafetyTool (HIGH / MEDIUM)
 *        - "did I take my medicine?"       -> MedicationTool / CHECK_MEDICATION
 *        - "I took my medicine"            -> MedicationTool / LOG_MEDICATION
 *        - "X's birthday is …" / "remember that …" / "my favorite …"
 *                                          -> MemoryTool / SAVE_MEMORY
 *      Elder-care behavior must not depend on prompt-time luck; if
 *      Gemma misclassifies, the device corrects routing locally before
 *      the tool runs.
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
        val safeAction = applyDeterministicOverrides(message, parsedAction)
        val toolResult = toolRegistry.execute(safeAction)
        val finalAction = if (toolResult.success && toolResult.message.isNotBlank()) {
            safeAction.copy(assistantResponse = toolResult.message)
        } else {
            safeAction
        }

        conversationRepository.saveAssistantMessage(
            message = finalAction.assistantResponse,
            intent = finalAction.intent,
            riskLevel = finalAction.riskLevel,
            tool = finalAction.tool
        )

        return AgentExecutionResult(action = finalAction, toolResult = toolResult)
    }

    /**
     * Run all on-device override rules. Order matters: safety always
     * wins over CRUD intent overrides. The original user message is
     * stashed in `arguments["userMessage"]` so downstream tools can
     * re-scan it.
     */
    private fun applyDeterministicOverrides(
        userMessage: String,
        action: AgentAction
    ): AgentAction {
        val enrichedArgs = action.arguments + mapOf("userMessage" to userMessage)

        return when {
            // --- 1. Safety overrides (highest priority) -----------------
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

            // --- 2. Medication intent overrides -------------------------
            IntentKeywords.isMedicationCheckQuery(userMessage) -> action.copy(
                intent = INTENT_CHECK_MEDICATION,
                tool = ToolNames.MEDICATION,
                riskLevel = action.riskLevel.ifBlank { RISK_LOW },
                assistantResponse = OVERRIDE_CHECK_RESPONSE,
                arguments = enrichedArgs
            )
            IntentKeywords.isMedicationLogStatement(userMessage) -> action.copy(
                intent = INTENT_LOG_MEDICATION,
                tool = ToolNames.MEDICATION,
                riskLevel = action.riskLevel.ifBlank { RISK_LOW },
                arguments = enrichedArgs
            )

            // --- 3. Memory save override --------------------------------
            IntentKeywords.isMemorySaveStatement(userMessage) -> action.copy(
                intent = INTENT_SAVE_MEMORY,
                tool = ToolNames.MEMORY,
                riskLevel = action.riskLevel.ifBlank { RISK_LOW },
                assistantResponse = action.assistantResponse
                    .ifBlank { OVERRIDE_MEMORY_RESPONSE },
                // Stash the original message + a derived coarse type so
                // MemoryTool always produces a useful row even when
                // Gemma left arguments empty.
                arguments = enrichedArgs + mapOf(
                    "note" to userMessage,
                    "memoryType" to IntentKeywords.deriveMemoryType(userMessage)
                )
            )

            else -> action.copy(arguments = enrichedArgs)
        }
    }

    companion object {
        private const val INTENT_SAFETY_CHECK = "SAFETY_CHECK"
        private const val INTENT_CHECK_MEDICATION = "CHECK_MEDICATION"
        private const val INTENT_LOG_MEDICATION = "LOG_MEDICATION"
        private const val INTENT_SAVE_MEMORY = "SAVE_MEMORY"
        private const val RISK_HIGH = "HIGH"
        private const val RISK_MEDIUM = "MEDIUM"
        private const val RISK_LOW = "LOW"

        // Replaces Gemma's chatty clarification ("please tell me which
        // medicine ...") for clearly-a-check queries. The actual answer
        // comes from MedicationTool's tool-result message.
        private const val OVERRIDE_CHECK_RESPONSE =
            "Let me check your medication log."

        // Used only when Gemma also left the assistant response blank.
        private const val OVERRIDE_MEMORY_RESPONSE =
            "Saved that to your memories."
    }
}
