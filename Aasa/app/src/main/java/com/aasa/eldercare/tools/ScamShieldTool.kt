package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Phase 8.5: Scam & Fraud Shield.
 *
 * Combines Gemma's classification (intent / riskLevel / scamSignals)
 * with deterministic keyword scanning in [ScamKeywords] so a single
 * mis-classification can never let an obvious gift-card or OTP scam
 * slide through as "LOW".
 *
 * The tool is purely informational. It NEVER auto-blocks a sender,
 * NEVER auto-reports, and NEVER sends anything. It just produces a
 * gentle explanation + a deferred-confirmation payload (`actionType =
 * SCAM_ANALYSIS`) the UI uses to render an explanation card and an
 * optional "Call Priya" tap.
 *
 * Inputs (from [AgentAction.arguments]):
 *  - `messageText`     – the suspicious text the elder pasted.
 *                         Falls back to `userMessage` (set by the
 *                         orchestrator) with the leading "analyze
 *                         this suspicious message:" prefix stripped.
 *  - `scamSignals`     – optional list of labels Gemma flagged. Merged
 *                         with deterministic hits.
 *  - `safeAction`      – optional safe-step copy from Gemma. Replaced
 *                         by deterministic copy when Gemma left it
 *                         blank or risk got escalated.
 *
 * Output ([ToolResult.data]):
 *  - actionType = "SCAM_ANALYSIS"
 *  - scamRisk   = LOW / MEDIUM / HIGH
 *  - scamSignals = list<String>
 *  - safeAction = string
 *  - contactName / phoneNumber (when a trusted contact exists)
 *  - messageText (echoed for the UI)
 */
class ScamShieldTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.SCAM_SHIELD

    override suspend fun execute(action: AgentAction): ToolResult {
        val messageText = resolveMessageText(action)
        if (messageText.isBlank()) {
            return ToolResult.fail(
                message = "I didn't see a message to check. Please paste the suspicious text and try again.",
                data = mapOf(
                    ToolResultKeys.ACTION_TYPE to ToolActionTypes.SCAM_ANALYSIS,
                    ToolResultKeys.SCAM_RISK to ScamKeywords.RISK_LOW,
                    ToolResultKeys.SCAM_SIGNALS to emptyList<String>(),
                    ToolResultKeys.SAFE_ACTION to "Paste the message into the box and tap Analyze.",
                    ToolResultKeys.MESSAGE_TEXT to "",
                    ToolResultKeys.PERSISTED to false
                )
            )
        }

        val deterministicSignals = ScamKeywords.detectSignals(messageText)
        val gemmaSignalLabels = readGemmaSignals(action)
        val mergedSignalLabels = mergeSignalLabels(deterministicSignals, gemmaSignalLabels)

        val effectiveRisk = ScamKeywords.classifyRisk(
            detected = deterministicSignals,
            baseRisk = action.riskLevel
        )

        val safeAction = resolveSafeAction(action, effectiveRisk)
        val contact: TrustedContactEntity? = trustedContactRepository.findPrimaryContact()
        val explanation = buildExplanation(
            risk = effectiveRisk,
            signals = mergedSignalLabels,
            safeAction = safeAction,
            contactName = contact?.name
        )

        val data = mutableMapOf<String, Any?>(
            ToolResultKeys.ACTION_TYPE to ToolActionTypes.SCAM_ANALYSIS,
            ToolResultKeys.SCAM_RISK to effectiveRisk,
            ToolResultKeys.SCAM_SIGNALS to mergedSignalLabels,
            ToolResultKeys.SAFE_ACTION to safeAction,
            ToolResultKeys.MESSAGE_TEXT to messageText,
            ToolResultKeys.ORIGINAL_RISK_LEVEL to action.riskLevel,
            ToolResultKeys.EFFECTIVE_RISK_LEVEL to effectiveRisk,
            ToolResultKeys.PERSISTED to false
        )
        if (contact != null) {
            data[ToolResultKeys.CONTACT_ID] = contact.id
            data[ToolResultKeys.CONTACT_NAME] = contact.name
            data[ToolResultKeys.PHONE_NUMBER] = contact.phoneNumber
            data[ToolResultKeys.RELATIONSHIP] = contact.relationship
            data[ToolResultKeys.IS_PRIMARY] = contact.isPrimary
        }

        return ToolResult.ok(message = explanation, data = data.toMap())
    }

    // ----------------------------------------------------------------

    private fun resolveMessageText(action: AgentAction): String {
        val direct = action.arguments.stringOrNull("messageText")
            ?: action.arguments.stringOrNull("message")
            ?: action.arguments.stringOrNull("text")
        if (!direct.isNullOrBlank()) return direct.trim()

        val userMessage = action.arguments.stringOrNull("userMessage").orEmpty()
        return ScamKeywords.extractMessageText(userMessage)
    }

    /**
     * Unwrap Gemma's `scamSignals` argument, which may arrive as a
     * `List<*>` (preferred) or as a comma-separated `String`. Anything
     * else returns an empty list.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readGemmaSignals(action: AgentAction): List<String> {
        val raw = action.arguments["scamSignals"] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            is String -> raw.split(',', ';', '|')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    /**
     * Combine deterministic + Gemma-supplied labels, preserving the
     * deterministic order first (so the elder always sees the most
     * concrete labels at the top) and de-duplicating case-insensitively.
     */
    private fun mergeSignalLabels(
        deterministic: List<ScamKeywords.ScamSignal>,
        gemma: List<String>
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        deterministic.forEach { ordered.add(it.label) }
        val seenLower = ordered.map { it.lowercase() }.toMutableSet()
        gemma.forEach { label ->
            val key = label.lowercase()
            if (key !in seenLower) {
                ordered.add(label)
                seenLower.add(key)
            }
        }
        return ordered.toList()
    }

    private fun resolveSafeAction(action: AgentAction, risk: String): String {
        val gemmaSafeAction = action.arguments.stringOrNull("safeAction").orEmpty().trim()
        if (gemmaSafeAction.isNotBlank()) return gemmaSafeAction
        return when (risk) {
            ScamKeywords.RISK_HIGH ->
                "Do not reply. Do not send money or codes. Call a trusted contact to verify."
            ScamKeywords.RISK_MEDIUM ->
                "Pause before replying. Do not click links. Verify with the company or person directly."
            else ->
                "This message looks ordinary, but if anything feels off you can still call a trusted contact."
        }
    }

    private fun buildExplanation(
        risk: String,
        signals: List<String>,
        safeAction: String,
        contactName: String?
    ): String {
        val opener = when (risk) {
            ScamKeywords.RISK_HIGH -> "This message looks suspicious."
            ScamKeywords.RISK_MEDIUM -> "Please pause — this message may not be safe."
            else -> "I did not see clear scam signals in this message."
        }
        val signalSentence = if (signals.isNotEmpty()) {
            " Warning signs I noticed: " + signals.joinToString(", ") + "."
        } else {
            ""
        }
        val callOffer = if (
            !contactName.isNullOrBlank() &&
            risk != ScamKeywords.RISK_LOW
        ) {
            " Would you like to call $contactName to verify?"
        } else {
            ""
        }
        return opener + signalSentence + " " + safeAction + callOffer
    }
}
