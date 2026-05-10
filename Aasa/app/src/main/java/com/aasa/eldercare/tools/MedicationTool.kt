package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.MedicationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real, Room-backed medication tool.
 *
 *  - `LOG_MEDICATION`: finds-or-creates the medication and inserts a
 *    `MedicationLogEntity` row. Returns `data.persisted = true` so the
 *    UI knows something actually hit the database.
 *  - `CHECK_MEDICATION`: reads today's logs (joined to medication
 *    names) and produces a readable summary.
 */
class MedicationTool(
    private val repository: MedicationRepository
) : AgentTool {

    override val name: String = ToolNames.MEDICATION

    override suspend fun execute(action: AgentAction): ToolResult {
        return when (action.intent.uppercase()) {
            INTENT_LOG -> handleLog(action)
            INTENT_CHECK -> handleCheck(action)
            else -> ToolResult.ok(
                message = "Medication intent received: ${action.intent}.",
                data = mapOf(
                    "intent" to action.intent,
                    "persisted" to false
                )
            )
        }
    }

    private suspend fun handleLog(action: AgentAction): ToolResult {
        val medicineName = extractMedicationName(action)
        val status = action.arguments.stringOrNull("status") ?: DEFAULT_STATUS

        val logged = repository.logMedication(medicineName, status)

        return ToolResult.ok(
            message = "Okay, I logged that you took your ${logged.medication.name} at ${formatTime(logged.loggedAt)}.",
            data = mapOf(
                "medicationId" to logged.medication.id,
                "medicationName" to logged.medication.name,
                "status" to logged.status,
                "loggedAt" to logged.loggedAt,
                "logId" to logged.logId,
                "intent" to action.intent,
                "persisted" to true
            )
        )
    }

    private suspend fun handleCheck(action: AgentAction): ToolResult {
        val requestedMedicineName = extractMedicationName(action)
        val statuses = repository.getTodayLogsWithNames()
        val matchingStatus = statuses.firstOrNull {
            normalizeMedicationName(it.medicationName) == normalizeMedicationName(requestedMedicineName)
        }

        val message = if (matchingStatus != null) {
            "Yes, you took your ${matchingStatus.medicationName} at ${formatTime(matchingStatus.loggedAt)}."
        } else if (statuses.isEmpty()) {
            "No medications logged today yet."
        } else {
            // De-duplicate by medication name keeping the latest status
            // (the query is already DESC by loggedAt).
            val latestPerMed = statuses
                .groupBy { it.medicationName }
                .map { (name, rows) -> name to rows.first().status }
            val summary = latestPerMed.joinToString(separator = "; ") { (name, status) ->
                "$name -> $status"
            }
            "Today's medication status: $summary."
        }

        return ToolResult.ok(
            message = message,
            data = mapOf(
                "intent" to action.intent,
                "medicationName" to requestedMedicineName,
                "matchedMedicationName" to matchingStatus?.medicationName,
                "matchedLoggedAt" to matchingStatus?.loggedAt,
                "logCount" to statuses.size,
                "persisted" to false
            )
        )
    }

    private fun extractMedicationName(action: AgentAction): String {
        for (key in MEDICATION_NAME_KEYS) {
            action.arguments.stringOrNull(key)?.let { return cleanMedicationName(it) }
        }

        val userMessage = action.arguments.stringOrNull("userMessage").orEmpty()
        val lowerMessage = userMessage.lowercase()
        for (phrase in USER_MESSAGE_NAME_PREFIXES) {
            val index = lowerMessage.indexOf(phrase)
            if (index >= 0) {
                val candidate = userMessage
                    .substring(index + phrase.length)
                    .trim()
                    .trim('.', '?', '!', ',')
                if (candidate.isNotBlank()) return cleanMedicationName(candidate)
            }
        }

        return DEFAULT_MEDICINE
    }

    private fun cleanMedicationName(value: String): String =
        value.trim()
            .removePrefix("my ")
            .trim()
            .ifBlank { DEFAULT_MEDICINE }

    private fun normalizeMedicationName(value: String): String =
        value.lowercase().replace(".", "").trim()

    private fun formatTime(epochMs: Long): String =
        timeFormatter.format(Date(epochMs))

    companion object {
        private const val DEFAULT_MEDICINE = "medicine"
        private const val DEFAULT_STATUS = "taken"
        private const val INTENT_LOG = "LOG_MEDICATION"
        private const val INTENT_CHECK = "CHECK_MEDICATION"
        private val MEDICATION_NAME_KEYS = listOf(
            "medicationName",
            "medicineName",
            "medication",
            "medicine",
            "name"
        )
        private val USER_MESSAGE_NAME_PREFIXES = listOf(
            "did i take my",
            "did i take",
            "have i taken my",
            "have i taken",
            "i took my",
            "i took",
            "took my",
            "took"
        )
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    }
}
