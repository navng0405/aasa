package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.HealthSnapshot
import com.aasa.eldercare.data.repository.HealthSnapshotRepository

/**
 * Phase 10 — Morning / Health Briefing tool.
 *
 * Reads the last-24-hour [HealthSnapshot] from Health Connect (or
 * falls back to mock data) and turns it into a gentle, elder-friendly
 * briefing the UI shows on the Health Briefing screen.
 *
 * Notes:
 *  - This tool is **never** a medical diagnosis. The copy is gentle
 *    and informational only.
 *  - When the snapshot is mock data (no wearable connected), the
 *    tool result carries `isMockData = true` so the screen can show
 *    the mandatory "Demo data — no wearable connected" pill.
 *  - On-device only. No cloud, no bridge. The snapshot never leaves
 *    the phone.
 */
class HealthBriefingTool(
    private val repository: HealthSnapshotRepository
) : AgentTool {

    override val name: String = ToolNames.HEALTH_BRIEFING

    override suspend fun execute(action: AgentAction): ToolResult {
        val snapshot = repository.fetchLast24h()
        val highlights = buildHighlights(snapshot)
        val briefing = buildBriefing(snapshot, highlights)

        val data = mapOf<String, Any?>(
            ToolResultKeys.ACTION_TYPE to ToolActionTypes.HEALTH_BRIEFING,
            ToolResultKeys.SLEEP_HOURS to snapshot.sleepHours,
            ToolResultKeys.AVG_RESTING_HEART_RATE to snapshot.avgRestingHeartRateBpm,
            ToolResultKeys.STEPS to snapshot.steps,
            ToolResultKeys.SNAPSHOT_SOURCE to snapshot.source,
            ToolResultKeys.IS_MOCK_DATA to snapshot.isMockData,
            ToolResultKeys.BRIEFING_TEXT to briefing,
            ToolResultKeys.BRIEFING_HIGHLIGHTS to highlights,
            ToolResultKeys.PERSISTED to false
        )
        return ToolResult.ok(message = briefing, data = data)
    }

    private fun buildHighlights(s: HealthSnapshot): List<String> {
        val out = mutableListOf<String>()
        s.sleepHours?.let {
            val rounded = String.format("%.1f", it)
            out += "Sleep: $rounded hours last night"
        }
        s.avgRestingHeartRateBpm?.let {
            out += "Average heart rate: $it bpm"
        }
        s.steps?.let {
            out += "Steps in the last 24 hours: $it"
        }
        if (out.isEmpty()) out += "No wearable data available yet."
        return out
    }

    private fun buildBriefing(s: HealthSnapshot, highlights: List<String>): String {
        val opener = if (s.isMockData) {
            "Good morning. Here's a sample briefing using demo data, because no wearable is connected yet."
        } else {
            "Good morning. Here's a quick look at the last 24 hours."
        }

        val sleepLine = s.sleepHours?.let { hours ->
            when {
                hours >= 7.0 -> "You slept about ${fmt(hours)} hours — that's a healthy stretch."
                hours >= 6.0 -> "You slept about ${fmt(hours)} hours — a little less than ideal. A short rest later could help."
                else -> "You slept only about ${fmt(hours)} hours. Try to take it easy today and rest if you can."
            }
        }

        val hrLine = s.avgRestingHeartRateBpm?.let { bpm ->
            when {
                bpm in 50..85 -> "Your average heart rate looks calm at $bpm beats per minute."
                bpm > 85 -> "Your average heart rate is a bit elevated at $bpm bpm. If you feel uneasy, let Priya know."
                else -> "Your average heart rate is $bpm bpm."
            }
        }

        val stepsLine = s.steps?.let { steps ->
            when {
                steps >= 4000 -> "Nice — you've already moved about $steps steps."
                steps >= 1500 -> "You've moved about $steps steps so far. A short walk later would be wonderful."
                else -> "Movement has been light today (about $steps steps). No rush — even a few minutes around the room counts."
            }
        }

        val closing = "This is just a friendly check-in, not a medical opinion."

        return listOfNotNull(opener, sleepLine, hrLine, stepsLine, closing)
            .joinToString(separator = " ")
    }

    private fun fmt(v: Double): String = String.format("%.1f", v)
}
