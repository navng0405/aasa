package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Combines Gemma's [AgentAction.riskLevel] with a deterministic
 * keyword-based check on the user message / assistant response /
 * arguments and produces a *deferred* safety action payload the UI
 * can render as an action card.
 *
 *  - LOW    → informational [ToolResult]; no card.
 *  - MEDIUM → `actionType = ALERT_TRUSTED_CONTACT`, includes resolved
 *             contact + a pre-baked SMS body. UI shows "Alert Priya?".
 *  - HIGH   → `actionType = HIGH_RISK_SAFETY`, includes resolved
 *             contact + emergency number 911 + alert body. UI shows
 *             the emergency action card.
 *
 * Tools never auto-dial / auto-SMS; the UI layer launches the intent
 * after explicit elder confirmation.
 *
 * Phrase lists live in [SafetyKeywords] so the orchestrator and this
 * tool can never disagree.
 */
class SafetyTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.SAFETY

    override suspend fun execute(action: AgentAction): ToolResult {
        val haystack = buildHaystack(action)
        val containsHigh = SafetyKeywords.containsHighRiskPhrase(haystack)
        val containsMedium = SafetyKeywords.containsMediumRiskPhrase(haystack)
        val baseRisk = action.riskLevel.uppercase()

        // Risk aggregation: take the *worst* of (deterministic phrase
        // scan, Gemma's risk level). We never downgrade.
        val effectiveRisk = when {
            containsHigh || baseRisk == RISK_HIGH -> RISK_HIGH
            containsMedium || baseRisk == RISK_MEDIUM -> RISK_MEDIUM
            else -> baseRisk.ifBlank { RISK_LOW }
        }

        val contact = trustedContactRepository.findPrimaryContact()

        val baseData = mutableMapOf<String, Any?>(
            ToolResultKeys.ORIGINAL_RISK_LEVEL to action.riskLevel,
            ToolResultKeys.EFFECTIVE_RISK_LEVEL to effectiveRisk,
            "matchedHighRiskPhrase" to containsHigh,
            "matchedMediumRiskPhrase" to containsMedium,
            ToolResultKeys.PERSISTED to false
        )

        return when (effectiveRisk) {
            RISK_HIGH -> highRiskResult(contact, baseData)
            RISK_MEDIUM -> mediumRiskResult(contact, baseData)
            else -> ToolResult.ok(
                message = "Low safety concern. Continuing normally.",
                data = baseData.toMap()
            )
        }
    }

    private fun mediumRiskResult(
        contact: TrustedContactEntity?,
        baseData: MutableMap<String, Any?>
    ): ToolResult {
        if (contact == null) {
            return ToolResult.ok(
                message = "Medium safety concern detected. Add a trusted contact so Aasa can offer to alert them.",
                data = baseData.toMap()
            )
        }
        val alertMessage = MEDIUM_RISK_ALERT_MESSAGE
        baseData[ToolResultKeys.ACTION_TYPE] = ToolActionTypes.ALERT_TRUSTED_CONTACT
        baseData[ToolResultKeys.CONTACT_ID] = contact.id
        baseData[ToolResultKeys.CONTACT_NAME] = contact.name
        baseData[ToolResultKeys.PHONE_NUMBER] = contact.phoneNumber
        baseData[ToolResultKeys.RELATIONSHIP] = contact.relationship
        baseData[ToolResultKeys.IS_PRIMARY] = contact.isPrimary
        baseData[ToolResultKeys.ALERT_MESSAGE] = alertMessage
        return ToolResult.ok(
            message = "Medium safety concern detected. Ask permission before alerting ${contact.name}.",
            data = baseData.toMap()
        )
    }

    private fun highRiskResult(
        contact: TrustedContactEntity?,
        baseData: MutableMap<String, Any?>
    ): ToolResult {
        val alertMessage = HIGH_RISK_ALERT_MESSAGE
        baseData[ToolResultKeys.ACTION_TYPE] = ToolActionTypes.HIGH_RISK_SAFETY
        baseData[ToolResultKeys.EMERGENCY_NUMBER] = EMERGENCY_NUMBER
        baseData[ToolResultKeys.ALERT_MESSAGE] = alertMessage
        if (contact != null) {
            baseData[ToolResultKeys.CONTACT_ID] = contact.id
            baseData[ToolResultKeys.CONTACT_NAME] = contact.name
            baseData[ToolResultKeys.PHONE_NUMBER] = contact.phoneNumber
            baseData[ToolResultKeys.RELATIONSHIP] = contact.relationship
            baseData[ToolResultKeys.IS_PRIMARY] = contact.isPrimary
        }
        return ToolResult.ok(
            message = "High safety concern detected. Recommend contacting emergency help or trusted contact immediately.",
            data = baseData.toMap()
        )
    }

    private fun buildHaystack(action: AgentAction): String {
        val argText = action.arguments.values
            .filterNotNull()
            .joinToString(separator = " ")
        return listOf(
            action.assistantResponse,
            argText
        ).filter { it.isNotBlank() }.joinToString(separator = " ")
    }

    companion object {
        private const val RISK_HIGH = "HIGH"
        private const val RISK_MEDIUM = "MEDIUM"
        private const val RISK_LOW = "LOW"
        private const val EMERGENCY_NUMBER = "911"
        private const val MEDIUM_RISK_ALERT_MESSAGE =
            "Aasa safety alert: The elder reported weakness and missed medication. " +
                "Please check in when possible."
        private const val HIGH_RISK_ALERT_MESSAGE =
            "Aasa urgent safety alert: The elder reported a high-risk concern. " +
                "Please check immediately."
    }
}
