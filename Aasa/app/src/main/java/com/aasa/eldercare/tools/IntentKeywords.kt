package com.aasa.eldercare.tools

/**
 * Deterministic intent classification on the *user input itself*, used
 * by the agent orchestrator as a defensive override over Gemma.
 *
 * These rules deliberately mirror what a careful human would assume:
 *  - "did I take my X?" / "have I taken my X?" with a medication noun
 *    is *always* a CHECK_MEDICATION query, regardless of how Gemma
 *    classifies it.
 *  - "I took / I just took my X" with a medication noun is *always* a
 *    LOG_MEDICATION action.
 *
 * Safety-related phrasing lives in [SafetyKeywords] – it takes priority
 * and is checked before these rules.
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
        "injection"
    )

    // ----- "Did I take ...?" style queries ---------------------------

    private val MEDICATION_CHECK_VERB_PATTERNS: List<Regex> = listOf(
        Regex("\\bdid i (take|have|miss|skip|finish)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bhave i (taken|had|missed|skipped|finished)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhat did i take\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhich (medicine|medication|pill|tablet|drug)s? did i\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * `true` when the message is unambiguously asking about today's
     * medication state. Requires both:
     *   1. a check-style verb pattern, AND
     *   2. at least one medication noun (so "did I take a walk?" is
     *      not misclassified).
     */
    fun isMedicationCheckQuery(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_CHECK_VERB_PATTERNS.any { it.containsMatchIn(text) }
    }

    // ----- "I took ..." style logs -----------------------------------

    private val MEDICATION_LOG_VERB_PATTERNS: List<Regex> = listOf(
        Regex("\\bi (just )?took\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi have taken\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi just had\\b", RegexOption.IGNORE_CASE),
        Regex("\\btaken my (medicine|medication|pill|tablet)\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * `true` when the message is unambiguously declaring that the elder
     * took a medication. Requires a log-style verb pattern *and* a
     * medication noun.
     */
    fun isMedicationLogStatement(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_LOG_VERB_PATTERNS.any { it.containsMatchIn(text) }
    }
}
