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
            INTENT_RECALL -> recallMemory(action, type)
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

    private suspend fun recallMemory(action: AgentAction, type: String): ToolResult {
        val memories = repository.findByType(type)
        if (memories.isEmpty()) {
            return ToolResult.ok(
                message = "I don't have that saved in your memories yet.",
                data = mapOf(
                    "intent" to action.intent,
                    "type" to type,
                    "found" to false
                )
            )
        }

        val userMessage = action.arguments.stringOrNull("userMessage").orEmpty()
        val best = memories.maxByOrNull { it.matchScore(userMessage) } ?: memories.first()
        val answer = when (type.uppercase()) {
            "BIRTHDAY" -> {
                val date = best.extractRememberedDate()
                if (date != null) {
                    "I have ${best.personLabel()} birthday as $date. I may be remembering this wrong, so please correct me if needed."
                } else {
                    "I found this birthday memory: ${best.value}. I may be remembering this wrong, so please correct me if needed."
                }
            }
            else -> "I found this in your memories: ${best.title} - ${best.value}"
        }

        return ToolResult.ok(
            message = answer,
            data = mapOf(
                "intent" to action.intent,
                "type" to type,
                "found" to true,
                "memoryId" to best.id,
                "title" to best.title,
                "value" to best.value
            )
        )
    }

    /**
     * Tiny extractor for the common "<Name>'s <event> is <date>" shape.
     * Returns e.g. `"Ananya - birthday"` for
     * `"My granddaughter Ananya's birthday is May 12."`.
     */
    private fun deriveTitleFromUserMessage(message: String): String? {
        for ((canonical, aliases) in EVENT_KEYWORDS) {
            val keywordPattern = aliases.joinToString("|") { Regex.escape(it) }
            val pattern = Regex("([A-Za-z]+)'?s\\s+($keywordPattern)", RegexOption.IGNORE_CASE)
            val match = pattern.find(message) ?: continue
            val name = match.groupValues[1].normalizeFamilyLabel()
            return "$name - $canonical"
        }
        // No possessive name; surface just the event keyword if present.
        val lower = message.lowercase()
        return EVENT_KEYWORDS.firstNotNullOfOrNull { (canonical, aliases) ->
            canonical.takeIf { aliases.any { alias -> lower.contains(alias) } }
        }?.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val INTENT_SAVE = "SAVE_MEMORY"
        private const val INTENT_RECALL = "RECALL_MEMORY"

        private val EVENT_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "birthday" to listOf("birthday", "bithday"),
            "anniversary" to listOf("anniversary"),
            "wedding" to listOf("wedding")
        )
    }
}

private fun com.aasa.eldercare.data.entity.MemoryEntity.matchScore(query: String): Int {
    if (query.isBlank()) return 0
    val haystack = "$title $value".lowercase()
    return query.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 }
        .distinct()
        .count { token -> haystack.contains(token) }
}

private fun com.aasa.eldercare.data.entity.MemoryEntity.extractRememberedDate(): String? {
    Regex("date=([^,]+(?:,\\s*\\d{4})?)", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val text = value
        .removePrefix("date=")
        .replace("note=", "")
        .trim()
    val pattern = Regex(
        "\\b(?:is|on|was|falls on)\\s+(?:on\\s+)?([A-Za-z]+\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?)",
        RegexOption.IGNORE_CASE
    )
    return pattern.find(text)?.groupValues?.getOrNull(1)?.trimEnd('.', ',', ';')
}

private fun com.aasa.eldercare.data.entity.MemoryEntity.personLabel(): String {
    val titleName = title.substringBefore(" - ").takeIf {
        it.isNotBlank() && !it.equals("Birthday", ignoreCase = true)
    }
    if (titleName != null) return "$titleName's"

    val relation = Regex("\\b(my\\s+[A-Za-z]+)", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()
    return relation?.let { "your ${it.removePrefix("my ").normalizeFamilyLabel()}'s" } ?: "that"
}

private fun String.normalizeFamilyLabel(): String = when (lowercase()) {
    "granddaugter", "granddaugther", "grandaughter" -> "granddaughter"
    "granddaugters", "granddaugthers", "grandaughters" -> "granddaughter"
    else -> this
}
