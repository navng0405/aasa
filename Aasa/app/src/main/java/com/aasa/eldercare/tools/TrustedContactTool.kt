package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Resolves a trusted contact from Room and *prepares* the call (no real
 * dialer intent yet – that arrives with the next phase). The resolved
 * phone number is included in [ToolResult.data] so a future tool layer
 * can launch `ACTION_DIAL` without re-querying the DB.
 */
class TrustedContactTool(
    private val repository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.TRUSTED_CONTACT

    override suspend fun execute(action: AgentAction): ToolResult {
        val requestedName = action.arguments.stringOrNull("contactName")
            ?: action.arguments.stringOrNull("name")

        val resolvedContact = repository.resolveContact(requestedName)
            ?: return ToolResult.fail(
                message = "No trusted contact configured. Add Priya or another contact first.",
                data = mapOf(
                    "requestedName" to requestedName,
                    "intent" to action.intent,
                    "persisted" to false
                )
            )

        return when (action.intent.uppercase()) {
            INTENT_CALL -> ToolResult.ok(
                message = "Prepared call action for ${resolvedContact.name}.",
                data = mapOf(
                    "contactId" to resolvedContact.id,
                    "contactName" to resolvedContact.name,
                    "phoneNumber" to resolvedContact.phoneNumber,
                    "relationship" to resolvedContact.relationship,
                    "isPrimary" to resolvedContact.isPrimary,
                    "intent" to action.intent,
                    "dialerLaunched" to false,
                    "persisted" to false
                )
            )
            else -> ToolResult.ok(
                message = "Trusted contact intent received: ${action.intent} for ${resolvedContact.name}.",
                data = mapOf(
                    "contactId" to resolvedContact.id,
                    "contactName" to resolvedContact.name,
                    "intent" to action.intent,
                    "persisted" to false
                )
            )
        }
    }

    companion object {
        private const val INTENT_CALL = "CALL_CONTACT"
    }
}
