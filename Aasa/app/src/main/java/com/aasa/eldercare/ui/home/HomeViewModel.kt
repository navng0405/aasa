package com.aasa.eldercare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.model.RemoteLocalGemmaRunner
import com.aasa.eldercare.network.RetrofitClient
import com.aasa.eldercare.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Aasa home / chat screen.
 *
 * Phase 3 surfaces *both* the Gemma decision ([agentAction]) and the
 * locally-executed tool outcome ([toolExecutionSuccess], [toolResultMessage],
 * [toolResultData]).
 */
data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val agentAction: AgentAction? = null,
    val toolExecutionSuccess: Boolean? = null,
    val toolResultMessage: String? = null,
    val toolResultData: Map<String, Any?> = emptyMap()
)

class HomeViewModel(
    private val orchestrator: AgentOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

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
                agentAction = null,
                toolExecutionSuccess = null,
                toolResultMessage = null,
                toolResultData = emptyMap()
            )
            runCatching { orchestrator.handleUserMessage(message) }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agentAction = result.action,
                        toolExecutionSuccess = result.toolResult.success,
                        toolResultMessage = result.toolResult.message,
                        toolResultData = result.toolResult.data,
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agentAction = null,
                        toolExecutionSuccess = null,
                        toolResultMessage = null,
                        toolResultData = emptyMap(),
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
     * Default factory wires production dependencies: real Retrofit-backed
     * Gemma runner + the default [ToolRegistry]. Tests / previews can
     * construct the [HomeViewModel] directly with a fake orchestrator.
     */
    class Factory(
        private val orchestrator: AgentOrchestrator = defaultOrchestrator()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return HomeViewModel(orchestrator) as T
        }

        companion object {
            private fun defaultOrchestrator(): AgentOrchestrator =
                AgentOrchestrator(
                    modelRunner = RemoteLocalGemmaRunner(RetrofitClient.apiService),
                    toolRegistry = ToolRegistry.createDefault()
                )
        }
    }
}
