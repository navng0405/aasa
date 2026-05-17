package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Builds deferred "neighbor helper" actions when the daily heartbeat
 * escalation should be routed to a nearby elder helper first.
 */
class NeighborCheckTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.NEIGHBOR_CHECK

    override suspend fun execute(action: AgentAction): ToolResult {
        val targetContact = trustedContactRepository.findPrimaryContact()
            ?: return ToolResult.fail(
                message = "No care recipient found for neighbor check.",
                data = mapOf(ToolResultKeys.PERSISTED to false)
            )
        val helperContact = trustedContactRepository.findPairedCareProviderFor(targetContact.id)
            ?: return ToolResult.fail(
                message = "No paired neighbor helper found for ${targetContact.name}.",
                data = mapOf(
                    ToolResultKeys.CONTACT_NAME to targetContact.name,
                    ToolResultKeys.PERSISTED to false
                )
            )

        val hoursSilent = (action.arguments[ARG_SILENCE_HOURS] as? Number)?.toInt() ?: 24
        val deepLink = action.arguments[ARG_DEEP_LINK] as? String ?: DEFAULT_DEEP_LINK
        val prompt = "${targetContact.name} hasn't checked in today. Want to knock on her door?"
        val smsDraft = buildString {
            append("Hi ")
            append(helperContact.name)
            append(", Aasa noticed ")
            append(targetContact.name)
            append(" has been quiet for ")
            append(hoursSilent.coerceAtLeast(1))
            append(" hours. If it feels safe, please do a quick doorstep check. ")
            append(deepLink)
        }

        return ToolResult.ok(
            message = "Prepared a neighbor check-in for ${helperContact.name}.",
            data = mapOf(
                ToolResultKeys.ACTION_TYPE to ToolActionTypes.NEIGHBOR_CHECK,
                ToolResultKeys.CONTACT_ID to helperContact.id,
                ToolResultKeys.CONTACT_NAME to helperContact.name,
                ToolResultKeys.PAIRED_CONTACT_NAME to targetContact.name,
                ToolResultKeys.PHONE_NUMBER to helperContact.phoneNumber,
                ToolResultKeys.RELATIONSHIP to helperContact.relationship,
                ToolResultKeys.ALERT_MESSAGE to prompt,
                ToolResultKeys.MESSAGE_TEXT to smsDraft,
                ToolResultKeys.SILENCE_HOURS to hoursSilent,
                ToolResultKeys.DEEP_LINK to deepLink,
                ToolResultKeys.PERSISTED to false
            )
        )
    }

    companion object {
        const val ARG_SILENCE_HOURS = "silenceHours"
        const val ARG_DEEP_LINK = "deepLink"
        private const val DEFAULT_DEEP_LINK = "aasa://neighbor-check"
    }
}
