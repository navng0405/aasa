package com.aasa.eldercare.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.data.repository.MemoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [MemoryScreen]. Memories already come back from Room as a
 * Flow ordered by createdAt desc, so the screen just collects whatever
 * the repository emits. Refresh is essentially a no-op (the Flow is
 * already live), but we still expose it so the UI behavior matches
 * the Medication screen and to give the elder explicit feedback.
 */
class MemoryViewModel(
    memoryRepository: MemoryRepository
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val memoriesFlow = memoryRepository.getAllMemories()
        .catch { error ->
            errorMessage.value = error.toReadableMessage()
            emit(emptyList())
        }
        .onEach {
            // Once the first batch arrives, drop the initial loading
            // spinner so the empty state can render even when the
            // user hasn't tapped Refresh.
            isRefreshing.value = false
        }

    val uiState: StateFlow<MemoryUiState> = combine(
        memoriesFlow,
        isRefreshing,
        errorMessage
    ) { memories, refreshing, error ->
        MemoryUiState(
            isLoading = refreshing,
            memories = memories,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = MemoryUiState(isLoading = true)
    )

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        errorMessage.value = null
        // The underlying Flow is already live; we just want a brief
        // visible spinner so the elder sees that Refresh did something.
        viewModelScope.launch {
            delay(SPINNER_VISIBLE_MILLIS)
            isRefreshing.value = false
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MemoryViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return MemoryViewModel(
                memoryRepository = application.memoryRepository
            ) as T
        }
    }

    companion object {
        private const val SPINNER_VISIBLE_MILLIS = 300L
    }
}
