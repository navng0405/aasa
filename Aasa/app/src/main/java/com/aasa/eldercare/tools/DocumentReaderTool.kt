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
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()
        val kind = detectKind(lower)
        val amount = MONEY_REGEX.find(normalized)?.value
        val dueDate = DATE_REGEX.find(normalized)?.value
        val reference = REFERENCE_REGEX.find(normalized)?.value
        val actionPhrase = detectActionPhrase(lower)
        val entity = detectEntity(normalized)

        val first = buildString {
            append("This appears to be ")
            append(kind)
            entity?.let { append(" from ").append(it) }
            amount?.let { append(" with an amount of ").append(it) }
            dueDate?.let { append(" and a date ").append(dueDate) }
            append(".")
        }

        val second = buildString {
            append("It asks you to ")
            append(actionPhrase)
            reference?.let { append(" using reference ").append(it) }
            append(".")
        }
        return "$first $second"
    }

    private fun detectKind(lower: String): String = when {
        anyContains(lower, "invoice", "tax invoice", "amount due", "total due", "bill") ->
            "a billing notice"
        anyContains(lower, "application form", "apply", "scheme", "eligibility", "benefit") ->
            "an application form"
        anyContains(lower, "hospital", "clinic", "medical", "medicare", "statement") ->
            "a medical statement"
        anyContains(lower, "final warning", "urgent", "suspended", "legal action") ->
            "an urgent warning letter"
        else -> "a document notice"
    }

    private fun detectActionPhrase(lower: String): String = when {
        anyContains(lower, "pay by", "due date", "amount due", "total due") ->
            "review the charges and pay through the official channel"
        anyContains(lower, "submit", "application", "documents required", "attach") ->
            "fill and submit the requested form details"
        anyContains(lower, "verify account", "click link", "otp", "verification code") ->
            "verify the sender first and avoid sharing codes or clicking unknown links"
        else ->
            "review the key instruction and confirm with a trusted helper before acting"
    }

    private fun detectEntity(text: String): String? =
        ENTITY_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.length >= 3 }
            ?.take(48)

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
        private val DATE_REGEX = Regex(
            """\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+\d{1,2},?\s+\d{2,4})\b""",
            RegexOption.IGNORE_CASE
        )
        private val REFERENCE_REGEX = Regex(
            """\b(?:ref(?:erence)?|invoice|account|application|claim)\s*(?:no|number|#|id)?[:\-\s]*([A-Z0-9\-]{4,})\b""",
            RegexOption.IGNORE_CASE
        )
        private val ENTITY_REGEX = Regex(
            """(?:from|issuer|hospital|clinic|department|ministry|bank)\s*[:\-]?\s*([A-Za-z0-9&.,\-\s]{3,})""",
            RegexOption.IGNORE_CASE
        )
    }
}
