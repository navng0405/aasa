package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.MedicationRepository
import com.aasa.eldercare.data.repository.MemoryRepository
import com.aasa.eldercare.data.repository.TrustedContactRepository

/**
 * Central dispatch from a Gemma-supplied tool name to the matching local
 * [AgentTool]. Unknown tool names always produce a *failed* [ToolResult]
 * – the orchestrator surfaces this to the UI, but never crashes.
 */
class ToolRegistry private constructor(
    tools: List<AgentTool>
) {
    private val toolsByName: Map<String, AgentTool> = tools.associateBy { it.name }

    /**
     * Look up the tool matching [AgentAction.tool] and run it. Falls back
     * to [ChatTool] when the action references that name explicitly; for
     * anything else unrecognised we return a failed result.
     */
    suspend fun execute(action: AgentAction): ToolResult {
        val tool = toolsByName[action.tool]
            ?: return ToolResult.fail(
                message = "Unknown tool: ${action.tool}",
                data = mapOf(
                    "requestedTool" to action.tool,
                    "availableTools" to toolsByName.keys.toList()
                )
            )
        return tool.execute(action)
    }

    fun availableTools(): Set<String> = toolsByName.keys

    companion object {
        /**
         * Default registry wiring all six tools with their Room-backed
         * repositories. Phase 4: Medication / Memory / TrustedContact
         * persist; Chat / Safety / Reminder remain stateless.
         */
        fun createDefault(
            medicationRepository: MedicationRepository,
            memoryRepository: MemoryRepository,
            trustedContactRepository: TrustedContactRepository
        ): ToolRegistry = ToolRegistry(
            listOf(
                ChatTool(),
                MedicationTool(medicationRepository),
                MemoryTool(memoryRepository),
                SafetyTool(trustedContactRepository),
                TrustedContactTool(trustedContactRepository),
                ReminderTool(),
                ScamShieldTool(trustedContactRepository),
                FallTriageTool(trustedContactRepository),
                MobilityShieldTool(trustedContactRepository)
            )
        )

        /** Test seam: register an arbitrary list of tools. */
        fun of(vararg tools: AgentTool): ToolRegistry = ToolRegistry(tools.toList())
    }
}
