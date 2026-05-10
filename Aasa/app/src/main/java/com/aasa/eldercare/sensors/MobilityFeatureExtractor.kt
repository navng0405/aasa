package com.aasa.eldercare.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 8.7: deterministic feature extractor for the 10-second walk
 * check.
 *
 * IMPORTANT — this is a hackathon heuristic. It DOES NOT perform
 * clinical gait analysis, does NOT diagnose Parkinson's, dementia,
 * stroke, or any neurological disease, and never claims to. It only
 * computes a few basic descriptive statistics from the accelerometer
 * stream so we can show the elder a friendly "mobility confidence"
 * summary.
 *
 * The math is intentionally simple so the demo behavior is repeatable:
 *
 *  - magnitude       = sqrt(ax² + ay² + az²) per sample (gravity in)
 *  - average / peak  = mean and max of magnitude
 *  - variance        = population variance of magnitude
 *  - sway score      = standard deviation of the raw lateral axis (`ax`)
 *  - abrupt pauses   = count of sliding windows whose mean magnitude
 *                       drops well below gravity right after a window
 *                       of ordinary walking activity
 *  - smoothness 0-100 = penalize variance + abrupt pauses
 *  - confidence 0-100 = blend smoothness + low sway + few pauses
 *  - stabilityLabel  = bucket the confidence score (see [MobilityFeatures])
 *
 * Variance is computed in `(m/s²)²`; sway is in `m/s²`. The constants
 * below are tuned to produce clearly different scores between a calm
 * stable walk (~85+) and a deliberately unsteady walk (~50-) for the
 * hackathon demo.
 */
object MobilityFeatureExtractor {

    /** Earth gravity baseline used by Android's accelerometer (m/s²). */
    private const val GRAVITY_MS2: Double = 9.81

    /**
     * Acceleration variance that we treat as "extremely smooth" → 0
     * variance penalty. Anything from here scales linearly toward
     * [VARIANCE_HIGH_BOUND].
     */
    private const val VARIANCE_LOW_BOUND: Double = 0.5

    /**
     * Acceleration variance that we treat as "very rough" → full
     * variance penalty (40 points off the smoothness score).
     */
    private const val VARIANCE_HIGH_BOUND: Double = 12.0

    /** Side-to-side sway lower bound → 0 sway penalty. */
    private const val SWAY_LOW_BOUND: Double = 0.6

    /** Side-to-side sway upper bound → full 30-point sway penalty. */
    private const val SWAY_HIGH_BOUND: Double = 4.5

    /** Each detected abrupt pause subtracts this many smoothness points. */
    private const val PAUSE_PENALTY_PER_EVENT: Int = 8

    /**
     * Sliding window length (in samples) used by the abrupt-pause
     * detector. At Android's `SENSOR_DELAY_GAME` (~50 Hz) this is
     * roughly 0.5 seconds.
     */
    private const val PAUSE_WINDOW_SAMPLES: Int = 25

    /**
     * Magnitude threshold that counts as "essentially still" (close to
     * pure gravity, no motion). Under this for [PAUSE_WINDOW_SAMPLES]
     * after a window of clearly-moving samples = abrupt pause.
     */
    private const val PAUSE_MAGNITUDE_DELTA_MS2: Double = 1.2

    /**
     * Magnitude over gravity that counts as "clearly walking" inside
     * the previous window so the pause stands out as abrupt.
     */
    private const val ACTIVE_MAGNITUDE_DELTA_MS2: Double = 2.5

    /** Final mobility confidence weighting. */
    private const val WEIGHT_SMOOTHNESS: Double = 0.55
    private const val WEIGHT_LOW_SWAY: Double = 0.25
    private const val WEIGHT_LOW_PAUSES: Double = 0.20

