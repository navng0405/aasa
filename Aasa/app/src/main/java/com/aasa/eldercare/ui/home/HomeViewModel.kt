package com.aasa.eldercare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.data.entity.ConversationEntity
import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.network.ApiService
import com.aasa.eldercare.network.RetrofitClient
import com.aasa.eldercare.tools.ToolActionTypes
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import com.aasa.eldercare.voice.SpeechEvent
import com.aasa.eldercare.voice.SpeechToTextManager
import com.aasa.eldercare.voice.TextToSpeechManager
import com.aasa.eldercare.voice.TtsEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Home / chat screen.
 *
 * Owns:
 *  - a [SpeechToTextManager] that converts elder speech into a
 *    transcript and feeds it into [sendMessage].
 *  - a [TextToSpeechManager] that reads the agent's response aloud
 *    after each successful turn.
 *  - the existing Phase 4 conversation flow against [orchestrator]
 *    and [conversationRepository], which is unchanged.
 *  - a periodic Gemma-server health probe that keeps the status card
 *    in sync (Phase 8).
 *
 * Lifecycle: both voice managers are released in [onCleared]. The
 * STT and TTS event flows, plus the health probe, are collected once
 * during init() with [viewModelScope].
 */
class HomeViewModel(
    private val orchestrator: AgentOrchestrator,
    private val conversationRepository: ConversationRepository,
    private val speechToTextManager: SpeechToTextManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val apiService: ApiService,
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

    init {
        observeSpeechEvents()
        observeTtsEvents()
        startHealthProbe()
    }

    // ---------------------------------------------------------------
    // Existing text flow (Phase 4) -- intentionally unchanged behavior.
    // ---------------------------------------------------------------

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendSample(message: String) {
        _uiState.value = _uiState.value.copy(inputText = message)
        sendCurrentMessage()
    }

    /**
     * Phase 8 demo affordance: fill the input *and* immediately fire
     * the agent turn so a 3-minute demo doesn't have to type and
     * tap separately. Mirrors [sendSample] but takes a typed scenario.
     */
    fun runDemoScenario(scenario: DemoScenario) {
        sendSample(scenario.message)
    }

    fun sendCurrentMessage() {
        val message = _uiState.value.inputText.trim()
        if (message.isEmpty() || _uiState.value.isLoading) return
        sendMessage(message)
    }

    private fun sendMessage(message: String) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                inputText = message,
                isLoading = true,
                errorMessage = null,
                agentAction = null,
                toolExecutionSuccess = null,
                toolResultMessage = null,
                toolResultData = emptyMap(),
                persistedToolResultMessage = null,
                pendingActionType = null,
                pendingContactName = null,
                pendingPhoneNumber = null,
                pendingAlertMessage = null,
                pendingEmergencyNumber = null
            )
            runCatching { orchestrator.handleUserMessage(message) }
                .onSuccess { result ->
                    val persisted = result.toolResult.data["persisted"] as? Boolean == true
                    val pending = parsePendingAction(result.toolResult)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agentAction = result.action,
                        toolExecutionSuccess = result.toolResult.success,
                        toolResultMessage = result.toolResult.message,
                        toolResultData = result.toolResult.data,
                        persistedToolResultMessage = result.toolResult.message
                            .takeIf { persisted && result.toolResult.success },
                        errorMessage = null,
                        pendingActionType = pending.actionType,
                        pendingContactName = pending.contactName,
                        pendingPhoneNumber = pending.phoneNumber,
                        pendingAlertMessage = pending.alertMessage,
                        pendingEmergencyNumber = pending.emergencyNumber,
                        // Successful turn = server is reachable.
                        gemmaConnection = GemmaConnectionState.CONNECTED
                    )
                    val spoken = result.action.assistantResponse
                        .ifBlank { result.toolResult.message }
                    if (spoken.isNotBlank()) {
                        textToSpeechManager.speak(spoken)
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agentAction = null,
                        toolExecutionSuccess = null,
                        toolResultMessage = null,
                        toolResultData = emptyMap(),
                        persistedToolResultMessage = null,
                        errorMessage = error.toReadableMessage(),
                        pendingActionType = null,
                        pendingContactName = null,
                        pendingPhoneNumber = null,
                        pendingAlertMessage = null,
                        pendingEmergencyNumber = null,
                        // Failed network round-trip = mark disconnected
                        // so the status card reflects reality.
                        gemmaConnection = GemmaConnectionState.DISCONNECTED
                    )
                }
        }
    }

    /**
     * Convert a [ToolResult]'s `data` map into a strongly-typed
     * [PendingAction] payload the UI can render. Tool results that
     * don't include an `actionType` recognized by the UI produce an
     * empty [PendingAction] so the action cards stay hidden.
     */
    private fun parsePendingAction(result: ToolResult): PendingAction {
        val data = result.data
        val actionType = (data[ToolResultKeys.ACTION_TYPE] as? String).orEmpty()
        if (!result.success) return PendingAction.NONE
        return when (actionType) {
            ToolActionTypes.CALL_CONTACT,
            ToolActionTypes.ALERT_TRUSTED_CONTACT,
            ToolActionTypes.HIGH_RISK_SAFETY -> PendingAction(
                actionType = actionType,
                contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)?.takeIf { it.isNotBlank() },
                phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)?.takeIf { it.isNotBlank() },
                alertMessage = (data[ToolResultKeys.ALERT_MESSAGE] as? String)?.takeIf { it.isNotBlank() },
                emergencyNumber = (data[ToolResultKeys.EMERGENCY_NUMBER] as? String)?.takeIf { it.isNotBlank() }
            )
            else -> PendingAction.NONE
        }
    }

    fun dismissPendingAction() {
        _uiState.value = _uiState.value.copy(
            pendingActionType = null,
            pendingContactName = null,
            pendingPhoneNumber = null,
            pendingAlertMessage = null,
            pendingEmergencyNumber = null
        )
    }

    private data class PendingAction(
        val actionType: String? = null,
        val contactName: String? = null,
        val phoneNumber: String? = null,
        val alertMessage: String? = null,
        val emergencyNumber: String? = null
    ) {
        companion object {
            val NONE = PendingAction()
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
                .onSuccess {
                    // Wipe the response cards so the UI reflects the
                    // freshly-seeded DB right away.
                    _uiState.value = _uiState.value.copy(
                        agentAction = null,
                        toolExecutionSuccess = null,
                        toolResultMessage = null,
                        toolResultData = emptyMap(),
                        persistedToolResultMessage = null,
                        pendingActionType = null,
                        pendingContactName = null,
                        pendingPhoneNumber = null,
                        pendingAlertMessage = null,
                        pendingEmergencyNumber = null,
                        recognizedSpeech = null,
                        errorMessage = null,
                        voiceError = null
                    )
                    publishTransientMessage("Demo data reset.")
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to reset demo data: ${error.toReadableMessage()}"
                    )
                }
            _uiState.value = _uiState.value.copy(isResettingDemoData = false)
        }
    }

    /**
     * Acknowledge the snackbar fired by the screen so it doesn't
     * re-trigger after a configuration change.
     */
    fun consumeTransientMessage() {
        _uiState.value = _uiState.value.copy(transientMessage = null)
    }

    private fun publishTransientMessage(message: String) {
        _uiState.value = _uiState.value.copy(
            transientMessage = message,
            transientMessageId = _uiState.value.transientMessageId + 1
        )
    }

    // ---------------------------------------------------------------
    // Voice flow (Phase 6).
    // ---------------------------------------------------------------

    /**
     * Sync the cached permission flag without changing recognizer
     * state. Safe to call from the screen's LaunchedEffect on every
     * recomposition.
     */
    fun setMicPermissionGranted(granted: Boolean) {
        if (_uiState.value.hasMicPermission == granted) return
        _uiState.value = _uiState.value.copy(
            hasMicPermission = granted,
            voiceError = if (!granted) {
                "Microphone permission is needed for voice input."
            } else {
                null
            }
        )
    }

    /**
     * Result callback for the Compose permission launcher. Mirrors the
     * elder's choice into UI state and, if granted in this very turn,
     * immediately starts listening so the mic tap → speak flow doesn't
     * require a second tap.
     */
    fun onMicPermissionResult(granted: Boolean) {
        setMicPermissionGranted(granted)
        if (granted) {
            startListening()
        }
    }

    fun startListening() {
        if (_uiState.value.isListening) return
        if (!_uiState.value.hasMicPermission) {
            _uiState.value = _uiState.value.copy(
                voiceError = "Microphone permission is needed for voice input."
            )
            return
        }
        // Stop any in-flight TTS so the recognizer doesn't pick up
        // the device's own voice.
        if (_uiState.value.isSpeaking) {
            textToSpeechManager.stop()
        }
        _uiState.value = _uiState.value.copy(
            voiceError = null,
            recognizedSpeech = null
        )
        speechToTextManager.startListening()
    }

    fun stopListening() {
        speechToTextManager.stopListening()
    }

    fun stopSpeaking() {
        textToSpeechManager.stop()
    }

    fun clearVoiceError() {
        _uiState.value = _uiState.value.copy(voiceError = null)
    }

    /**
     * Public hook for callers (Compose-side launchers, tests) that
     * already have a recognized transcript. Mirrors the path the
     * recognizer takes when [SpeechEvent.Recognized] arrives.
     */
    fun onSpeechRecognized(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        _uiState.value = _uiState.value.copy(
            recognizedSpeech = cleaned,
            inputText = cleaned,
            voiceError = null
        )
        sendMessage(cleaned)
    }

    private fun observeSpeechEvents() {
        viewModelScope.launch {
            speechToTextManager.isListening.collect { listening ->
                _uiState.value = _uiState.value.copy(isListening = listening)
            }
        }
        viewModelScope.launch {
            speechToTextManager.events.collect { event ->
                when (event) {
                    is SpeechEvent.Partial -> {
                        _uiState.value = _uiState.value.copy(
                            recognizedSpeech = event.text,
                            inputText = event.text
                        )
                    }
                    is SpeechEvent.Recognized -> {
                        onSpeechRecognized(event.text)
                    }
                    is SpeechEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            voiceError = event.message
                        )
                    }
                    SpeechEvent.ReadyForSpeech,
                    SpeechEvent.BeginningOfSpeech,
                    SpeechEvent.EndOfSpeech -> Unit
                }
            }
        }
    }

    private fun observeTtsEvents() {
        viewModelScope.launch {
            textToSpeechManager.isSpeaking.collect { speaking ->
                _uiState.value = _uiState.value.copy(isSpeaking = speaking)
            }
        }
        viewModelScope.launch {
            textToSpeechManager.status.collect { status ->
                _uiState.value = _uiState.value.copy(ttsStatus = status)
            }
        }
        viewModelScope.launch {
            textToSpeechManager.events.collect { event ->
                if (event is TtsEvent.Error) {
                    _uiState.value = _uiState.value.copy(voiceError = event.message)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Phase 8: local Gemma 4 health probe.
    // ---------------------------------------------------------------

    /**
     * Periodically ping the Gemma bridge `/health` endpoint so the
     * status card stays accurate even before the elder sends their
     * first message. The probe loop terminates with [viewModelScope].
     */
    private fun startHealthProbe() {
        viewModelScope.launch {
            while (isActive) {
                pingGemmaServerInternal()
                delay(HEALTH_PROBE_INTERVAL_MS)
            }
        }
    }

    /** Manually re-probe (used by the "Retry" button on the status card). */
    fun pingGemmaServer() {
        viewModelScope.launch { pingGemmaServerInternal() }
    }

    private suspend fun pingGemmaServerInternal() {
        // Don't downgrade to CONNECTING if we're already CONNECTED
        // - that would briefly flicker the badge on every probe.
        if (_uiState.value.gemmaConnection == GemmaConnectionState.UNKNOWN) {
            _uiState.value = _uiState.value.copy(
                gemmaConnection = GemmaConnectionState.CONNECTING
            )
        }
        runCatching { apiService.checkServer() }
            .onSuccess { response ->
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        gemmaConnection = GemmaConnectionState.CONNECTED,
                        gemmaModelLabel = response.body()?.ollamaModel
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        gemmaConnection = GemmaConnectionState.DISCONNECTED
                    )
                }
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(
                    gemmaConnection = GemmaConnectionState.DISCONNECTED
                )
            }
    }

    override fun onCleared() {
        speechToTextManager.destroy()
        textToSpeechManager.shutdown()
        super.onCleared()
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    /**
     * Default factory: resolves the orchestrator, repositories, voice
     * managers, and the reset-demo action from [AasaApplication].
     * Tests / previews can construct [HomeViewModel] directly with
     * fakes.
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
                speechToTextManager = SpeechToTextManager(application.applicationContext),
                textToSpeechManager = TextToSpeechManager(application.applicationContext),
                apiService = RetrofitClient.apiService,
                resetDemoDataAction = { application.resetDemoData() }
            ) as T
        }
    }

    private companion object {
        const val HEALTH_PROBE_INTERVAL_MS = 15_000L
    }
}
