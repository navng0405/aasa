package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Builds deferred wellness check actions for the daily heartbeat flow.
 */
class WellnessCheckTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.WELLNESS_CHECK

    override suspend fun execute(action: AgentAction): ToolResult {
        val contact = trustedContactRepository.findPrimaryContact()
            ?: return ToolResult.ok(
                message = "No trusted contact set. Add one to enable the daily heartbeat alert.",
                data = mapOf(ToolResultKeys.PERSISTED to false)
            )

        val state = (action.arguments[ARG_STATE] as? String).orEmpty().uppercase()
        val hoursSilent = (action.arguments[ARG_SILENCE_HOURS] as? Number)?.toInt() ?: 0
        val windowStartHour = (action.arguments[ARG_WINDOW_START_HOUR] as? Number)?.toInt()
        val windowEndHour = (action.arguments[ARG_WINDOW_END_HOUR] as? Number)?.toInt()
        val deepLink = action.arguments[ARG_DEEP_LINK] as? String ?: DEFAULT_DEEP_LINK

        val elderPrompt = "Priya hasn't heard from you today. Send her a quick hello?"
        val elderSms = "Hi Priya, it's me. I'm okay today. - Sent from Aasa"
        val escalationSms = buildString {
            append("Aasa wellness check: no heartbeat from Mom for ")
            append(hoursSilent.coerceAtLeast(1))
            append(" hours. Please check in soon. ")
            append(deepLink)
        }

        val message = if (state == STATE_ESCALATED) {
            "Long silence detected. Prepared a check-in draft for ${contact.name}."
        } else {
            elderPrompt
        }

        return ToolResult.ok(
            message = message,
            data = mapOf(
                ToolResultKeys.ACTION_TYPE to ToolActionTypes.WELLNESS_CHECK,
                ToolResultKeys.CONTACT_ID to contact.id,
                ToolResultKeys.CONTACT_NAME to contact.name,
                ToolResultKeys.PHONE_NUMBER to contact.phoneNumber,
                ToolResultKeys.RELATIONSHIP to contact.relationship,
                ToolResultKeys.IS_PRIMARY to contact.isPrimary,
                ToolResultKeys.ALERT_MESSAGE to elderPrompt,
                ToolResultKeys.MESSAGE_TEXT to if (state == STATE_ESCALATED) escalationSms else elderSms,
                ToolResultKeys.HEARTBEAT_STATE to state.ifBlank { STATE_ELDER_PROMPT },
                ToolResultKeys.WINDOW_START_HOUR to windowStartHour,
                ToolResultKeys.WINDOW_END_HOUR to windowEndHour,
                ToolResultKeys.SILENCE_HOURS to hoursSilent,
                ToolResultKeys.DEEP_LINK to deepLink,
                ToolResultKeys.PERSISTED to false
            )
        )
    }

    companion object {
        const val ARG_STATE = "heartbeatState"
        const val ARG_SILENCE_HOURS = "silenceHours"
        const val ARG_WINDOW_START_HOUR = "windowStartHour"
        const val ARG_WINDOW_END_HOUR = "windowEndHour"
        const val ARG_DEEP_LINK = "deepLink"

        const val STATE_ELDER_PROMPT = "ELDER_PROMPT"
        const val STATE_ESCALATED = "ESCALATED"

        private const val DEFAULT_DEEP_LINK = "aasa://wellness-check"
    }
}