    /**
     * Compute features from a recorded sample stream. When [samples]
     * is empty (e.g. no accelerometer or recording failed) we return a
     * conservative "needs check-in" payload instead of throwing.
     */
    fun extract(samples: List<MotionSample>): MobilityFeatures {
        if (samples.size < 2) {
            return emptyFeatures()
        }

        val durationSeconds = computeDurationSeconds(samples)
        val magnitudes = DoubleArray(samples.size) { idx ->
            val s = samples[idx]
            sqrt(
                (s.ax * s.ax + s.ay * s.ay + s.az * s.az).toDouble()
            )
        }

        val averageAcceleration = magnitudes.average()
        val peakAcceleration = magnitudes.max()
        val accelerationVariance = populationVariance(magnitudes, averageAcceleration)
        val sideToSideSwayScore = standardDeviation(samples.map { it.ax.toDouble() })
        val abruptPauses = countAbruptPauses(magnitudes)

        val smoothnessScore = computeSmoothnessScore(
            variance = accelerationVariance,
            abruptPauses = abruptPauses
        )
        val mobilityConfidenceScore = computeConfidenceScore(
            smoothness = smoothnessScore,
            sideToSideSwayScore = sideToSideSwayScore,
            abruptPauses = abruptPauses
        )
        val stabilityLabel = MobilityFeatures.labelForScore(mobilityConfidenceScore)

        return MobilityFeatures(
            durationSeconds = roundTo(durationSeconds, decimals = 2),
            averageAcceleration = roundTo(averageAcceleration, decimals = 2),
            accelerationVariance = roundTo(accelerationVariance, decimals = 2),
            peakAcceleration = roundTo(peakAcceleration, decimals = 2),
            sideToSideSwayScore = roundTo(sideToSideSwayScore, decimals = 2),
            abruptPauses = abruptPauses,
            smoothnessScore = smoothnessScore,
            mobilityConfidenceScore = mobilityConfidenceScore,
            stabilityLabel = stabilityLabel
        )
    }

    /**
     * Build a synthetic feature payload from preset numbers. Used by
     * the "Simulate Stable Walk" / "Simulate Unsteady Walk" buttons in
     * [com.aasa.eldercare.ui.mobility.MobilityShieldViewModel] so the
     * demo can showcase both ends of the score band without requiring
     * the user to physically walk.
     */
    fun simulated(
        durationSeconds: Double,
        averageAcceleration: Double,
        accelerationVariance: Double,
        peakAcceleration: Double,
        sideToSideSwayScore: Double,
        abruptPauses: Int,
        mobilityConfidenceScore: Int
    ): MobilityFeatures {
        val clamped = mobilityConfidenceScore.coerceIn(0, 100)
        val smoothness = computeSmoothnessScore(
            variance = accelerationVariance,
            abruptPauses = abruptPauses
        )
        return MobilityFeatures(
            durationSeconds = roundTo(durationSeconds, decimals = 2),
            averageAcceleration = roundTo(averageAcceleration, decimals = 2),
            accelerationVariance = roundTo(accelerationVariance, decimals = 2),
            peakAcceleration = roundTo(peakAcceleration, decimals = 2),
            sideToSideSwayScore = roundTo(sideToSideSwayScore, decimals = 2),
            abruptPauses = abruptPauses,
            smoothnessScore = smoothness,
            mobilityConfidenceScore = clamped,
            stabilityLabel = MobilityFeatures.labelForScore(clamped)
        )
    }

    // ---------------------------------------------------------------

    private fun emptyFeatures(): MobilityFeatures = MobilityFeatures(
        durationSeconds = 0.0,
        averageAcceleration = 0.0,
        accelerationVariance = 0.0,
        peakAcceleration = 0.0,
        sideToSideSwayScore = 0.0,
        abruptPauses = 0,
        smoothnessScore = 50,
        mobilityConfidenceScore = 50,
        stabilityLabel = MobilityFeatures.LABEL_NEEDS_CHECK_IN
    )

    private fun computeDurationSeconds(samples: List<MotionSample>): Double {
        val first = samples.first().timestampNanos
        val last = samples.last().timestampNanos
        if (last <= first) return 0.0
        return (last - first).toDouble() / 1_000_000_000.0
    }

    private fun populationVariance(values: DoubleArray, mean: Double): Double {
        if (values.size < 2) return 0.0
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sumSq / values.size
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }

