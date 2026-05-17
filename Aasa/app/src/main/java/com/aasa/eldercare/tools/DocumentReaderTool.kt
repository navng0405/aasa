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
                    ToolResultKeys.DOCUMENT_SUMMARY to "No readable text found.",
                    ToolResultKeys.DOCUMENT_REQUESTED_ACTION to "Take another photo with better lighting.",
                    ToolResultKeys.DOCUMENT_WORRIES to "Unreadable image.",
                    ToolResultKeys.DOCUMENT_IGNORE to "Ignore blurry letters caused by camera angle.",
                    ToolResultKeys.DOCUMENT_RISK to "LOW",
                    ToolResultKeys.PERSISTED to false
                )
            )
        }

        val lower = text.lowercase()
        val summary = summarize(lower)
        val requestedAction = requestedAction(lower)
        val worries = worries(lower)
        val ignore = ignorableParts(lower)
        val risk = riskBand(lower)
        val spoken = "$summary $requestedAction"

        return ToolResult.ok(
            message = spoken,
            data = mapOf(
                ToolResultKeys.ACTION_TYPE to ToolActionTypes.DOCUMENT_READING,
                ToolResultKeys.DOCUMENT_TEXT to text.take(1800),
                ToolResultKeys.DOCUMENT_SUMMARY to summary,
                ToolResultKeys.DOCUMENT_REQUESTED_ACTION to requestedAction,
                ToolResultKeys.DOCUMENT_WORRIES to worries,
                ToolResultKeys.DOCUMENT_IGNORE to ignore,
                ToolResultKeys.DOCUMENT_RISK to risk,
                ToolResultKeys.PERSISTED to false
            )
        )
    }

    private fun summarize(lower: String): String = when {
        lower.contains("invoice") || lower.contains("bill") || lower.contains("amount due") ->
            "This looks like a bill or payment notice. It shows charges and may include a due date."
        lower.contains("application form") || lower.contains("apply") || lower.contains("scheme") ->
            "This looks like an application or benefits form. It is asking for personal details and eligibility information."
        lower.contains("urgent") && lower.contains("payment") ->
            "This looks like an urgent payment message. It is trying to push fast action."
        else ->
            "This looks like an official letter or form. It includes instructions and key dates to review carefully."
    }

    private fun requestedAction(lower: String): String = when {
        lower.contains("due date") || lower.contains("pay by") || lower.contains("amount due") ->
            "What this asks you to do: verify the amount and due date, then pay only through a trusted official channel."
        lower.contains("submit") || lower.contains("application") || lower.contains("documents required") ->
            "What this asks you to do: fill the form and submit the listed documents before the deadline."
        lower.contains("click link") || lower.contains("verify account") ->
            "What this asks you to do: it asks you to verify details quickly; do not use unknown links."
        else ->
            "What this asks you to do: review the main instruction, note any deadline, and confirm with a trusted person if unsure."
    }

    private fun worries(lower: String): String {
        val flags = mutableListOf<String>()
        if (lower.contains("gift card") || lower.contains("wire transfer") || lower.contains("crypto")) {
            flags += "Unusual payment method request."
        }
        if (lower.contains("otp") || lower.contains("one time password") || lower.contains("verification code")) {
            flags += "Request for OTP/security code."
        }
        if (lower.contains("final warning") || lower.contains("suspended") || lower.contains("legal action")) {
            flags += "Fear or threat language."
        }
        if (lower.contains("immediate") || lower.contains("within 24 hours") || lower.contains("urgent")) {
            flags += "Artificial urgency."
        }
        return when {
            flags.isNotEmpty() -> flags.joinToString(" ")
            lower.contains("bill") -> "Check duplicate fees, incorrect dates, and unexplained penalties."
            else -> "Watch for mismatched names, strange contacts, or pressure to act quickly."
        }
    }

    private fun ignorableParts(lower: String): String = when {
        lower.contains("terms and conditions") || lower.contains("boilerplate") ->
            "You can ignore long legal boilerplate for now and focus on amount, deadline, and required action."
        lower.contains("advertisement") || lower.contains("offer") ->
            "You can ignore marketing offers that are unrelated to the main notice."
        else ->
            "You can ignore repeated reference numbers and decorative formatting while deciding next steps."
    }

    private fun riskBand(lower: String): String = when {
        lower.contains("gift card") || lower.contains("wire transfer") ||
            lower.contains("otp") || lower.contains("verification code") -> "HIGH"
        lower.contains("urgent") || lower.contains("final warning") -> "MEDIUM"
        else -> "LOW"
    }
}
