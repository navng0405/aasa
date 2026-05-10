package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Phase-3 mock for the trusted-circle calling flow. We only *prepare*
 * the call – no real dialer intent is fired yet.
 */
class TrustedContactTool : AgentTool {
    override val name: String = ToolNames.TRUSTED_CONTACT

    override suspend fun execute(action: AgentAction): ToolResult {
        val contactName = action.arguments.stringOrNull("contactName")
            ?: action.arguments.stringOrNull("name")
            ?: DEFAULT_CONTACT_NAME

        return when (action.intent.uppercase()) {
            INTENT_CALL -> ToolResult.ok(
                message = "Prepared call action for $contactName.",
                data = mapOf(
                    "contactName" to contactName,
                    "intent" to action.intent,
                    "dialerLaunched" to false
                )
            )
            else -> ToolResult.ok(
                message = "Trusted contact intent received: ${action.intent}.",
                data = mapOf("contactName" to contactName, "intent" to action.intent)
            )
        }
    }

    companion object {
        private const val DEFAULT_CONTACT_NAME = "Priya"
        private const val INTENT_CALL = "CALL_CONTACT"
    }
}
