package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * A locally-executable side-effect Gemma can route to. Each tool is
 * identified by its [name] (matched against [AgentAction.tool]) and
 * produces a [ToolResult] the orchestrator can render.
 */
interface AgentTool {
    val name: String
    suspend fun execute(action: AgentAction): ToolResult
}

/**
 * Canonical tool name strings, used in both the registry and the
 * [AgentOrchestrator][com.aasa.eldercare.agent.AgentOrchestrator]. Keeping
 * them in one place avoids string typos creeping in.
 */
object ToolNames {
    const val CHAT = "ChatTool"
    const val MEDICATION = "MedicationTool"
    const val MEMORY = "MemoryTool"
    const val SAFETY = "SafetyTool"
    const val TRUSTED_CONTACT = "TrustedContactTool"
    const val REMINDER = "ReminderTool"
    const val SCAM_SHIELD = "ScamShieldTool"
    const val FALL_TRIAGE = "FallTriageTool"
    const val MOBILITY_SHIELD = "MobilityShieldTool"
    const val HEALTH_BRIEFING = "HealthBriefingTool"
    const val WELLNESS_CHECK = "WellnessCheckTool"
    const val DOCUMENT_READER = "DocumentReaderTool"
}
