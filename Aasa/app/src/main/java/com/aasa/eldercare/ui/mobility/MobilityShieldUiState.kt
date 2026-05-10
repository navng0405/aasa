package com.aasa.eldercare.ui.mobility

import com.aasa.eldercare.sensors.MobilityCheckState
import com.aasa.eldercare.sensors.MobilityFeatures
import com.aasa.eldercare.ui.home.RiskCopy

/**
 * Single source of truth for the Mobility Shield screen.
 *
 * Phases (left → right in the demo):
 *  1. IDLE        – nothing recorded yet, buttons enabled.
 *  2. RECORDING   – 10-second window in progress, countdown ticking.
 *  3. ANALYZING   – orchestrator round-trip in flight.
 *  4. RESULT_READY – features + Gemma summary on screen.
 *
 * Mobility-specific result fields:
 *  - [confidenceScore] / [stabilityLabel] / [riskLevel]
 *  - [features] (raw extractor output, including peak / variance)
 *  - [assistantResponse] (Gemma's gentle explanation)
 *  - [toolMessage] (deterministic on-device summary)
 *  - [recommendedAction] (next-step copy from Gemma, optional)
 *  - [contactName] / [phoneNumber] / [alertMessage] for the action card.
 *
 * IMPORTANT — the screen never claims to diagnose Parkinson's,
 * dementia, stroke, or any neurological disease. The copy throughout
 * uses gentle "mobility confidence" wording and points the elder at
 * a trusted contact, not a physician.
 */
data class MobilityShieldUiState(
    val phase: Phase = Phase.IDLE,

    // Sensor / recording status
    val sensorAvailable: Boolean = true,
    val hasGyroscope: Boolean = false,
    val recordingState: MobilityCheckState = MobilityCheckState.IDLE,
    val secondsRemaining: Int = TOTAL_RECORDING_SECONDS,
    val sensorErrorMessage: String? = null,

    // Mobility result (post-orchestrator)
    val features: MobilityFeatures? = null,
    val confidenceScore: Int? = null,
    val stabilityLabel: String? = null,
    val riskLevel: String? = null,
    val assistantResponse: String? = null,
    val toolMessage: String? = null,
    val recommendedAction: String? = null,

    val contactName: String? = null,
    val phoneNumber: String? = null,
    val alertMessage: String? = null,

    val errorMessage: String? = null
) {
    enum class Phase {
        IDLE,
        RECORDING,
        ANALYZING,
        RESULT_READY
    }

    val isRecording: Boolean get() = phase == Phase.RECORDING
    val isAnalyzing: Boolean get() = phase == Phase.ANALYZING
    val isBusy: Boolean get() = isRecording || isAnalyzing

    val showResultCard: Boolean
        get() = phase == Phase.RESULT_READY && confidenceScore != null

    val riskCopy: RiskCopy?
        get() = riskLevel?.let { RiskCopy.fromRaw(it) }

    val canCallContact: Boolean
        get() = !contactName.isNullOrBlank() && !phoneNumber.isNullOrBlank()

    /** Human-readable status for the recording row. */
    val statusLabel: String
        get() = when (phase) {
            Phase.IDLE -> if (!sensorAvailable) {
                "Accelerometer unavailable. Use Simulate Stable / Unsteady Walk."
            } else {
                "Ready"
            }
            Phase.RECORDING -> "Recording motion…"
            Phase.ANALYZING -> "Aasa is reviewing your walk…"
            Phase.RESULT_READY -> "Result ready"
        }

    companion object {
        const val TOTAL_RECORDING_SECONDS: Int = 10
    }
}

/**
 * Elder-friendly mapping of a mobility risk band into a card title +
 * supporting copy used by the result card. Distinct from
 * [com.aasa.eldercare.ui.home.RiskCopy] because the messaging is
 * mobility-specific and intentionally NEVER references neurological
 * disease.
 */
data class MobilityResultCopy(
    val title: String,
    val subtitle: String,
    val severity: Severity
) {
    enum class Severity { LOW, MEDIUM, HIGH }

    companion object {
        fun forRisk(risk: String?): MobilityResultCopy = when (risk?.trim()?.uppercase()) {
            "HIGH" -> MobilityResultCopy(
                title = "Needs check-in",
                subtitle = "Aasa noticed stronger signs of instability in this short check. " +
                    "Please sit down and consider alerting your trusted contact.",
                severity = Severity.HIGH
            )
            "MEDIUM" -> MobilityResultCopy(
                title = "Slightly unsteady",
                subtitle = "Your walk looked a little less steady than usual. " +
                    "Please sit down if you feel uncomfortable.",
                severity = Severity.MEDIUM
            )
            else -> MobilityResultCopy(
                title = "Stable",
                subtitle = "Your movement looked steady during this short check.",
                severity = Severity.LOW
            )
        }
    }
}
