package com.aasa.eldercare.ui.home

/**
 * Hackathon demo scripts shown in the "Try a demo scenario" section of
 * the Home screen. Each entry pairs a short tappable label with the
 * exact phrase sent into [HomeViewModel.sendSample] so the demo flow
 * matches what we've been talking through with reviewers.
 */
data class DemoScenario(
    val id: String,
    val label: String,
    val message: String
)

object DemoScenarios {
    val ALL: List<DemoScenario> = listOf(
        DemoScenario(
            id = "medication",
            label = "Medication",
            message = "I took my Metformin."
        ),
        DemoScenario(
            id = "memory",
            label = "Memory",
            message = "My granddaughter Ananya's birthday is May 12."
        ),
        DemoScenario(
            id = "memory_second",
            label = "Memory 2",
            message = "My granddaugters bithday is on June 10."
        ),
        DemoScenario(
            id = "memory_recall",
            label = "Recall",
            message = "When is my granddaughter birthday?"
        ),
        DemoScenario(
            id = "companion",
            label = "Companion",
            message = "I feel lonely this evening and miss my family."
        ),
        DemoScenario(
            id = "safety",
            label = "Safety",
            message = "I feel weak and missed my medicine."
        ),
        DemoScenario(
            id = "emergency",
            label = "Emergency",
            message = "I cannot breathe."
        ),
        DemoScenario(
            id = "call_priya",
            label = "Call Priya",
            message = "Call Priya."
        ),
        DemoScenario(
            id = "scam_alert",
            label = "Scam Alert",
            message = "Analyze this suspicious message: Hi Grandma, I'm in trouble and need help now. " +
                "Please don't call anyone. Buy two Apple gift cards worth \$500 and send me the codes quickly."
        )
    )
}
