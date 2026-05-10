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
            message = "I took my BP tablet."
        ),
        DemoScenario(
            id = "memory",
            label = "Memory",
            message = "My granddaughter Ananya's birthday is May 12."
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
        )
    )
}
