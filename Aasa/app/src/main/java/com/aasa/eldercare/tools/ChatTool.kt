package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Default fallback tool. Used for normal small-talk or whenever Gemma
 * could not classify the message into a more specific tool. We just echo
 * the assistant response back to the UI as a successful result.
 */
class ChatTool : AgentTool {
    override val name: String = ToolNames.CHAT

    override suspend fun execute(action: AgentAction): ToolResult {
        val reply = action.assistantResponse
            .takeIf { it.isNotBlank() }
            ?: "Okay."
        return ToolResult.ok(
            message = reply,
            data = mapOf("intent" to action.intent)
        )
    }
}
