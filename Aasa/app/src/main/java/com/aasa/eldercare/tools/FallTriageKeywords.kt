package com.aasa.eldercare.tools

/**
 * Deterministic phrase lists used by [FallTriageTool] and the
 * [com.aasa.eldercare.agent.AgentOrchestrator] override (Phase 8.6).
 *
 * The triage logic is intentionally simple substring matching on a
 * lowercased view of the user's spoken response. We never downgrade
 * Gemma's verdict — only escalate. Order of evaluation:
 *
 *   1. NO_RESPONSE         (caller passed a blank response)
 *   2. URGENT_RISK         (head injury, can't get up, dizzy, bleeding…)
 *   3. NON_EMERGENCY_INJURY (pain, soreness, hurt limbs)
 *   4. FALSE_ALARM         (dropped phone, "I'm okay")
 *   5. Otherwise           (defer to Gemma's category)
 *
 * Urgent keyword hits trump everything else even when the elder also
 * said "I'm okay" — saying both is a known partial-denial pattern.
 */
object FallTriageKeywords {

    /** Anything in this list is treated as URGENT_RISK / HIGH. */
    val URGENT_PHRASES: List<String> = listOf(
        "can't get up",
        "cannot get up",
        "can not get up",
        "couldn't get up",
        "couldnt get up",
        "i can't move",
        "cannot move",
        "hit my head",
        "banged my head",
        "hurt my head",
        "head is bleeding",
        "i am bleeding",
        "i'm bleeding",
        "im bleeding",
        "lost consciousness",
        "passed out",
        "blacking out",
        "blacked out",
        "fainted",
        "i cannot breathe",
        "i can't breathe",
        "cant breathe",
        "having trouble breathing",
        "very dizzy",
        "really dizzy",
        "feel dizzy",
        "feeling dizzy",
        "chest pain",
        "everything hurts",
        "i need help",
        "help me"
    )

    /** Hits here map to NON_EMERGENCY_INJURY / MEDIUM. */
    val INJURY_PHRASES: List<String> = listOf(
        "my hip hurts",
        "hip hurts",
        "my arm hurts",
        "arm hurts",
        "my leg hurts",
        "leg hurts",
        "my back hurts",
        "back hurts",
        "my knee hurts",
        "knee hurts",
        "my wrist hurts",
        "wrist hurts",
        "my ankle hurts",
        "ankle hurts",
        "i hurt my",
        "i'm hurting",
        "im hurting",
        "in some pain",
        "sore",
        "bruised",
        "bruise",
        "sprained",
        "twisted my",
        "scraped my",
        "scratched my"
    )

    /** Hits here map to FALSE_ALARM / LOW (unless an injury phrase also fires). */
    val FALSE_ALARM_PHRASES: List<String> = listOf(
        "i'm okay",
        "im okay",
        "i am okay",
        "i'm fine",
        "im fine",
        "i am fine",
        "i'm alright",
        "im alright",
        "i am alright",
        "i'm good",
        "im good",
        "false alarm",
        "dropped the phone",
        "dropped my phone",
        "phone slipped",
        "it was a mistake",
        "no fall",
        "didn't fall",
        "did not fall",
        "no need",
        "nothing happened"
    )

    /**
     * Classify the elder's spoken response into one of the four triage
     * categories. Returns null when no deterministic phrase matches so
     * the caller can fall back to Gemma's classification.
     *
     * Special cases:
     *  - Blank input → NO_RESPONSE (the listener timed out).
     *  - Containing the literal "NO_RESPONSE" or "no response" marker
     *    that the FallTriage ViewModel emits when the elder didn't
     *    speak → NO_RESPONSE.
     */
    fun classify(userResponse: String?): String? {
        if (userResponse.isNullOrBlank()) {
            return FallTriageCategories.NO_RESPONSE
        }
        val lower = userResponse.lowercase()
        if (lower.contains("no_response") || lower.contains("no response")) {
            return FallTriageCategories.NO_RESPONSE
        }
        if (URGENT_PHRASES.any { lower.contains(it) }) {
            return FallTriageCategories.URGENT_RISK
        }
        if (INJURY_PHRASES.any { lower.contains(it) }) {
            return FallTriageCategories.NON_EMERGENCY_INJURY
        }
        if (FALSE_ALARM_PHRASES.any { lower.contains(it) }) {
            return FallTriageCategories.FALSE_ALARM
        }
        return null
    }

    /**
     * Map a triage category to the worst-case risk band. Used when
     * Gemma also left riskLevel blank.
     */
    fun riskFor(category: String?): String = when (category) {
        FallTriageCategories.URGENT_RISK,
        FallTriageCategories.NO_RESPONSE -> "HIGH"
        FallTriageCategories.NON_EMERGENCY_INJURY -> "MEDIUM"
        FallTriageCategories.FALSE_ALARM -> "LOW"
        else -> "LOW"
    }

    /**
     * Fold Gemma's triage category with the deterministic verdict.
     * Returns the *worst* of the two so we can never downgrade an
     * elder's reported "I hit my head" because Gemma got chatty.
     */
    fun resolveCategory(
        gemmaCategory: String?,
        deterministicCategory: String?
    ): String {
        val ranked = listOf(
            FallTriageCategories.URGENT_RISK,
            FallTriageCategories.NO_RESPONSE,
            FallTriageCategories.NON_EMERGENCY_INJURY,
            FallTriageCategories.FALSE_ALARM
        )
        val gemma = gemmaCategory?.trim()?.uppercase()?.takeIf { it in ranked }
        val deterministic = deterministicCategory?.trim()?.uppercase()?.takeIf { it in ranked }

        return when {
            deterministic == null && gemma == null -> FallTriageCategories.FALSE_ALARM
            deterministic == null -> gemma!!
            gemma == null -> deterministic
            // Pick whichever of the two has the smaller (worst) rank.
            else -> {
                val gemmaRank = ranked.indexOf(gemma)
                val detRank = ranked.indexOf(deterministic)
                ranked[minOf(gemmaRank, detRank)]
            }
        }
    }

    /**
     * True when [userMessage] looks like the orchestrator-internal
     * synthetic prompt used to route into [FallTriageTool]. Used so
     * the deterministic override knows when to take over.
     */
    fun isFallTriageRequest(userMessage: String): Boolean {
        val lower = userMessage.lowercase()
        return TRIAGE_TRIGGERS.any { lower.contains(it) }
    }

    /** Pull the elder's spoken response out of the synthetic prompt. */
    fun extractUserResponse(userMessage: String): String {
        val cleaned = userMessage.trim()
        val marker = "user response:"
        val idx = cleaned.lowercase().indexOf(marker)
        if (idx < 0) return cleaned
        val rest = cleaned.substring(idx + marker.length).trim()
        // Drop the trailing instruction sentence (everything after the
        // last period the orchestrator appended) so we don't classify
        // "Classify fall triage." as injury text.
        val tail = "classify fall triage"
        val tailIdx = rest.lowercase().indexOf(tail)
        return if (tailIdx >= 0) {
            rest.substring(0, tailIdx).trim().trimEnd('.', ' ', ',')
        } else {
            rest
        }
    }

    private val TRIAGE_TRIGGERS: List<String> = listOf(
        "fall detected.",
        "classify fall triage",
        "possible fall detected"
    )
}
