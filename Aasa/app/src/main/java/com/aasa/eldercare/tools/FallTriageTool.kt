package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Phase 8.6: Fall Triage Tool.
 *
 * Combines Gemma's classification (intent / riskLevel / triageCategory
 * / userResponse) with deterministic phrase scanning in
 * [FallTriageKeywords] so a single mis-classification can never let a
 * head injury slide through as "FALSE_ALARM".
 *
 * The tool is purely informational and never auto-acts. It returns a
 * deferred-confirmation payload (`actionType = FALL_TRIAGE`) the UI
 * uses to render an action card with elder-controlled buttons (Open
 * Dialer, Open SMS, Emergency Dialer). All system intents are launched
 * by the UI, never by the tool.
 *
 * Inputs (from [AgentAction.arguments]):
 *  - `userResponse`     – the elder's spoken response to "Are you okay?".
 *                          Falls back to `userMessage` parsed by the
 *                          orchestrator override.
 *  - `triageCategory`   – Gemma's category. Merged with the
 *                          deterministic verdict (worst-case wins).
 *  - `fallDetected`     – optional boolean, propagated to the UI.
 *  - `reason`           – Gemma's free-text rationale (optional).
 *  - `recommendedAction` – Gemma's suggested next step (optional).
 *
 * Output ([ToolResult.data]):
 *  - actionType          = "FALL_TRIAGE"
 *  - triageCategory      = FALSE_ALARM / NON_EMERGENCY_INJURY /
 *                            URGENT_RISK / NO_RESPONSE
 *  - effectiveRiskLevel  = LOW / MEDIUM / HIGH
 *  - userResponse / fallDetected / triageReason / recommendedAction
 *  - alertMessage        = pre-baked SMS body
 *  - emergencyNumber     = "911"
 *  - contactName / phoneNumber / contactId / relationship / isPrimary
 *    when a trusted contact exists.
 */
class FallTriageTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.FALL_TRIAGE

    override suspend fun execute(action: AgentAction): ToolResult {
        val userResponse = resolveUserResponse(action)
        val gemmaCategory = action.arguments.stringOrNull("triageCategory")
        val deterministicCategory = FallTriageKeywords.classify(userResponse)
        val effectiveCategory = FallTriageKeywords.resolveCategory(
            gemmaCategory = gemmaCategory,
            deterministicCategory = deterministicCategory
        )
        val gemmaRisk = action.riskLevel.trim().uppercase()
        val effectiveRisk = pickWorstRisk(
            gemmaRisk = gemmaRisk,
            deterministicRisk = FallTriageKeywords.riskFor(effectiveCategory)
        )

        val contact: TrustedContactEntity? =
            trustedContactRepository.findPrimaryContact()
        val alertMessage = buildAlertMessage(effectiveCategory, userResponse)
        val toolMessage = buildToolMessage(effectiveCategory, contact?.name)

        val data = mutableMapOf<String, Any?>(
            ToolResultKeys.ACTION_TYPE to ToolActionTypes.FALL_TRIAGE,
            ToolResultKeys.TRIAGE_CATEGORY to effectiveCategory,
            ToolResultKeys.ORIGINAL_RISK_LEVEL to action.riskLevel,
            ToolResultKeys.EFFECTIVE_RISK_LEVEL to effectiveRisk,
            ToolResultKeys.USER_RESPONSE to userResponse,
            ToolResultKeys.FALL_DETECTED to readFallDetected(action),
            ToolResultKeys.TRIAGE_REASON to action.arguments.stringOrNull("reason"),
            ToolResultKeys.RECOMMENDED_ACTION to action.arguments.stringOrNull("recommendedAction"),
            ToolResultKeys.ALERT_MESSAGE to alertMessage,
            ToolResultKeys.EMERGENCY_NUMBER to EMERGENCY_NUMBER,
            ToolResultKeys.PERSISTED to false
        )
        if (contact != null) {
            data[ToolResultKeys.CONTACT_ID] = contact.id
            data[ToolResultKeys.CONTACT_NAME] = contact.name
            data[ToolResultKeys.PHONE_NUMBER] = contact.phoneNumber
            data[ToolResultKeys.RELATIONSHIP] = contact.relationship
            data[ToolResultKeys.IS_PRIMARY] = contact.isPrimary
        }

        // Surface a friendly "no trusted contact" message but still
        // return success so the UI can render the triage card with the
        // emergency dialer option.
        val finalMessage = if (
            contact == null &&
            effectiveCategory != FallTriageCategories.FALSE_ALARM
        ) {
            "$toolMessage No trusted contact is set up. Please add one in Trusted Circle."
        } else {
            toolMessage
        }

        return ToolResult.ok(message = finalMessage, data = data.toMap())
    }

    // ----------------------------------------------------------------

    private fun resolveUserResponse(action: AgentAction): String {
        val direct = action.arguments.stringOrNull("userResponse")
            ?: action.arguments.stringOrNull("response")
            ?: action.arguments.stringOrNull("text")
        if (!direct.isNullOrBlank()) return direct.trim()
        val userMessage = action.arguments.stringOrNull("userMessage").orEmpty()
        return FallTriageKeywords.extractUserResponse(userMessage)
    }

    private fun readFallDetected(action: AgentAction): Boolean {
        return when (val raw = action.arguments["fallDetected"]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> true // Reaching this tool already implies a fall flow.
        }
    }

    private fun pickWorstRisk(gemmaRisk: String, deterministicRisk: String): String {
        val rank = mapOf("HIGH" to 3, "MEDIUM" to 2, "LOW" to 1)
        val g = rank[gemmaRisk] ?: 0
        val d = rank[deterministicRisk] ?: 0
        return when (maxOf(g, d)) {
            3 -> "HIGH"
            2 -> "MEDIUM"
            1 -> "LOW"
            else -> "LOW"
        }
    }

    private fun buildAlertMessage(category: String, userResponse: String): String =
        when (category) {
            FallTriageCategories.URGENT_RISK ->
                "Aasa urgent fall alert: The elder reported a possible fall and may need immediate help. Please check in now."
            FallTriageCategories.NO_RESPONSE ->
                "Aasa fall alert: A possible fall was detected and Aasa did not hear a response. Please check in now."
            FallTriageCategories.NON_EMERGENCY_INJURY -> {
                val saidQuote = userResponse.takeIf { it.isNotBlank() }
                    ?.let { " and said: \"$it\"" }
                    .orEmpty()
                "Aasa fall check-in: The elder reported a possible fall$saidQuote. Please check in when possible."
            }
            else ->
                "Aasa fall check-in: A possible fall was reported as a false alarm. No action needed."
        }

    private fun buildToolMessage(category: String, contactName: String?): String {
        val name = contactName?.takeIf { it.isNotBlank() } ?: "your trusted contact"
        return when (category) {
            FallTriageCategories.FALSE_ALARM ->
                "False alarm noted. No action is needed."
            FallTriageCategories.NON_EMERGENCY_INJURY ->
                "Aasa noticed you are responsive but mentioned pain. You may want to alert $name."
            FallTriageCategories.URGENT_RISK ->
                "This may need urgent help. You can open the emergency dialer or call $name."
            FallTriageCategories.NO_RESPONSE ->
                "Aasa did not hear a response. It may be safer to alert $name."
            else ->
                "Triage complete."
        }
    }

    companion object {
        private const val EMERGENCY_NUMBER = "911"
    }
}
