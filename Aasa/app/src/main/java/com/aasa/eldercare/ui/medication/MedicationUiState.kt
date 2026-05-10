package com.aasa.eldercare.ui.medication

/**
 * One row rendered on the Medication screen. Combines the medication
 * record with whatever the elder has done with it today so the card
 * can show a status chip and the latest logged time without having to
 * re-query Room from the Composable.
 */
data class MedicationItem(
    val id: Long,
    val name: String,
    val dosage: String?,
    val scheduleTime: String?,
    val status: MedicationStatus,
    val latestLoggedTime: String?
)

/**
 * Coarse status used by the UI. We deliberately keep "Missed" separate
 * from "Pending" so the chip can change color and tone, but we only
 * surface "Missed" if the underlying log row explicitly says so.
 */
enum class MedicationStatus {
    TAKEN,
    PENDING,
    MISSED
}

data class MedicationUiState(
    val isLoading: Boolean = false,
    val medications: List<MedicationItem> = emptyList(),
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && medications.isEmpty()
}
