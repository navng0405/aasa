package com.aasa.eldercare.ui.home

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.tools.ToolActionTypes

/**
 * Single source of truth for the Home / chat screen.
 *
 * Phase 4 surfaced the Gemma decision and tool execution outcome.
 * Phase 6 added voice-first state. Phase 7 added deferred-confirmation
 * pending-action fields. Phase 8 adds:
 *  - [gemmaConnection] / [gemmaModelLabel] for the "Local Gemma 4
 *    Edge: Connected" status card.
 *  - [transientMessage] for one-shot snackbar copy ("Demo data reset.").
 *
 * Risk semantics are derived from [agentAction]?.riskLevel via
 * [riskCopy] so the UI never has to know about risk strings.
 */
data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val agentAction: AgentAction? = null,
    val toolExecutionSuccess: Boolean? = null,
    val toolResultMessage: String? = null,
    val toolResultData: Map<String, Any?> = emptyMap(),
    val persistedToolResultMessage: String? = null,
    val isResettingDemoData: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val recognizedSpeech: String? = null,
    val voiceError: String? = null,
    val ttsStatus: String? = null,
    val hasMicPermission: Boolean = false,

    /** Phase 7 deferred-confirmation action payload. */
    val pendingActionType: String? = null,
    val pendingContactName: String? = null,
    val pendingPhoneNumber: String? = null,
    val pendingAlertMessage: String? = null,
    val pendingEmergencyNumber: String? = null,

    /** Phase 8.5 Scam Shield analysis payload (when surfaced on Home). */
    val pendingScamRisk: String? = null,
    val pendingScamSignals: List<String> = emptyList(),
    val pendingSafeAction: String? = null,
    val pendingScamMessageText: String? = null,

    /** Phase 8: local Gemma 4 server health. */
    val gemmaConnection: GemmaConnectionState = GemmaConnectionState.UNKNOWN,
    val gemmaModelLabel: String? = null,

    /**
     * Phase 8: one-shot snackbar text, e.g. "Demo data reset.". The
     * screen launches a side effect on [transientMessageId] changes
     * and then calls [HomeViewModel.consumeTransientMessage].
     */
    val transientMessage: String? = null,
    val transientMessageId: Long = 0L
) {
    val showContactActionCard: Boolean
        get() = pendingActionType == ToolActionTypes.CALL_CONTACT &&
            !pendingPhoneNumber.isNullOrBlank()

    val showSafetyActionCard: Boolean
        get() = pendingActionType == ToolActionTypes.ALERT_TRUSTED_CONTACT &&
            !pendingPhoneNumber.isNullOrBlank()

    val showEmergencyActionCard: Boolean
        get() = pendingActionType == ToolActionTypes.HIGH_RISK_SAFETY

    val showScamAnalysisCard: Boolean
        get() = pendingActionType == ToolActionTypes.SCAM_ANALYSIS &&
            !pendingScamRisk.isNullOrBlank()

    val scamRiskCopy: ScamRiskCopy?
        get() = pendingScamRisk?.let { ScamRiskCopy.fromRaw(it) }

    val riskCopy: RiskCopy?
        get() = agentAction?.riskLevel?.let { RiskCopy.fromRaw(it) }
}

/**
 * Coarse local view of the Gemma 4 bridge health. Driven by periodic
 * health pings from [HomeViewModel]; the UI maps each value to a
 * label in the status card.
 */
enum class GemmaConnectionState {
    UNKNOWN,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * Elder-friendly mapping of Gemma's raw risk string ("LOW" / "MEDIUM"
 * / "HIGH") into a short label and an explanation. Anything we don't
 * recognize maps to LOW so the UI still has something safe to show.
 */
data class RiskCopy(
    val level: RiskLevel,
    val label: String,
    val explanation: String
) {
    enum class RiskLevel { LOW, MEDIUM, HIGH }

    companion object {
        fun fromRaw(raw: String): RiskCopy = when (raw.trim().uppercase()) {
            "HIGH" -> RiskCopy(
                level = RiskLevel.HIGH,
                label = "Urgent safety concern",
                explanation = "This may need urgent help."
            )
            "MEDIUM" -> RiskCopy(
                level = RiskLevel.MEDIUM,
                label = "Possible safety concern",
                explanation = "Aasa noticed something that may need a check-in."
            )
            else -> RiskCopy(
                level = RiskLevel.LOW,
                label = "Low risk",
                explanation = "Normal request."
            )
        }
    }
}

/**
 * Elder-friendly mapping of the Scam & Fraud Shield risk band into a
 * label + supporting copy. Distinct from [RiskCopy] because the
 * messaging is scam-specific ("looks suspicious" vs. "safety concern").
 */
data class ScamRiskCopy(
    val level: RiskCopy.RiskLevel,
    val label: String,
    val explanation: String
) {
    companion object {
        fun fromRaw(raw: String): ScamRiskCopy = when (raw.trim().uppercase()) {
            "HIGH" -> ScamRiskCopy(
                level = RiskCopy.RiskLevel.HIGH,
                label = "High-risk scam pattern",
                explanation = "This message has strong scam warning signs. Please pause before replying."
            )
            "MEDIUM" -> ScamRiskCopy(
                level = RiskCopy.RiskLevel.MEDIUM,
                label = "Suspicious",
                explanation = "Some parts of this message look suspicious. Verify before acting."
            )
            else -> ScamRiskCopy(
                level = RiskCopy.RiskLevel.LOW,
                label = "No obvious scam signals",
                explanation = "This message looks ordinary. Trust your judgment if anything feels off."
            )
        }
    }
}
