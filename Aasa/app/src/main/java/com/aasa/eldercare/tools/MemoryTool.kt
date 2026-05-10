package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.MemoryRepository

/**
 * Real, Room-backed memory tool. For `SAVE_MEMORY` we materialize the
 * Gemma-extracted arguments (name, event, date, note, …) into a
 * [com.aasa.eldercare.data.entity.MemoryEntity] row and acknowledge.
 *
 * The tool also handles the *defensive* case where Gemma misroutes a
 * memory statement to ChatTool: the orchestrator still flips the
 * routing back to MemoryTool but the arguments may be empty. In that
 * case we fall back to the original user message (stashed under
 * `userMessage` / `note`) and a coarse type derived from keywords like
 * `"birthday"` or `"favorite"`.
 */
class MemoryTool(
    private val repository: MemoryRepository
) : AgentTool {

    override val name: String = ToolNames.MEMORY

    override suspend fun execute(action: AgentAction): ToolResult {
        val type = resolveType(action)
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

    // ---------------------------------------------------------------
    // Type / title / value derivation
    // ---------------------------------------------------------------

    private fun resolveType(action: AgentAction): String {
        val args = action.arguments
        val explicit = args.stringOrNull("type") ?: args.stringOrNull("memoryType")
        if (explicit != null) return explicit

        val userMessage = args.stringOrNull("userMessage")
            ?: action.assistantResponse
        return IntentKeywords.deriveMemoryType(userMessage)
    }

    private fun buildTitle(action: AgentAction): String {
        val args = action.arguments
        val person = args.stringOrNull("personName") ?: args.stringOrNull("person")
        val event = args.stringOrNull("event") ?: args.stringOrNull("type")
        val explicitTitle = args.stringOrNull("title")

        when {
            person != null && event != null -> return "$person - $event"
            person != null -> return person
            event != null -> return event
            explicitTitle != null -> return explicitTitle
        }

        // Best-effort: try to derive "<Name> - <event>" from the raw
        // user message – useful when Gemma left arguments empty.
        val userMessage = args.stringOrNull("userMessage")
        if (userMessage != null) {
            deriveTitleFromUserMessage(userMessage)?.let { return it }
        }
        return "Memory"
    }

    private fun buildValue(action: AgentAction): String {
        val args = action.arguments
        val date = args.stringOrNull("date") ?: args.stringOrNull("when")
        val note = args.stringOrNull("note")
            ?: args.stringOrNull("text")
            ?: args.stringOrNull("userMessage")

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

    /**
     * Tiny extractor for the common "<Name>'s <event> is <date>" shape.
     * Returns e.g. `"Ananya - birthday"` for
     * `"My granddaughter Ananya's birthday is May 12."`.
     */
    private fun deriveTitleFromUserMessage(message: String): String? {
        for (keyword in EVENT_KEYWORDS) {
            val pattern = Regex("([A-Za-z]+)'s\\s+$keyword", RegexOption.IGNORE_CASE)
            val match = pattern.find(message) ?: continue
            val name = match.groupValues[1]
            return "$name - $keyword"
        }
        // No possessive name; surface just the event keyword if present.
        val lower = message.lowercase()
        return EVENT_KEYWORDS.firstOrNull { lower.contains(it) }
            ?.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val INTENT_SAVE = "SAVE_MEMORY"

        private val EVENT_KEYWORDS = listOf(
            "birthday",
            "anniversary",
            "wedding"
        )
    }
}
