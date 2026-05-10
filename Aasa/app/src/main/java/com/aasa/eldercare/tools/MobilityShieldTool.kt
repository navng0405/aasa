package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Phase 8.7: Mobility Shield Tool.
 *
 * Combines Gemma's classification (mobilityConfidenceScore /
 * stabilityLabel / featureSummary) with deterministic on-device risk
 * adjustment so a single mis-classification can never let a clearly
 * unsteady walk slide through as LOW.
 *
 * IMPORTANT — this is a hackathon-grade mobility check. It is NOT a
 * medical diagnosis. It does NOT detect Parkinson's, dementia, stroke,
 * or any neurological disease. It does NOT alert a physician
 * automatically. It is purely informational and only ever returns a
 * deferred-confirmation payload (`actionType = MOBILITY_CHECK`) the UI
 * uses to render a gentle summary card with elder-controlled buttons
 * (Alert Priya, Call Priya, Dismiss).
 *
 * Inputs (from [AgentAction.arguments]):
 *  - `mobilityConfidenceScore` – 0..100 from Gemma. Capped & coerced.
 *  - `stabilityLabel`           – Gemma's friendly bucket name.
 *  - `featureSummary`           – nested map with abruptPauses, etc.
 *                                  Top-level fields with the same
 *                                  names are also accepted.
 *  - `recommendedAction`        – Gemma's suggested next step.
 *
 * Output ([ToolResult.data]):
 *  - actionType                = "MOBILITY_CHECK"
 *  - mobilityConfidenceScore   = Int 0..100
 *  - stabilityLabel            = String
 *  - effectiveRiskLevel        = LOW / MEDIUM / HIGH
 *  - featureSummary            = nested map (echoed for the UI)
 *  - recommendedAction         = String?
 *  - alertMessage              = pre-baked SMS body
 *  - contactName / phoneNumber when a trusted contact exists.
 */
