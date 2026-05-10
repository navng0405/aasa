package com.aasa.eldercare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.data.entity.ConversationEntity
import com.aasa.eldercare.data.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the Aasa home / chat screen.
 *
 * Phase 4 surfaces:
 *  - the Gemma decision ([agentAction])
 *  - the live tool execution outcome ([toolExecutionSuccess], [toolResultMessage], [toolResultData])
 *  - a redundant [persistedToolResultMessage] that is only set when the
 *    tool actually wrote to Room (so the UI can show a "Persisted in
 *    Room" pill and the user can be confident the action survived)
 *  - [recentConversations] is a separate, always-current StateFlow –
 *    pulled directly from Room so it persists across cold starts.
 */
data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val agentAction: AgentAction? = null,
    val toolExecutionSuccess: Boolean? = null,
    val toolResultMessage: String? = null,
    val toolResultData: Map<String, Any?> = emptyMap(),
    val persistedToolResultMessage: String? = null,
    val isResettingDemoData: Boolean = false
)

class HomeViewModel(
    private val orchestrator: AgentOrchestrator,
    private val conversationRepository: ConversationRepository,
    private val resetDemoDataAction: suspend () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Live feed of recent conversation rows; survives process death. */
    val recentConversations: StateFlow<List<ConversationEntity>> =
        conversationRepository.getRecent(limit = 20)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = emptyList()
            )

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
                toolResultData = emptyMap(),
                persistedToolResultMessage = null
            )
            runCatching { orchestrator.handleUserMessage(message) }
                .onSuccess { result ->
                    val persisted = result.toolResult.data["persisted"] as? Boolean == true
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agentAction = result.action,
                        toolExecutionSuccess = result.toolResult.success,
                        toolResultMessage = result.toolResult.message,
                        toolResultData = result.toolResult.data,
                        persistedToolResultMessage = result.toolResult.message
                            .takeIf { persisted && result.toolResult.success },
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
                        persistedToolResultMessage = null,
                        errorMessage = error.toReadableMessage()
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun resetDemoData() {
        if (_uiState.value.isResettingDemoData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResettingDemoData = true)
            runCatching { resetDemoDataAction() }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to reset demo data: ${error.toReadableMessage()}"
                    )
                }
            _uiState.value = _uiState.value.copy(isResettingDemoData = false)
        }
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    /**
     * Default factory: resolves the orchestrator, repositories and the
     * reset-demo action from [AasaApplication]. Tests / previews can
     * construct [HomeViewModel] directly with fakes.
     */
    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return HomeViewModel(
                orchestrator = application.agentOrchestrator,
                conversationRepository = application.conversationRepository,
                resetDemoDataAction = { application.resetDemoData() }
            ) as T
        }
    }
}
