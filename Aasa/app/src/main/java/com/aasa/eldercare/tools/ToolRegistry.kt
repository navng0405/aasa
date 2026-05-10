package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

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
        /** Default registry wiring all six Phase-3 tools. */
        fun createDefault(): ToolRegistry = ToolRegistry(
            listOf(
                ChatTool(),
                MedicationTool(),
                MemoryTool(),
                SafetyTool(),
                TrustedContactTool(),
                ReminderTool()
            )
        )

        /** Test seam: register an arbitrary list of tools. */
        fun of(vararg tools: AgentTool): ToolRegistry = ToolRegistry(tools.toList())
    }
}
