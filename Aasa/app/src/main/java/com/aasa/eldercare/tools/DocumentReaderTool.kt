package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction

/**
 * Elder-friendly helper that converts OCR'd form/letter text into
 * plain-language guidance.
 */
class DocumentReaderTool : AgentTool {

    override val name: String = ToolNames.DOCUMENT_READER

    override suspend fun execute(action: AgentAction): ToolResult {
        val text = action.arguments[ToolResultKeys.DOCUMENT_TEXT]
            ?.toString()
            .orEmpty()
            .trim()
        if (text.isBlank()) {
            return ToolResult.fail(
                message = "I couldn't read that page clearly. Try a brighter photo with the page flat.",
                data = mapOf(
                    ToolResultKeys.ACTION_TYPE to ToolActionTypes.DOCUMENT_READING,
                    ToolResultKeys.DOCUMENT_SUMMARY to
                        "I could not read enough text from this photo. Please retake it with brighter light and the page fully visible.",
                    ToolResultKeys.DOCUMENT_RISK to "LOW",
                    ToolResultKeys.PERSISTED to false
                )
            )
        }

        val summary = buildTwoSentenceSummary(text)
        val risk = riskBand(text.lowercase())

        return ToolResult.ok(
            message = summary,
            data = mapOf(
                ToolResultKeys.ACTION_TYPE to ToolActionTypes.DOCUMENT_READING,
                ToolResultKeys.DOCUMENT_SUMMARY to summary,
                ToolResultKeys.DOCUMENT_RISK to risk,
                ToolResultKeys.PERSISTED to false
            )
        )
    }

    private fun buildTwoSentenceSummary(text: String): String {
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 3 }
            .toList()
        val normalized = lines.joinToString(" ")
        val lower = normalized.lowercase()

        val highlights = extractHighlights(lines)
        val keyValues = extractKeyValues(lines, normalized)
        val action = detectActionInstruction(lines, lower)

        val firstSentence = buildString {
            val headline = highlights.take(2).joinToString("; ")
                .ifBlank { lines.take(2).joinToString("; ") }
                .take(180)
            append("I read this document as: ")
            append(headline.ifBlank { "text is partially visible" })
            if (keyValues.isNotEmpty()) {
                append(". Key details found: ")
                append(keyValues.take(4).joinToString(", "))
            }
            append(".")
        }

        val secondSentence = buildString {
            append("It is asking you to ")
            append(action)
            extractDeadlineOrDate(normalized)?.let { append(" by ").append(it) }
            extractAmount(normalized)?.let { append(" and review amount ").append(it) }
            extractReference(normalized)?.let { append(" (reference ").append(it).append(")") }
            append(".")
        }

        return "$firstSentence $secondSentence"
    }

    private fun extractHighlights(lines: List<String>): List<String> =
        lines
            .sortedByDescending { scoreLine(it) }
            .take(3)

    private fun scoreLine(line: String): Int {
        val lower = line.lowercase()
        var score = 0
        if (line.any { it.isDigit() }) score += 2
        if (line.contains(":")) score += 2
        if (KEY_LINE_HINTS.any { lower.contains(it) }) score += 4
        return score
    }

    private fun extractKeyValues(lines: List<String>, normalized: String): List<String> {
        val extracted = mutableListOf<String>()
        lines.forEach { line ->
            val lower = line.lowercase()
            if (KEY_VALUE_HINTS.none { lower.contains(it) }) return@forEach
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                val key = parts[0].trim().take(24)
                val value = parts[1].trim().take(48)
                extracted += "$key=$value"
            } else {
                extracted += line.take(64)
            }
        }
        extractAmount(normalized)?.let { extracted += "amount=$it" }
        extractDeadlineOrDate(normalized)?.let { extracted += "date=$it" }
        extractReference(normalized)?.let { extracted += "ref=$it" }
        return extracted.distinct()
    }

    private fun detectActionInstruction(lines: List<String>, lower: String): String {
        val imperativeLine = lines.firstOrNull { line ->
            val l = line.lowercase()
            l.startsWith("please ") ||
                l.contains("you must") ||
                l.contains("required to") ||
                l.contains("submit") ||
                l.contains("pay") ||
                l.contains("verify")
        }?.take(120)

        if (!imperativeLine.isNullOrBlank()) {
            return imperativeLine.lowercase().removeSuffix(".")
        }
        return when {
            anyContains(lower, "pay by", "amount due", "total due", "outstanding") ->
                "check this bill and pay through the official channel"
            anyContains(lower, "submit", "application", "documents required") ->
                "complete and submit the requested form details"
            anyContains(lower, "verify", "otp", "verification code", "click link") ->
                "verify the sender first and avoid sharing codes"
            else ->
                "review the visible instructions and confirm with a trusted helper before acting"
        }
    }

    private fun extractAmount(text: String): String? =
        MONEY_REGEX.find(text)?.value

    private fun extractDeadlineOrDate(text: String): String? =
        DUE_DATE_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: DATE_REGEX.find(text)?.value

    private fun extractReference(text: String): String? =
        REFERENCE_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun anyContains(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }

    private fun riskBand(lower: String): String = when {
        lower.contains("gift card") || lower.contains("wire transfer") ||
            lower.contains("otp") || lower.contains("verification code") -> "HIGH"
        lower.contains("urgent") || lower.contains("final warning") -> "MEDIUM"
        else -> "LOW"
    }

    companion object {
        private val MONEY_REGEX = Regex("""(?:₹|\$|USD|INR)\s?\d[\d,]*(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE)
        private val DUE_DATE_REGEX = Regex(
            """(?:due(?:\s+date)?|pay\s+by|before)\s*[:\-]?\s*([A-Za-z0-9,/\-\s]{4,24})""",
            RegexOption.IGNORE_CASE
        )
        private val DATE_REGEX = Regex(
            """\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+\d{1,2},?\s+\d{2,4})\b""",
            RegexOption.IGNORE_CASE
        )
        private val REFERENCE_REGEX = Regex(
            """\b(?:ref(?:erence)?|invoice|account|application|claim)\s*(?:no|number|#|id)?[:\-\s]*([A-Z0-9\-]{4,})\b""",
            RegexOption.IGNORE_CASE
        )
        private val KEY_LINE_HINTS = listOf(
            "invoice", "bill", "statement", "account", "reference", "claim",
            "due", "amount", "total", "patient", "name", "hospital", "scheme",
            "application", "deadline", "submit", "verify", "urgent"
        )
        private val KEY_VALUE_HINTS = listOf(
            "invoice", "account", "reference", "ref", "claim", "patient", "name",
            "amount", "total", "due", "date", "deadline", "scheme", "application"
        )
    }
}
