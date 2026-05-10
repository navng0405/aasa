package com.aasa.eldercare.ui.medication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.data.dao.TodayMedicationStatus
import com.aasa.eldercare.data.entity.MedicationEntity
import com.aasa.eldercare.data.repository.MedicationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives [MedicationScreen]. Combines the live medications Flow with a
 * snapshot of today's medication logs that the screen can refresh
 * on demand. We re-query today's logs on init and whenever the Refresh
 * button is tapped; medications themselves come straight from Room as
 * a Flow so they update reactively if the agent inserts a new row.
 */
class MedicationViewModel(
    private val medicationRepository: MedicationRepository
) : ViewModel() {

    private val todayLogs = MutableStateFlow<List<TodayMedicationStatus>>(emptyList())
    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MedicationUiState> = combine(
        medicationRepository.getAllMedications(),
        todayLogs,
        isRefreshing,
        errorMessage
    ) { medications, logs, refreshing, error ->
        MedicationUiState(
            isLoading = refreshing,
            medications = medications.map { med -> med.toItem(logs) },
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = MedicationUiState(isLoading = true)
    )

    init {
        refresh()
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            runCatching { medicationRepository.getTodayLogsWithNames() }
                .onSuccess { todayLogs.value = it }
                .onFailure { errorMessage.value = it.toReadableMessage() }
            isRefreshing.value = false
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    private fun MedicationEntity.toItem(
        logs: List<TodayMedicationStatus>
    ): MedicationItem {
        val match = logs.firstOrNull { log ->
            normalize(log.medicationName) == normalize(name)
        }
        val status = when {
            match == null -> MedicationStatus.PENDING
            match.status.equals("missed", ignoreCase = true) ->
                MedicationStatus.MISSED
            else -> MedicationStatus.TAKEN
        }
        return MedicationItem(
            id = id,
            name = name,
            dosage = dosage,
            scheduleTime = scheduleTime,
            status = status,
            latestLoggedTime = match?.loggedAt?.let(::formatTime)
        )
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(".", "")

    private fun formatTime(epochMs: Long): String =
        timeFormatter.format(Date(epochMs))

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MedicationViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return MedicationViewModel(
                medicationRepository = application.medicationRepository
            ) as T
        }
    }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    }
}
