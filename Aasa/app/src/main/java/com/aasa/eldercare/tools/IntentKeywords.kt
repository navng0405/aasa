package com.aasa.eldercare.tools

/**
 * Deterministic intent classification on the *user input itself*, used
 * by the agent orchestrator as a defensive override over Gemma.
 *
 * Rules are intentionally simple substring matches on a lowercased view
 * of the message. Substrings are easier to reason about than regex word
 * boundaries and don't silently miss obvious phrasings like
 * `"did I take BP tablet"` (no `today`, no `my`).
 *
 * Two conditions must both hold for a match:
 *   1. the message contains at least one medication noun, and
 *   2. the message contains at least one verb-shape trigger.
 *
 * Safety phrasing lives in [SafetyKeywords] – it is checked before
 * these rules and always wins.
 */
object IntentKeywords {

    /** Words the elder uses to refer to a medication. */
    private val MEDICATION_NOUNS: List<String> = listOf(
        "medicine",
        "medicines",
        "medication",
        "medications",
        "pill",
        "pills",
        "tablet",
        "tablets",
        "dose",
        "doses",
        "drug",
        "drugs",
        "syrup",
        "injection",
        "capsule",
        "capsules",
        // Demo-specific shorthands the elder may use without a noun:
        "bp tablet",
        "bp medicine",
        "bp pill"
    )

    // ---------------- "Did I take ..." style queries -----------------

    private val MEDICATION_CHECK_TRIGGERS: List<String> = listOf(
        "did i take",
        "did i have",
        "did i miss",
        "did i skip",
        "did i finish",
        "did i swallow",
        "have i taken",
        "have i had",
        "have i missed",
        "have i skipped",
        "have i finished",
        "what did i take",
        "what medicine did i",
        "what medication did i",
        "which medicine did i",
        "which medication did i",
        "which pill did i",
        "which tablet did i",
        "did you log",
        "have you logged"
    )

    /**
     * `true` when the message is unambiguously asking about today's
     * medication state. Requires both a medication noun and a check
     * trigger so "did I take a walk?" does not false-positive.
     */
    fun isMedicationCheckQuery(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_CHECK_TRIGGERS.any { lower.contains(it) }
    }

    // ---------------- "I took ..." style logs ------------------------

    private val MEDICATION_LOG_TRIGGERS: List<String> = listOf(
        "i took",
        "i just took",
        "i've taken",
        "i have taken",
        "i had my",
        "i just had my",
        "i finished my",
        "taken my medicine",
        "taken my medication",
        "taken my pill",
        "taken my tablet",
        "swallowed my"
    )

    /**
     * `true` when the message is unambiguously declaring that the elder
     * took a medication.
     */
    fun isMedicationLogStatement(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_LOG_TRIGGERS.any { lower.contains(it) }
    }
}