class MobilityShieldTool(
    private val trustedContactRepository: TrustedContactRepository
) : AgentTool {

    override val name: String = ToolNames.MOBILITY_SHIELD

    override suspend fun execute(action: AgentAction): ToolResult {
        val featureMap = readFeatureSummary(action)

        val score = resolveConfidenceScore(action, featureMap).coerceIn(0, 100)
        val abruptPauses = readInt(featureMap[ToolResultKeys.ABRUPT_PAUSES])
            ?: readInt(action.arguments[ToolResultKeys.ABRUPT_PAUSES])
            ?: 0
        val swayScoreNumeric = readDouble(featureMap[ToolResultKeys.SIDE_TO_SIDE_SWAY_SCORE])
            ?: readDouble(action.arguments[ToolResultKeys.SIDE_TO_SIDE_SWAY_SCORE])
        val swayLabel = (featureMap[ToolResultKeys.SIDE_TO_SIDE_SWAY] as? String)
            ?: (action.arguments[ToolResultKeys.SIDE_TO_SIDE_SWAY] as? String)
        val gemmaRisk = action.riskLevel.trim().uppercase()

        val baseRisk = scoreToRisk(score)
        val effectiveRisk = adjustRisk(
            base = baseRisk,
            gemmaRisk = gemmaRisk,
            abruptPauses = abruptPauses,
            swayScoreNumeric = swayScoreNumeric,
            swayLabel = swayLabel
        )

        val stabilityLabel = resolveStabilityLabel(action, score)
        val recommendedAction = action.arguments.stringOrNull(ToolResultKeys.RECOMMENDED_ACTION)

        val contact: TrustedContactEntity? =
            trustedContactRepository.findPrimaryContact()
        val toolMessage = buildToolMessage(effectiveRisk, contact?.name)
        val alertMessage = buildAlertMessage(effectiveRisk, stabilityLabel)

        val finalMessage = if (
            contact == null && effectiveRisk != RISK_LOW
        ) {
            "$toolMessage No trusted contact is set up. Please add one in Trusted Circle."
        } else {
            toolMessage
        }

        val data = mutableMapOf<String, Any?>(
            ToolResultKeys.ACTION_TYPE to ToolActionTypes.MOBILITY_CHECK,
            ToolResultKeys.MOBILITY_CONFIDENCE_SCORE to score,
            ToolResultKeys.STABILITY_LABEL to stabilityLabel,
            ToolResultKeys.ORIGINAL_RISK_LEVEL to action.riskLevel,
            ToolResultKeys.EFFECTIVE_RISK_LEVEL to effectiveRisk,
            ToolResultKeys.FEATURE_SUMMARY to featureMap,
            ToolResultKeys.ABRUPT_PAUSES to abruptPauses,
            ToolResultKeys.SIDE_TO_SIDE_SWAY_SCORE to swayScoreNumeric,
            ToolResultKeys.SIDE_TO_SIDE_SWAY to swayLabel,
            ToolResultKeys.RECOMMENDED_ACTION to recommendedAction,
            ToolResultKeys.ALERT_MESSAGE to alertMessage,
            ToolResultKeys.PERSISTED to false
        )
        if (contact != null) {
            data[ToolResultKeys.CONTACT_ID] = contact.id
            data[ToolResultKeys.CONTACT_NAME] = contact.name
            data[ToolResultKeys.PHONE_NUMBER] = contact.phoneNumber
            data[ToolResultKeys.RELATIONSHIP] = contact.relationship
            data[ToolResultKeys.IS_PRIMARY] = contact.isPrimary
        }

        return ToolResult.ok(message = finalMessage, data = data.toMap())
    }

    // ----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun readFeatureSummary(action: AgentAction): Map<String, Any?> {
        val raw = action.arguments[ToolResultKeys.FEATURE_SUMMARY]
        if (raw is Map<*, *>) {
            return raw.entries
                .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                .toMap()
        }
        // Fall back to a flat view of any feature-named arguments at
        // the top level so prompts that put everything next to each
        // other still produce a useful payload.
        val flat = mutableMapOf<String, Any?>()
        listOf(
            ToolResultKeys.AVERAGE_ACCELERATION,
            ToolResultKeys.ACCELERATION_VARIANCE,
            ToolResultKeys.PEAK_ACCELERATION,
            ToolResultKeys.SIDE_TO_SIDE_SWAY,
            ToolResultKeys.SIDE_TO_SIDE_SWAY_SCORE,
            ToolResultKeys.ABRUPT_PAUSES,
            ToolResultKeys.SMOOTHNESS_SCORE,
            ToolResultKeys.DURATION_SECONDS
        ).forEach { key ->
            action.arguments[key]?.let { flat[key] = it }
        }
        return flat
    }

    private fun resolveConfidenceScore(
        action: AgentAction,
        featureMap: Map<String, Any?>
    ): Int {
        val direct = readInt(action.arguments[ToolResultKeys.MOBILITY_CONFIDENCE_SCORE])
        if (direct != null) return direct
        val nested = readInt(featureMap[ToolResultKeys.MOBILITY_CONFIDENCE_SCORE])
        if (nested != null) return nested
        val smoothness = readInt(featureMap[ToolResultKeys.SMOOTHNESS_SCORE])
            ?: readInt(action.arguments[ToolResultKeys.SMOOTHNESS_SCORE])
        if (smoothness != null) return smoothness
        return DEFAULT_SCORE
    }

    private fun resolveStabilityLabel(action: AgentAction, score: Int): String {
        val direct = action.arguments.stringOrNull(ToolResultKeys.STABILITY_LABEL)
        if (!direct.isNullOrBlank()) return direct
        return scoreToLabel(score)
    }

    /**
     * Map score → base risk band per Phase 8.7 spec:
     *   80+   → LOW
     *   60-79 → MEDIUM
     *   < 60  → HIGH
     */
    private fun scoreToRisk(score: Int): String = when {
        score >= STABLE_THRESHOLD -> RISK_LOW
        score >= UNSTEADY_THRESHOLD -> RISK_MEDIUM
        else -> RISK_HIGH
    }

    private fun scoreToLabel(score: Int): String = when {
        score >= STABLE_THRESHOLD -> "Stable"
        score >= UNSTEADY_THRESHOLD -> "Slightly unsteady"
        else -> "Needs check-in"
    }

    /**
     * Apply the deterministic risk overrides from the spec:
     *  - 3+ abrupt pauses bumps risk by one level.
     *  - High side-to-side sway forces at least MEDIUM.
     *  - We always pick the worst of (base, Gemma) before applying the
     *    nudges so a clearly conservative Gemma still wins.
     */
    private fun adjustRisk(
        base: String,
        gemmaRisk: String,
        abruptPauses: Int,
        swayScoreNumeric: Double?,
        swayLabel: String?
    ): String {
        var rank = riskRank(base)
        val gemmaRank = riskRank(gemmaRisk)
        if (gemmaRank > rank) rank = gemmaRank
        if (abruptPauses >= 3) rank = (rank + 1).coerceAtMost(3)
        val highSway = isHighSway(swayScoreNumeric, swayLabel)
        if (highSway && rank < 2) rank = 2
        return rankToRisk(rank)
    }

    private fun isHighSway(
        swayScoreNumeric: Double?,
        swayLabel: String?
    ): Boolean {
        val labelHigh = swayLabel?.trim()?.lowercase() in HIGH_SWAY_LABELS
        if (labelHigh) return true
        if (swayScoreNumeric == null) return false
        return swayScoreNumeric >= HIGH_SWAY_NUMERIC_THRESHOLD
    }

    private fun riskRank(risk: String): Int = when (risk.trim().uppercase()) {
        RISK_HIGH -> 3
        RISK_MEDIUM -> 2
        RISK_LOW -> 1
        else -> 0
    }

    private fun rankToRisk(rank: Int): String = when (rank) {
        3 -> RISK_HIGH
        2 -> RISK_MEDIUM
        else -> RISK_LOW
    }

    private fun readInt(raw: Any?): Int? = when (raw) {
        null -> null
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun readDouble(raw: Any?): Double? = when (raw) {
        null -> null
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }

    private fun buildToolMessage(risk: String, contactName: String?): String {
        val name = contactName?.takeIf { it.isNotBlank() } ?: "your trusted contact"
        return when (risk) {
            RISK_LOW ->
                "Your movement looked steady during this short check."
            RISK_MEDIUM ->
                "Your movement looked a little less steady than usual. " +
                    "Please sit down if you feel uncomfortable. You can alert $name if you want."
            RISK_HIGH ->
                "Aasa noticed stronger signs of instability in this short check. " +
                    "Please sit down and consider contacting $name or emergency help if you feel unsafe."
            else ->
                "Mobility check complete."
        }
    }

    private fun buildAlertMessage(risk: String, stabilityLabel: String): String {
        val labelDescriptor = stabilityLabel.takeIf { it.isNotBlank() } ?: "less steady"
        return when (risk) {
            RISK_HIGH ->
                "Aasa mobility check-in: The elder completed a 10-second mobility check and the result was \"$labelDescriptor\". " +
                    "Please check in as soon as possible."
            RISK_MEDIUM ->
                "Aasa mobility check-in: The elder completed a 10-second mobility check and appeared $labelDescriptor. " +
                    "Please check in when possible."
            else ->
                "Aasa mobility check-in: The elder completed a 10-second mobility check and the result was \"$labelDescriptor\". " +
                    "No action is required, just sharing for awareness."
        }
    }

    companion object {
        private const val RISK_LOW = "LOW"
        private const val RISK_MEDIUM = "MEDIUM"
        private const val RISK_HIGH = "HIGH"

        private const val STABLE_THRESHOLD = 80
        private const val UNSTEADY_THRESHOLD = 60
        private const val DEFAULT_SCORE = 70

        /**
         * Numeric standard-deviation threshold (m/s²) above which we
         * treat the walk as having high lateral sway. This mirrors the
         * upper bound used by [com.aasa.eldercare.sensors.MobilityFeatureExtractor].
         */
        private const val HIGH_SWAY_NUMERIC_THRESHOLD: Double = 3.0

        private val HIGH_SWAY_LABELS: Set<String> = setOf(
            "high", "strong", "severe", "very high", "large"
        )
    }
}
