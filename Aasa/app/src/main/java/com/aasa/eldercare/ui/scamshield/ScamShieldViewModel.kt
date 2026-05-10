package com.aasa.eldercare.ui.scamshield

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.tools.ToolActionTypes
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Scam & Fraud Shield screen.
 *
 * Reuses [AgentOrchestrator] so a turn here goes through the exact
 * same path as a Home turn: deterministic safety + scam overrides,
 * Gemma classification, [com.aasa.eldercare.tools.ScamShieldTool], and
 * conversation persistence. The only difference is how the result is
 * surfaced — this screen renders the scam fields as a structured card.
 *
 * Failures (Gemma offline, tool error) populate [ScamShieldUiState.errorMessage]
 * so the screen can show a gentle "could not check" message instead of
 * crashing. The shield is informational only and never auto-acts.
 */
class ScamShieldViewModel(
    private val orchestrator: AgentOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScamShieldUiState())
    val uiState: StateFlow<ScamShieldUiState> = _uiState.asStateFlow()

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun useDemo(message: String) {
        _uiState.value = _uiState.value.copy(inputText = message)
    }

    fun clear() {
        _uiState.value = ScamShieldUiState()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Run a scam analysis turn. The user's pasted text is wrapped with
     * an "Analyze this suspicious message:" prefix so the orchestrator
     * deterministic override picks ScamShieldTool reliably even if
     * Gemma decides otherwise.
     */
    fun analyzeMessage() {
        val message = _uiState.value.inputText.trim()
        if (message.isEmpty() || _uiState.value.isAnalyzing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isAnalyzing = true,
                errorMessage = null,
                scamRisk = null,
                scamSignals = emptyList(),
                safeAction = null,
                assistantExplanation = null,
                analyzedMessageText = message
            )

            val prompt = "Analyze this suspicious message: $message"
            runCatching { orchestrator.handleUserMessage(prompt) }
                .onSuccess { result ->
                    val parsed = parseScamResult(result.toolResult)
                    val explanation = result.action.assistantResponse
                        .takeIf { it.isNotBlank() }
                        ?: result.toolResult.message

                    _uiState.value = if (parsed != null) {
                        _uiState.value.copy(
                            isAnalyzing = false,
                            errorMessage = null,
                            analyzedMessageText = parsed.messageText ?: message,
                            scamRisk = parsed.risk,
                            scamSignals = parsed.signals,
                            safeAction = parsed.safeAction,
                            assistantExplanation = explanation,
                            contactName = parsed.contactName,
                            phoneNumber = parsed.phoneNumber
                        )
                    } else {
                        _uiState.value.copy(
                            isAnalyzing = false,
                            errorMessage = "Aasa could not analyze this message right now. Please try again.",
                            analyzedMessageText = message,
                            assistantExplanation = explanation
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        errorMessage = error.toReadableMessage(),
                        scamRisk = null,
                        scamSignals = emptyList(),
                        safeAction = null,
                        assistantExplanation = null
                    )
                }
        }
    }

    private data class ParsedScamResult(
        val risk: String,
        val signals: List<String>,
        val safeAction: String?,
        val messageText: String?,
        val contactName: String?,
        val phoneNumber: String?
    )

    /**
     * Pluck the scam-specific fields out of the [ToolResult.data] map.
     * Returns null when the tool result doesn't actually carry a
     * SCAM_ANALYSIS payload, so the caller can show a graceful error.
     */
    private fun parseScamResult(result: ToolResult): ParsedScamResult? {
        val data = result.data
        val actionType = data[ToolResultKeys.ACTION_TYPE] as? String
        if (actionType != ToolActionTypes.SCAM_ANALYSIS) return null
        val risk = (data[ToolResultKeys.SCAM_RISK] as? String)?.takeIf { it.isNotBlank() }
            ?: return null
        return ParsedScamResult(
            risk = risk,
            signals = readSignalList(data[ToolResultKeys.SCAM_SIGNALS]),
            safeAction = (data[ToolResultKeys.SAFE_ACTION] as? String)?.takeIf { it.isNotBlank() },
            messageText = (data[ToolResultKeys.MESSAGE_TEXT] as? String)?.takeIf { it.isNotBlank() },
            contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)?.takeIf { it.isNotBlank() },
            phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSignalList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.filterIsInstance<String>()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        is String -> raw.split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ScamShieldViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return ScamShieldViewModel(
                orchestrator = application.agentOrchestrator
            ) as T
        }
    }
}
