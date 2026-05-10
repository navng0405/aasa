package com.aasa.eldercare.tools

/**
 * Deterministic phrase lists used by the safety pipeline.
 *
 * Both the [com.aasa.eldercare.agent.AgentOrchestrator] (pre-routing
 * override) and [SafetyTool] (post-routing classification) read from
 * here so the rules can never drift apart.
 *
 * The lists are intentionally short and elder-care specific. We do *not*
 * try to diagnose – only to detect language that warrants escalation.
 */
object SafetyKeywords {

    /** Always treat as HIGH – emergency-style language. */
    val HIGH_RISK_PHRASES: List<String> = listOf(
        "chest pain",
        "cannot breathe",
        "can't breathe",
        "fell down",
        "fainted",
        "severe weakness"
    )

    /** Treat as MEDIUM – symptoms / missed-medication patterns. */
    val MEDIUM_RISK_PHRASES: List<String> = listOf(
        "feel weak",
        "feeling weak",
        "felt weak",
        "feel dizzy",
        "feeling dizzy",
        "dizzy",
        "lightheaded",
        "light-headed",
        "feel unwell",
        "feeling unwell",
        "not feeling well",
        "feel sick",
        "missed my medicine",
        "missed my medication",
        "missed a dose",
        "skipped my medicine",
        "skipped my medication",
        "didn't take my medicine",
        "didn't take my medication"
    )

    fun containsHighRiskPhrase(text: String): Boolean {
        val lower = text.lowercase()
        return HIGH_RISK_PHRASES.any { lower.contains(it) }
    }

    fun containsMediumRiskPhrase(text: String): Boolean {
        val lower = text.lowercase()
        return MEDIUM_RISK_PHRASES.any { lower.contains(it) }
    }
}
