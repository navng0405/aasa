package com.aasa.eldercare.tools

/**
 * Outcome of a single [AgentTool.execute] call.
 *
 * Tools never throw for "expected" failures (e.g. missing argument); they
 * return a failed [ToolResult] instead so the orchestrator can surface
 * the message to the UI uniformly.
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun ok(message: String, data: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(success = true, message = message, data = data)

        fun fail(message: String, data: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(success = false, message = message, data = data)
    }
}

/**
 * Canonical keys used inside [ToolResult.data] so the UI / ViewModel
 * can look them up without sprinkling string literals across the
 * codebase. Tools must use these constants when populating action
 * payloads consumed by the Home screen action cards.
 */
object ToolResultKeys {
    const val ACTION_TYPE = "actionType"
    const val CONTACT_ID = "contactId"
    const val CONTACT_NAME = "contactName"
    const val PHONE_NUMBER = "phoneNumber"
    const val RELATIONSHIP = "relationship"
    const val IS_PRIMARY = "isPrimary"
    const val ALERT_MESSAGE = "alertMessage"
    const val EMERGENCY_NUMBER = "emergencyNumber"
    const val INTENT = "intent"
    const val PERSISTED = "persisted"
    const val ORIGINAL_RISK_LEVEL = "originalRiskLevel"
    const val EFFECTIVE_RISK_LEVEL = "effectiveRiskLevel"

    // Scam & Fraud Shield (Phase 8.5)
    const val MESSAGE_TEXT = "messageText"
    const val SCAM_RISK = "scamRisk"
    const val SCAM_SIGNALS = "scamSignals"
    const val SAFE_ACTION = "safeAction"

    // Fall Triage (Phase 8.6)
    const val TRIAGE_CATEGORY = "triageCategory"
    const val TRIAGE_REASON = "triageReason"
    const val USER_RESPONSE = "userResponse"
    const val FALL_DETECTED = "fallDetected"
    const val RECOMMENDED_ACTION = "recommendedAction"

    // Mobility Shield (Phase 8.7)
    const val MOBILITY_CONFIDENCE_SCORE = "mobilityConfidenceScore"
    const val STABILITY_LABEL = "stabilityLabel"
    const val FEATURE_SUMMARY = "featureSummary"
    const val AVERAGE_ACCELERATION = "averageAcceleration"
    const val ACCELERATION_VARIANCE = "accelerationVariance"
    const val PEAK_ACCELERATION = "peakAcceleration"
    const val SIDE_TO_SIDE_SWAY = "sideToSideSway"
    const val SIDE_TO_SIDE_SWAY_SCORE = "sideToSideSwayScore"
    const val ABRUPT_PAUSES = "abruptPauses"
    const val SMOOTHNESS_SCORE = "smoothnessScore"
    const val DURATION_SECONDS = "durationSeconds"
}

/**
 * Action types the UI knows how to render as a deferred-confirmation
 * card. Keeping these as plain strings (instead of an enum) preserves
 * the cross-process JSON contract Gemma already speaks.
 */
object ToolActionTypes {
    const val CALL_CONTACT = "CALL_CONTACT"
    const val ALERT_TRUSTED_CONTACT = "ALERT_TRUSTED_CONTACT"
    const val HIGH_RISK_SAFETY = "HIGH_RISK_SAFETY"
    const val SCAM_ANALYSIS = "SCAM_ANALYSIS"
    const val FALL_TRIAGE = "FALL_TRIAGE"
    const val MOBILITY_CHECK = "MOBILITY_CHECK"
}

/**
 * Triage categories for [com.aasa.eldercare.tools.FallTriageTool]
 * (Phase 8.6). Kept as plain string constants so the JSON contract
 * with Gemma stays simple.
 */
object FallTriageCategories {
    const val FALSE_ALARM = "FALSE_ALARM"
    const val NON_EMERGENCY_INJURY = "NON_EMERGENCY_INJURY"
    const val URGENT_RISK = "URGENT_RISK"
    const val NO_RESPONSE = "NO_RESPONSE"
}
