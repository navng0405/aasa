package com.aasa.eldercare.sensors

/**
 * Phase 8.7: Mobility Shield data models.
 *
 * IMPORTANT — these structures back a hackathon-grade mobility check,
 * not clinical gait analysis. They never claim to detect Parkinson's,
 * dementia, stroke, or any neurological disease. The only goal is a
 * gentle "mobility confidence" summary the elder can use to decide
 * whether to sit down or alert a trusted contact.
 */

/**
 * One raw motion sample captured during the 10-second walk check.
 *
 * Accelerometer values are in m/s² (Android's [android.hardware.Sensor.TYPE_ACCELEROMETER]
 * convention, gravity included). Gyroscope values are in rad/s and are
 * optional because not every device exposes a gyroscope.
 */
data class MotionSample(
    val timestampNanos: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float? = null,
    val gy: Float? = null,
    val gz: Float? = null
)

/**
 * Aggregated motion features computed from one 10-second walk check.
 *
 * Every value is intentionally simple and deterministic — no ML model,
 * no clinical thresholds, no diagnosis. The numbers feed both the
 * on-device interpretation in [com.aasa.eldercare.tools.MobilityShieldTool]
 * and the Gemma 4 prompt so the model can produce a gentle, non-medical
 * summary.
 */
data class MobilityFeatures(
    val durationSeconds: Double,
    val averageAcceleration: Double,
    val accelerationVariance: Double,
    val peakAcceleration: Double,
    val sideToSideSwayScore: Double,
    val abruptPauses: Int,
    val smoothnessScore: Int,
    val mobilityConfidenceScore: Int,
    val stabilityLabel: String
) {
    companion object {
        /**
         * Stability buckets for [stabilityLabel]. Kept here so both
         * the feature extractor and the tool agree on the wording.
         */
        const val LABEL_STABLE = "Stable"
        const val LABEL_SLIGHTLY_UNSTEADY = "Slightly unsteady"
        const val LABEL_NEEDS_CHECK_IN = "Needs check-in"

        /**
         * Map a 0-100 score to an elder-friendly label. The thresholds
         * mirror the Gemma server rules: 80+ stable, 60-79 slightly
         * unsteady, below 60 needs check-in.
         */
        fun labelForScore(score: Int): String = when {
            score >= 80 -> LABEL_STABLE
            score >= 60 -> LABEL_SLIGHTLY_UNSTEADY
            else -> LABEL_NEEDS_CHECK_IN
        }
    }
}

/**
 * Coarse state of the mobility sensor. The screen uses this to enable
 * or disable the "Start 10-second check" button.
 */
enum class MobilityCheckState {
    IDLE,
    RECORDING,
    UNAVAILABLE
}

/**
 * Events emitted by [MobilitySensorManager] over the 10-second window.
 *
 *  - [Started]   : recording window opened, samples are accumulating.
 *  - [Tick]      : one-per-second update so the UI can show a countdown.
 *  - [Completed] : 10 seconds elapsed; carries the recorded samples.
 *  - [Cancelled] : caller stopped the recording before completion.
 *  - [Unavailable] : no accelerometer or registration failed.
 */
sealed class MobilityCheckEvent {
    object Started : MobilityCheckEvent()
    data class Tick(val secondsRemaining: Int) : MobilityCheckEvent()
    data class Completed(val samples: List<MotionSample>) : MobilityCheckEvent()
    object Cancelled : MobilityCheckEvent()
    data class Unavailable(val message: String) : MobilityCheckEvent()
}