    /**
     * Walk a sliding window across the magnitude array; when a moving
     * window is followed immediately by a near-still window, count it
     * as one abrupt pause and skip past so we don't double-count.
     */
    private fun countAbruptPauses(magnitudes: DoubleArray): Int {
        if (magnitudes.size < PAUSE_WINDOW_SAMPLES * 2) return 0
        var pauses = 0
        var i = 0
        while (i + (PAUSE_WINDOW_SAMPLES * 2) <= magnitudes.size) {
            val activeMean = sliceMean(
                magnitudes,
                fromIndex = i,
                toIndexExclusive = i + PAUSE_WINDOW_SAMPLES
            )
            val pauseMean = sliceMean(
                magnitudes,
                fromIndex = i + PAUSE_WINDOW_SAMPLES,
                toIndexExclusive = i + (PAUSE_WINDOW_SAMPLES * 2)
            )
            val activeDelta = abs(activeMean - GRAVITY_MS2)
            val pauseDelta = abs(pauseMean - GRAVITY_MS2)
            if (activeDelta >= ACTIVE_MAGNITUDE_DELTA_MS2 &&
                pauseDelta <= PAUSE_MAGNITUDE_DELTA_MS2
            ) {
                pauses++
                i += PAUSE_WINDOW_SAMPLES * 2
            } else {
                i += PAUSE_WINDOW_SAMPLES
            }
        }
        return pauses
    }

    private fun sliceMean(
        values: DoubleArray,
        fromIndex: Int,
        toIndexExclusive: Int
    ): Double {
        var sum = 0.0
        for (i in fromIndex until toIndexExclusive) sum += values[i]
        return sum / (toIndexExclusive - fromIndex)
    }

    /**
     * Smoothness score: starts at 100, subtracts up to 40 for variance,
     * 8 per abrupt pause, capped 0..100.
     */
    private fun computeSmoothnessScore(
        variance: Double,
        abruptPauses: Int
    ): Int {
        val variancePenalty = scaleLinearPenalty(
            value = variance,
            lowBound = VARIANCE_LOW_BOUND,
            highBound = VARIANCE_HIGH_BOUND,
            maxPenalty = 40.0
        )
        val pausePenalty = abruptPauses * PAUSE_PENALTY_PER_EVENT
        val raw = 100.0 - variancePenalty - pausePenalty
        return raw.coerceIn(0.0, 100.0).roundToInt()
    }

    /**
     * Mobility confidence: weighted blend of smoothness, low sway, and
     * low pauses. Result is always in 0..100 and feeds [MobilityFeatures.labelForScore].
     */
    private fun computeConfidenceScore(
        smoothness: Int,
        sideToSideSwayScore: Double,
        abruptPauses: Int
    ): Int {
        val swayPenalty = scaleLinearPenalty(
            value = sideToSideSwayScore,
            lowBound = SWAY_LOW_BOUND,
            highBound = SWAY_HIGH_BOUND,
            maxPenalty = 100.0
        )
        val swayScore = (100.0 - swayPenalty).coerceIn(0.0, 100.0)

        val pauseScore = (100.0 - (abruptPauses * 12.0)).coerceIn(0.0, 100.0)

        val blended = (smoothness * WEIGHT_SMOOTHNESS) +
            (swayScore * WEIGHT_LOW_SWAY) +
            (pauseScore * WEIGHT_LOW_PAUSES)
        return blended.coerceIn(0.0, 100.0).roundToInt()
    }

    private fun scaleLinearPenalty(
        value: Double,
        lowBound: Double,
        highBound: Double,
        maxPenalty: Double
    ): Double {
        if (value <= lowBound) return 0.0
        if (value >= highBound) return maxPenalty
        val frac = (value - lowBound) / (highBound - lowBound)
        return maxPenalty * frac
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = pow10(decimals)
        return (value * factor).roundToInt().toDouble() / factor
    }

    private fun pow10(exp: Int): Double {
        var r = 1.0
        repeat(max(0, min(exp, 9))) { r *= 10.0 }
        return r
    }
}
