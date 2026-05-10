package com.aasa.eldercare.tools

import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.repository.MedicationRepository

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
        val medicineName = action.arguments.stringOrNull("medicineName") ?: DEFAULT_MEDICINE
        val status = action.arguments.stringOrNull("status") ?: DEFAULT_STATUS

        val logged = repository.logMedication(medicineName, status)

        return ToolResult.ok(
            message = "Medication ${logged.medication.name} marked as ${logged.status}.",
            data = mapOf(
                "medicationId" to logged.medication.id,
                "medicationName" to logged.medication.name,
                "status" to logged.status,
                "logId" to logged.logId,
                "intent" to action.intent,
                "persisted" to true
            )
        )
    }

    private suspend fun handleCheck(action: AgentAction): ToolResult {
        val statuses = repository.getTodayLogsWithNames()

        val message = if (statuses.isEmpty()) {
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
                "logCount" to statuses.size,
                "persisted" to false
            )
        )
    }

    companion object {
        private const val DEFAULT_MEDICINE = "medicine"
        private const val DEFAULT_STATUS = "taken"
        private const val INTENT_LOG = "LOG_MEDICATION"
        private const val INTENT_CHECK = "CHECK_MEDICATION"
    }
}
