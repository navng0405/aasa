package com.aasa.eldercare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.model.ModelRunner
import com.aasa.eldercare.model.RemoteLocalGemmaRunner
import com.aasa.eldercare.network.AgentMessageResponse
import com.aasa.eldercare.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Aasa home / chat screen.
 *
 * The screen is intentionally simple in Phase 2:
 *   - the user types (or taps a sample) -> [inputText]
 *   - we POST it to the local FastAPI bridge -> [isLoading]
 *   - we either show the parsed agent response -> [parsedResponse]
 *   - or a human-readable error -> [errorMessage]
 *
 * The raw model output is also surfaced in [parsedResponse.rawResponse]
 * for debugging.
 */
data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val parsedResponse: AgentMessageResponse? = null
)

class HomeViewModel(
    private val modelRunner: ModelRunner
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    /**
     * Convenience used by the sample buttons: replace the input box with
     * the given text and immediately send it.
     */
    fun sendSample(message: String) {
        _uiState.value = _uiState.value.copy(inputText = message)
        sendCurrentMessage()
    }

    fun sendCurrentMessage() {
        val message = _uiState.value.inputText.trim()
        if (message.isEmpty() || _uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                parsedResponse = null
            )
            runCatching { modelRunner.sendMessage(message) }
                .onSuccess { response ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        parsedResponse = response,
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        parsedResponse = null,
                        errorMessage = error.toReadableMessage()
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    /**
     * Default factory that wires a real [RemoteLocalGemmaRunner] backed by
     * [RetrofitClient]. Tests / previews can construct [HomeViewModel]
     * directly with a fake [ModelRunner].
     */
    class Factory(
        private val modelRunner: ModelRunner =
            RemoteLocalGemmaRunner(RetrofitClient.apiService)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return HomeViewModel(modelRunner) as T
        }
    }
}
