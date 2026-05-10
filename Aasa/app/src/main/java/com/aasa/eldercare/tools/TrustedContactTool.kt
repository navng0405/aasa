package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Resolves a trusted contact from Room and *prepares* a call action.
 *
 * Phase 7: the tool no longer claims the call has happened. Instead it
 * returns a deferred-confirmation payload (`actionType = CALL_CONTACT`,
 * `phoneNumber`, `contactName`) that the Home screen renders as a
 * single-tap "Open Dialer" card. The actual `ACTION_DIAL` happens in
 * the UI layer; tools never launch intents themselves.
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
                    ToolResultKeys.INTENT to action.intent,
                    ToolResultKeys.PERSISTED to false
                )
            )

        return when (action.intent.uppercase()) {
            INTENT_CALL -> ToolResult.ok(
                message = "Tap to call ${resolvedContact.name} at ${resolvedContact.phoneNumber}.",
                data = mapOf(
                    ToolResultKeys.ACTION_TYPE to ToolActionTypes.CALL_CONTACT,
                    ToolResultKeys.CONTACT_ID to resolvedContact.id,
                    ToolResultKeys.CONTACT_NAME to resolvedContact.name,
                    ToolResultKeys.PHONE_NUMBER to resolvedContact.phoneNumber,
                    ToolResultKeys.RELATIONSHIP to resolvedContact.relationship,
                    ToolResultKeys.IS_PRIMARY to resolvedContact.isPrimary,
                    ToolResultKeys.INTENT to action.intent,
                    "dialerLaunched" to false,
                    ToolResultKeys.PERSISTED to false
                )
            )
            else -> ToolResult.ok(
                message = "Trusted contact intent received: ${action.intent} for ${resolvedContact.name}.",
                data = mapOf(
                    ToolResultKeys.CONTACT_ID to resolvedContact.id,
                    ToolResultKeys.CONTACT_NAME to resolvedContact.name,
                    ToolResultKeys.INTENT to action.intent,
                    ToolResultKeys.PERSISTED to false
                )
            )
        }
    }

    companion object {
        private const val INTENT_CALL = "CALL_CONTACT"
    }
}
