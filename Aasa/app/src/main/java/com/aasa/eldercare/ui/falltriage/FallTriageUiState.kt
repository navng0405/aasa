package com.aasa.eldercare.ui.falltriage

import com.aasa.eldercare.sensors.FallDetectionState
import com.aasa.eldercare.tools.FallTriageCategories

/**
 * Single source of truth for the Fall Detection & Triage screen.
 *
 * Phases (left → right in the demo):
 *  1. IDLE              – nothing detected, monitoring may be on/off.
 *  2. CHECKING_IN       – fall detected, Aasa is speaking the prompt.
 *  3. LISTENING         – SpeechRecognizer capturing the elder's reply.
 *  4. ANALYZING         – orchestrator round-trip in flight.
 *  5. TRIAGE_READY      – result card visible.
 *
 * The fields are intentionally flat so the composable can render the
 * action card without reaching back into the orchestrator's data map.
 */
data class FallTriageUiState(
    val phase: Phase = Phase.IDLE,

    // Sensor / monitoring
    val detectionState: FallDetectionState = FallDetectionState.IDLE,
    val sensorAvailable: Boolean = true,
    val sensorErrorMessage: String? = null,

    // Voice
    val hasMicPermission: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val recognizedSpeech: String? = null,
    val voiceError: String? = null,

    // Triage result (post-orchestrator)
    val triageCategory: String? = null,
    val triageRiskLevel: String? = null,
    val triageReason: String? = null,
    val recommendedAction: String? = null,
    val assistantResponse: String? = null,
    val toolMessage: String? = null,
    val alertMessage: String? = null,
    val emergencyNumber: String? = null,
    val contactName: String? = null,
    val phoneNumber: String? = null,

    val errorMessage: String? = null
) {
    enum class Phase {
        IDLE,
        CHECKING_IN,
        LISTENING,
        ANALYZING,
        TRIAGE_READY
    }

    /** Coarse on/off label for the monitoring status row. */
    val isMonitoring: Boolean
        get() = detectionState == FallDetectionState.MONITORING ||
            detectionState == FallDetectionState.POSSIBLE_FALL

    val showTriageCard: Boolean
        get() = phase == Phase.TRIAGE_READY && !triageCategory.isNullOrBlank()

    /** Friendly status copy for the "Voice check-in status" row. */
    val voiceStatusLabel: String
        get() = when (phase) {
            Phase.CHECKING_IN -> "Speaking…"
            Phase.LISTENING -> "Listening for your response…"
            Phase.ANALYZING -> "Analyzing your response…"
            Phase.TRIAGE_READY -> "Complete"
            Phase.IDLE -> "Waiting"
        }

    /** Human-readable label for the triage status row. */
    val fallStatusLabel: String
        get() = when {
            phase == Phase.IDLE && detectionState == FallDetectionState.MONITORING ->
                "No fall detected"
            phase == Phase.IDLE -> "—"
            phase == Phase.TRIAGE_READY -> "Triage complete"
            else -> "Possible fall detected"
        }
}

/**
 * Elder-friendly mapping of a triage category into a card title +
 * supporting copy used by the action card on the FallTriage screen.
 */
data class FallTriageCopy(
    val title: String,
    val subtitle: String,
    val severity: Severity
) {
    enum class Severity { LOW, MEDIUM, HIGH }

    companion object {
        fun forCategory(category: String?): FallTriageCopy = when (category) {
            FallTriageCategories.URGENT_RISK -> FallTriageCopy(
                title = "Urgent Safety Concern",
                subtitle = "This may need urgent help. You can open the emergency dialer or call your trusted contact.",
                severity = Severity.HIGH
            )
            FallTriageCategories.NO_RESPONSE -> FallTriageCopy(
                title = "No Response Heard",
                subtitle = "Aasa did not hear a response. It may be safer to alert your trusted contact.",
                severity = Severity.HIGH
            )
            FallTriageCategories.NON_EMERGENCY_INJURY -> FallTriageCopy(
                title = "Possible Injury",
                subtitle = "Aasa noticed pain. Would you like to alert your trusted contact?",
                severity = Severity.MEDIUM
            )
            FallTriageCategories.FALSE_ALARM -> FallTriageCopy(
                title = "False Alarm",
                subtitle = "No action is needed. Aasa will keep monitoring while this screen is open.",
                severity = Severity.LOW
            )
            else -> FallTriageCopy(
                title = "Triage Complete",
                subtitle = "Aasa is here when you need to check in.",
                severity = Severity.LOW
            )
        }
    }
}
