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
        if (lines.isEmpty()) {
            return "I could not read clear text from this photo. Please retake the photo with better light and keep the full page visible."
        }
        val normalized = lines.joinToString(" ")
        val highlights = extractHighlights(lines)
        val facts = extractFacts(lines, normalized)

        val firstSentence = buildString {
            append("Visible text: ")
            append(
                highlights.take(3)
                    .joinToString(" | ")
                    .ifBlank { lines.take(2).joinToString(" | ") }
                    .take(220)
            )
            append(".")
        }
        val secondSentence = buildString {
            append("Extracted details: ")
            append(
                facts.take(6).joinToString(", ").ifBlank {
                    "no clear amount, date, reference, or named field was confidently found"
                }.take(240)
            )
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

    private fun extractFacts(lines: List<String>, normalized: String): List<String> {
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

    private fun extractAmount(text: String): String? =
        MONEY_REGEX.find(text)?.value

    private fun extractDeadlineOrDate(text: String): String? =
        DUE_DATE_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: DATE_REGEX.find(text)?.value

    private fun extractReference(text: String): String? =
        REFERENCE_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

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
