package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Phase-3 mock medication tool. Reads `medicineName` and `status` out of
 * the action arguments and produces a confirmation string. No persistence
 * yet – Room DB is wired up in a later phase.
 */
class MedicationTool : AgentTool {
    override val name: String = ToolNames.MEDICATION

    override suspend fun execute(action: AgentAction): ToolResult {
        val medicineName = action.arguments.stringOrNull("medicineName") ?: DEFAULT_MEDICINE
        val status = action.arguments.stringOrNull("status") ?: DEFAULT_STATUS

        return when (action.intent.uppercase()) {
            INTENT_LOG -> ToolResult.ok(
                message = "Medication $medicineName marked as $status.",
                data = mapOf(
                    "medicineName" to medicineName,
                    "status" to status,
                    "intent" to action.intent
                )
            )
            INTENT_CHECK -> ToolResult.ok(
                message = "Medication status check requested. Room DB will be connected in next phase.",
                data = mapOf("intent" to action.intent)
            )
            else -> ToolResult.ok(
                message = "Medication intent received: ${action.intent}.",
                data = mapOf("intent" to action.intent)
            )
        }
    }

    companion object {
        private const val DEFAULT_MEDICINE = "medicine"
        private const val DEFAULT_STATUS = "taken"
        private const val INTENT_LOG = "LOG_MEDICATION"
        private const val INTENT_CHECK = "CHECK_MEDICATION"
    }
}
