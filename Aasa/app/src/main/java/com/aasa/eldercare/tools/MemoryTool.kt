package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Phase-3 mock memory tool. Builds a human readable summary from the
 * arguments Gemma extracted (e.g. `personName`, `event`, `date`) and
 * returns it as a successful result. No persistence yet.
 */
class MemoryTool : AgentTool {
    override val name: String = ToolNames.MEMORY

    override suspend fun execute(action: AgentAction): ToolResult {
        val summary = buildSummary(action)
        return when (action.intent.uppercase()) {
            INTENT_SAVE -> ToolResult.ok(
                message = "Saved memory locally: $summary",
                data = mapOf("summary" to summary, "intent" to action.intent)
            )
            else -> ToolResult.ok(
                message = "Memory intent received: ${action.intent}. ($summary)",
                data = mapOf("summary" to summary, "intent" to action.intent)
            )
        }
    }

    private fun buildSummary(action: AgentAction): String {
        val args = action.arguments
        val person = args.stringOrNull("personName") ?: args.stringOrNull("person")
        val event = args.stringOrNull("event") ?: args.stringOrNull("type")
        val date = args.stringOrNull("date") ?: args.stringOrNull("when")
        val note = args.stringOrNull("note") ?: args.stringOrNull("text")

        val pieces = listOfNotNull(
            person?.let { "person=$it" },
            event?.let { "event=$it" },
            date?.let { "date=$it" },
            note?.let { "note=$it" }
        )

        return when {
            pieces.isNotEmpty() -> pieces.joinToString(", ")
            args.isNotEmpty() -> args.flattenToText()
            action.assistantResponse.isNotBlank() -> action.assistantResponse
            else -> "(no details)"
        }
    }

    companion object {
        private const val INTENT_SAVE = "SAVE_MEMORY"
    }
}
