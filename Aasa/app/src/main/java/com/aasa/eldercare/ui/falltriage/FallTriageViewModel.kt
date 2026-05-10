package com.aasa.eldercare.ui.falltriage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.sensors.FallDetectionEvent
import com.aasa.eldercare.sensors.FallDetectionManager
import com.aasa.eldercare.tools.FallTriageCategories
import com.aasa.eldercare.tools.ToolActionTypes
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import com.aasa.eldercare.voice.SpeechEvent
import com.aasa.eldercare.voice.SpeechToTextManager
import com.aasa.eldercare.voice.TextToSpeechManager
import com.aasa.eldercare.voice.TtsEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the Fall Detection & Triage screen.
 *
 * Owns:
 *  - a [FallDetectionManager] that runs the foreground accelerometer
 *    heuristic (or fires a synthetic event from "Simulate Fall").
 *  - a fresh [SpeechToTextManager] / [TextToSpeechManager] pair so the
 *    fall flow doesn't fight the Home screen voice managers.
 *  - the [AgentOrchestrator] so triage classification goes through the
 *    same Gemma + ScamShield + Safety pipeline used elsewhere.
 *
 * Flow on a fall event:
 *   1. Speak "I noticed a possible fall. Are you okay?" via TTS.
 *   2. Wait until TTS finishes (or short fallback delay).
 *   3. Start STT and wait up to [LISTEN_TIMEOUT_MS] for a response.
 *   4. Send a synthetic prompt the orchestrator deterministic override
 *      recognizes ("Fall detected. User response: …. Classify fall
 *      triage."). FallTriageTool produces the action payload.
 *   5. Surface the triage result on the screen for the elder to act on.
 *
 * Lifecycle: [onCleared] stops sensors + voice managers. The screen
 * never holds open mics or sensors after navigating away.
 */
class FallTriageViewModel(
    private val orchestrator: AgentOrchestrator,
    private val fallDetectionManager: FallDetectionManager,
    private val speechToTextManager: SpeechToTextManager,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FallTriageUiState(
            sensorAvailable = fallDetectionManager.isAvailable()
        )
    )
    val uiState: StateFlow<FallTriageUiState> = _uiState.asStateFlow()

    private var triageJob: Job? = null

    init {
        observeFallDetectionEvents()
        observeSpeechEvents()
        observeTtsEvents()
    }

    // ---------------------------------------------------------------
    // Public API for the screen
    // ---------------------------------------------------------------

    fun setMicPermissionGranted(granted: Boolean) {
        if (_uiState.value.hasMicPermission == granted) return
        _uiState.value = _uiState.value.copy(
            hasMicPermission = granted,
            voiceError = if (!granted) {
                "Microphone permission is needed for voice check-in."
            } else null
        )
    }

    fun onMicPermissionResult(granted: Boolean) {
        setMicPermissionGranted(granted)
    }

    fun startMonitoring() {
        if (!fallDetectionManager.isAvailable()) {
            _uiState.value = _uiState.value.copy(
                sensorAvailable = false,
                sensorErrorMessage = "This device does not have a usable accelerometer. " +
                    "Use Simulate Fall instead."
            )
            return
        }
        fallDetectionManager.start()
    }

    fun stopMonitoring() {
        fallDetectionManager.stop()
    }

    fun simulateFall() {
        fallDetectionManager.simulateFall()
    }

    /**
     * Reset the screen back to IDLE so the elder can run another demo
     * (or stop interacting). Cancels any in-flight triage job and
     * clears the result card.
     */
    fun reset() {
        triageJob?.cancel()
        triageJob = null
        speechToTextManager.cancel()
        textToSpeechManager.stop()
        fallDetectionManager.acknowledgeFall()
        _uiState.value = _uiState.value.copy(
            phase = FallTriageUiState.Phase.IDLE,
            recognizedSpeech = null,
            triageCategory = null,
            triageRiskLevel = null,
            triageReason = null,
            recommendedAction = null,
            assistantResponse = null,
            toolMessage = null,
            alertMessage = null,
            contactName = null,
            phoneNumber = null,
            voiceError = null,
            errorMessage = null
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, voiceError = null)
    }

    // ---------------------------------------------------------------
    // Internal flow
    // ---------------------------------------------------------------

    private fun observeFallDetectionEvents() {
        viewModelScope.launch {
            fallDetectionManager.state.collect { sensorState ->
                _uiState.value = _uiState.value.copy(detectionState = sensorState)
            }
        }
        viewModelScope.launch {
            fallDetectionManager.events.collect { event ->
                when (event) {
                    is FallDetectionEvent.PossibleFall -> {
                        runTriage(reason = if (event.simulated) "Simulated fall" else "Sensor heuristic")
                    }
                    is FallDetectionEvent.Unavailable -> {
                        _uiState.value = _uiState.value.copy(
                            sensorAvailable = false,
                            sensorErrorMessage = event.message
                        )
                    }
                    FallDetectionEvent.Started,
                    FallDetectionEvent.Stopped -> Unit
                }
            }
        }
    }

    private fun observeSpeechEvents() {
        viewModelScope.launch {
            speechToTextManager.isListening.collect { listening ->
                _uiState.value = _uiState.value.copy(isListening = listening)
            }
        }
    }

    private fun observeTtsEvents() {
        viewModelScope.launch {
            textToSpeechManager.isSpeaking.collect { speaking ->
                _uiState.value = _uiState.value.copy(isSpeaking = speaking)
            }
        }
    }

    private fun runTriage(reason: String) {
        if (triageJob?.isActive == true) return
        triageJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    phase = FallTriageUiState.Phase.CHECKING_IN,
                    recognizedSpeech = null,
                    triageCategory = null,
                    triageRiskLevel = null,
                    triageReason = null,
                    recommendedAction = null,
                    assistantResponse = null,
                    toolMessage = null,
                    alertMessage = null,
                    contactName = null,
                    phoneNumber = null,
                    voiceError = null,
                    errorMessage = null
                )

                speakCheckInPrompt()
                val response = listenForResponse()
                analyzeResponse(spokenResponse = response, triggerReason = reason)
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    phase = FallTriageUiState.Phase.IDLE,
                    errorMessage = "Could not run triage: ${t.message ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Speak the check-in prompt and wait for the TTS engine to report
     * Done. Falls back to a fixed delay if Done never arrives (e.g.
     * device with no TTS engine) so the demo doesn't hang.
     */
    private suspend fun speakCheckInPrompt() {
        textToSpeechManager.speak(CHECK_IN_PROMPT)
        // Wait for either Done or Error within a reasonable bound.
        withTimeoutOrNull(TTS_DONE_TIMEOUT_MS) {
            textToSpeechManager.events
                .firstOrNull { it is TtsEvent.Done || it is TtsEvent.Error }
        }
        // Brief pause so the recognizer doesn't pick up the device's
        // own voice tail.
        delay(TTS_TAIL_DELAY_MS)
    }

    /**
     * Start the recognizer and wait for the first final transcript or
     * error event. Returns null if the elder didn't speak before the
     * NO_RESPONSE timeout — that maps to FALL_TRIAGE / NO_RESPONSE.
     */
    private suspend fun listenForResponse(): String? {
        if (!_uiState.value.hasMicPermission) {
            _uiState.value = _uiState.value.copy(
                voiceError = "Microphone permission is needed. Tap Reset and try again."
            )
            return null
        }
        _uiState.value = _uiState.value.copy(
            phase = FallTriageUiState.Phase.LISTENING,
            recognizedSpeech = null
        )
        speechToTextManager.startListening()

        val event = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            speechToTextManager.events.first { ev ->
                ev is SpeechEvent.Recognized || ev is SpeechEvent.Error
            }
        }
        speechToTextManager.cancel()

        return when (event) {
            is SpeechEvent.Recognized -> {
                _uiState.value = _uiState.value.copy(recognizedSpeech = event.text)
                event.text
            }
            is SpeechEvent.Error -> {
                // Treat recognizer errors during the fall window as a
                // missing response — that's the safer demo behavior.
                _uiState.value = _uiState.value.copy(voiceError = event.message)
                null
            }
            else -> null
        }
    }

    private suspend fun analyzeResponse(spokenResponse: String?, triggerReason: String) {
        _uiState.value = _uiState.value.copy(phase = FallTriageUiState.Phase.ANALYZING)
        val prompt = buildOrchestratorPrompt(spokenResponse, triggerReason)
        val result = runCatching { orchestrator.handleUserMessage(prompt) }
        val agentResult = result.getOrElse { error ->
            _uiState.value = _uiState.value.copy(
                phase = FallTriageUiState.Phase.IDLE,
                errorMessage = "Aasa could not classify the response: " +
                    (error.message ?: "unknown error")
            )
            return
        }

        val triage = parseTriagePayload(agentResult.toolResult)
        if (triage == null) {
            _uiState.value = _uiState.value.copy(
                phase = FallTriageUiState.Phase.IDLE,
                errorMessage = "Triage tool did not return a result. Please try again."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            phase = FallTriageUiState.Phase.TRIAGE_READY,
            triageCategory = triage.category,
            triageRiskLevel = triage.riskLevel,
            triageReason = triage.reason,
            recommendedAction = triage.recommendedAction,
            assistantResponse = agentResult.action.assistantResponse
                .takeIf { it.isNotBlank() },
            toolMessage = agentResult.toolResult.message.takeIf { it.isNotBlank() },
            alertMessage = triage.alertMessage,
            emergencyNumber = triage.emergencyNumber,
            contactName = triage.contactName,
            phoneNumber = triage.phoneNumber
        )
        // Speak the gentle assistant response so the elder hears the
        // recommendation without having to look at the screen.
        val spoken = agentResult.action.assistantResponse
            .takeIf { it.isNotBlank() }
            ?: agentResult.toolResult.message
        if (spoken.isNotBlank()) {
            textToSpeechManager.speak(spoken)
        }
    }

    private fun buildOrchestratorPrompt(
        spokenResponse: String?,
        triggerReason: String
    ): String {
        val response = spokenResponse?.trim().orEmpty()
        return if (response.isBlank()) {
            "Fall detected. User response: NO_RESPONSE. Trigger: $triggerReason. Classify fall triage."
        } else {
            "Fall detected. User response: $response. Trigger: $triggerReason. Classify fall triage."
        }
    }

    private data class TriagePayload(
        val category: String,
        val riskLevel: String?,
        val reason: String?,
        val recommendedAction: String?,
        val alertMessage: String?,
        val emergencyNumber: String?,
        val contactName: String?,
        val phoneNumber: String?
    )

    private fun parseTriagePayload(result: ToolResult): TriagePayload? {
        val data = result.data
        val actionType = data[ToolResultKeys.ACTION_TYPE] as? String
        if (actionType != ToolActionTypes.FALL_TRIAGE) return null
        val category = (data[ToolResultKeys.TRIAGE_CATEGORY] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: FallTriageCategories.NO_RESPONSE
        return TriagePayload(
            category = category,
            riskLevel = (data[ToolResultKeys.EFFECTIVE_RISK_LEVEL] as? String)
                ?.takeIf { it.isNotBlank() },
            reason = (data[ToolResultKeys.TRIAGE_REASON] as? String)
                ?.takeIf { it.isNotBlank() },
            recommendedAction = (data[ToolResultKeys.RECOMMENDED_ACTION] as? String)
                ?.takeIf { it.isNotBlank() },
            alertMessage = (data[ToolResultKeys.ALERT_MESSAGE] as? String)
                ?.takeIf { it.isNotBlank() },
            emergencyNumber = (data[ToolResultKeys.EMERGENCY_NUMBER] as? String)
                ?.takeIf { it.isNotBlank() },
            contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)
                ?.takeIf { it.isNotBlank() },
            phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)
                ?.takeIf { it.isNotBlank() }
        )
    }

    override fun onCleared() {
        triageJob?.cancel()
        speechToTextManager.destroy()
        textToSpeechManager.shutdown()
        fallDetectionManager.stop()
        super.onCleared()
    }

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FallTriageViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            val ctx = application.applicationContext
            return FallTriageViewModel(
                orchestrator = application.agentOrchestrator,
                fallDetectionManager = FallDetectionManager(ctx),
                speechToTextManager = SpeechToTextManager(ctx),
                textToSpeechManager = TextToSpeechManager(ctx)
            ) as T
        }
    }

    private companion object {
        const val CHECK_IN_PROMPT = "I noticed a possible fall. Are you okay?"
        const val LISTEN_TIMEOUT_MS = 12_000L
        const val TTS_DONE_TIMEOUT_MS = 6_000L
        const val TTS_TAIL_DELAY_MS = 250L
    }
}
