package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.MemoryRepository

/**
 * Real, Room-backed memory tool. For `SAVE_MEMORY` we materialize the
 * Gemma-extracted arguments (name, event, date, note, …) into a
 * [com.aasa.eldercare.data.entity.MemoryEntity] row and acknowledge.
 */
class MemoryTool(
    private val repository: MemoryRepository
) : AgentTool {

    override val name: String = ToolNames.MEMORY

    override suspend fun execute(action: AgentAction): ToolResult {
        val type = (action.arguments.stringOrNull("type")
            ?: action.arguments.stringOrNull("memoryType")
            ?: deriveTypeFromIntent(action.intent))
        val title = buildTitle(action)
        val value = buildValue(action)

        return when (action.intent.uppercase()) {
            INTENT_SAVE -> {
                val saved = repository.saveMemory(type = type, title = title, value = value)
                ToolResult.ok(
                    message = "Saved memory locally: ${saved.title} - ${saved.value}",
                    data = mapOf(
                        "memoryId" to saved.id,
                        "type" to saved.type,
                        "title" to saved.title,
                        "value" to saved.value,
                        "intent" to action.intent,
                        "persisted" to true
                    )
                )
            }
            else -> ToolResult.ok(
                message = "Memory intent received: ${action.intent}. ($title - $value)",
                data = mapOf(
                    "intent" to action.intent,
                    "persisted" to false
                )
            )
        }
    }

    private fun deriveTypeFromIntent(intent: String): String =
        when (intent.uppercase()) {
            INTENT_SAVE -> "GENERAL"
            else -> intent.uppercase()
        }

    private fun buildTitle(action: AgentAction): String {
        val args = action.arguments
        val person = args.stringOrNull("personName") ?: args.stringOrNull("person")
        val event = args.stringOrNull("event") ?: args.stringOrNull("type")
        return when {
            person != null && event != null -> "$person - $event"
            person != null -> person
            event != null -> event
            args.stringOrNull("title") != null -> args.stringOrNull("title")!!
            else -> "Memory"
        }
    }

    private fun buildValue(action: AgentAction): String {
        val args = action.arguments
        val date = args.stringOrNull("date") ?: args.stringOrNull("when")
        val note = args.stringOrNull("note") ?: args.stringOrNull("text")

        val parts = listOfNotNull(
            date?.let { "date=$it" },
            note?.let { "note=$it" }
        )

        return when {
            parts.isNotEmpty() -> parts.joinToString(", ")
            args.isNotEmpty() -> args.flattenToText()
            action.assistantResponse.isNotBlank() -> action.assistantResponse
            else -> "(no details)"
        }
    }

    companion object {
        private const val INTENT_SAVE = "SAVE_MEMORY"
    }
}
