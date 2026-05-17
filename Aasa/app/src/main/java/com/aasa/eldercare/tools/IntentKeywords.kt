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
 * Safety phrasing lives in [SafetyKeywords] – it is checked before
 * these rules and always wins.
 */
object IntentKeywords {

    // ----------------------------------------------------------------
    // Medication intent
    // ----------------------------------------------------------------

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
        // Demo-specific shorthands the elder may use:
        "bp tablet",
        "bp medicine",
        "bp pill"
    )

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

    /** Both a check trigger AND a medication noun must be present. */
    fun isMedicationCheckQuery(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_CHECK_TRIGGERS.any { lower.contains(it) }
    }

    /** Both a log trigger AND a medication noun must be present. */
    fun isMedicationLogStatement(text: String): Boolean {
        val lower = text.lowercase()
        if (MEDICATION_NOUNS.none { lower.contains(it) }) return false
        return MEDICATION_LOG_TRIGGERS.any { lower.contains(it) }
    }

    // ----------------------------------------------------------------
    // Memory save intent
    // ----------------------------------------------------------------

    /**
     * Strong "this is a fact to remember" signals. Each trigger is
     * specific enough that a substring hit is unambiguous on its own
     * (we deliberately use `"remember that"` instead of `"remember"`,
     * for example, so `"remember to call Priya"` does NOT match).
     */
    private val MEMORY_SAVE_TRIGGERS: List<String> = listOf(
        // Explicit "save this" markers
        "remember that",
        "remember this",
        "please remember that",
        "don't forget that",
        "don't forget about",
        "do not forget that",
        "note that",
        "note down",
        "make a note",
        "save this",
        "keep in mind that",

        // Event-date declarations
        "birthday is",
        "birthday on",
        "birthday was",
        "birthday falls on",
        "anniversary is",
        "anniversary on",
        "anniversary was",
        "wedding is",
        "wedding on",
        "wedding date",

        // Preference declarations
        "my favorite",
        "my favourite"
    )

    /**
     * Map of memory triggers to a coarse memory `type`. Used by
     * [deriveMemoryType] when Gemma didn't classify the memory itself.
     */
    private val MEMORY_TYPE_HINTS: List<Pair<String, String>> = listOf(
        "birthday" to "BIRTHDAY",
        "anniversary" to "ANNIVERSARY",
        "wedding" to "WEDDING",
        "favorite music" to "FAVORITE_MUSIC",
        "favourite music" to "FAVORITE_MUSIC",
        "favorite food" to "FAVORITE_FOOD",
        "favourite food" to "FAVORITE_FOOD",
        "favorite" to "FAVORITE",
        "favourite" to "FAVORITE"
    )

    fun isMemorySaveStatement(text: String): Boolean {
        val lower = text.lowercase()
        return MEMORY_SAVE_TRIGGERS.any { lower.contains(it) }
    }

    // ----------------------------------------------------------------
    // Mobility Shield intent (Phase 8.7)
    // ----------------------------------------------------------------

    /**
     * Recognize the synthetic prompt the Mobility Shield screen sends
     * to the orchestrator after a 10-second walk check (or after the
     * "Simulate Stable / Unsteady Walk" buttons). Matching is
     * intentionally permissive — anything that mentions a "mobility
     * check", a 10-second walk check, or a motion-feature summary
     * counts. Substring matches against a lowercased view.
     */
    private val MOBILITY_CHECK_TRIGGERS: List<String> = listOf(
        "mobility check completed",
        "mobility check complete",
        "mobility check result",
        "10-second walk check",
        "ten-second walk check",
        "10 second walk check",
        "analyze this 10-second motion summary",
        "analyze this motion summary",
        "mobilityconfidencescore"
    )

    fun isMobilityCheckRequest(text: String): Boolean {
        val lower = text.lowercase()
        return MOBILITY_CHECK_TRIGGERS.any { lower.contains(it) }
    }

    // ----------------------------------------------------------------
    // Health briefing intent (Phase 10)
    // ----------------------------------------------------------------

    /**
     * Triggers a Morning Briefing via HealthBriefingTool. Matches
     * both spoken phrases the elder might say ("how did I sleep
     * last night") and the synthetic prompt the Health Briefing
     * screen sends when the elder taps "Get my briefing".
     */
    private val HEALTH_BRIEFING_TRIGGERS: List<String> = listOf(
        "morning briefing",
        "morning brief",
        "daily briefing",
        "health briefing",
        "how did i sleep",
        "how was my sleep",
        "how am i doing today",
        "how is my health today",
        "what's my health like",
        "whats my health like",
        "give me my briefing",
        "give me a briefing",
        "summarize my health",
        "summarise my health",
        // synthetic prompt fired by the Health Briefing screen
        "generate my morning briefing"
    )

    fun isHealthBriefingRequest(text: String): Boolean {
        val lower = text.lowercase()
        return HEALTH_BRIEFING_TRIGGERS.any { lower.contains(it) }
    }

    // ----------------------------------------------------------------
    // Daily heartbeat / wellness-check intent
    // ----------------------------------------------------------------

    private val WELLNESS_CHECK_TRIGGERS: List<String> = listOf(
        "daily heartbeat timeout",
        "wellness check timeout",
        "wellness check escalation",
        "heartbeat escalation"
    )

    fun isWellnessCheckRequest(text: String): Boolean {
        val lower = text.lowercase()
        return WELLNESS_CHECK_TRIGGERS.any { lower.contains(it) }
    }

    private val NEIGHBOR_CHECK_TRIGGERS: List<String> = listOf(
        "neighbor check escalation",
        "neighbour check escalation",
        "community helper check"
    )

    fun isNeighborCheckRequest(text: String): Boolean {
        val lower = text.lowercase()
        return NEIGHBOR_CHECK_TRIGGERS.any { lower.contains(it) }
    }

    /**
     * Best-effort coarse type for a memory derived from the raw user
     * message – used by [com.aasa.eldercare.tools.MemoryTool] when
     * Gemma didn't supply a type itself.
     */
    fun deriveMemoryType(text: String): String {
        val lower = text.lowercase()
        return MEMORY_TYPE_HINTS.firstOrNull { lower.contains(it.first) }?.second
            ?: "GENERAL"
    }
}
