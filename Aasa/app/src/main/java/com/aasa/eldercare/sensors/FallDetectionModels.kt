package com.aasa.eldercare.sensors

/**
 * Coarse state of the fall detector. Used by the screen status card.
 */
enum class FallDetectionState {
    IDLE,
    MONITORING,
    POSSIBLE_FALL,
    UNAVAILABLE
}

/**
 * One observable event from [FallDetectionManager]. The ViewModel
 * collapses these into UI state and decides whether to start the
 * voice check-in flow.
 */
sealed class FallDetectionEvent {
    /** Listener registration succeeded. */
    object Started : FallDetectionEvent()

    /** Listener registration failed (no accelerometer or refused). */
    data class Unavailable(val message: String) : FallDetectionEvent()

    /** Listener was unregistered (manual stop or activity leaving). */
    object Stopped : FallDetectionEvent()

    /**
     * The heuristic flagged a possible fall. [peakMagnitude] is the
     * spike value in m/s², [stillnessSamples] is how many low-motion
     * readings were observed before the flag fired, and [simulated]
     * differentiates the "Simulate Fall" button from real sensor input.
     */
    data class PossibleFall(
        val peakMagnitude: Float,
        val stillnessSamples: Int,
        val simulated: Boolean
    ) : FallDetectionEvent()
}
