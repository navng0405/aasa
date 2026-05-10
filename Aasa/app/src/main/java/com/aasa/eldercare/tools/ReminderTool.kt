package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Phase-3 placeholder for reminders. We just acknowledge the request;
 * scheduling will land with the persistence layer.
 */
class ReminderTool : AgentTool {
    override val name: String = ToolNames.REMINDER

    override suspend fun execute(action: AgentAction): ToolResult {
        return ToolResult.ok(
            message = "Reminder request captured. Reminder persistence will be added later.",
            data = mapOf(
                "intent" to action.intent,
                "arguments" to action.arguments
            )
        )
    }
}
