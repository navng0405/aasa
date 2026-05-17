package com.aasa.eldercare.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.BuildConfig
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.agent.GreetingBuilder
import com.aasa.eldercare.data.entity.ConversationEntity
import com.aasa.eldercare.data.preferences.UserPreferences
import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.data.repository.MedicationRepository
import com.aasa.eldercare.data.repository.PresencePingRepository
import com.aasa.eldercare.data.repository.TrustedContactRepository
import com.aasa.eldercare.document.DocumentReaderAnalyzer
import com.aasa.eldercare.medicine.MedicineLensAnalyzer
import com.aasa.eldercare.model.GemmaRouter
import com.aasa.eldercare.tools.ToolActionTypes
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import com.aasa.eldercare.tools.NeighborCheckTool
import com.aasa.eldercare.tools.WellnessCheckTool
import com.aasa.eldercare.voice.HotwordService
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
 *  - a periodic on-device Gemma health probe that keeps the status card
 *    in sync (Phase 8).
 *
 * Lifecycle: both voice managers are released in [onCleared]. The
 * STT and TTS event flows, plus the health probe, are collected once
 * during init() with [viewModelScope].
 */
class HomeViewModel(
    private val orchestrator: AgentOrchestrator,
    private val conversationRepository: ConversationRepository,
    private val medicationRepository: MedicationRepository,
    private val presencePingRepository: PresencePingRepository,
    private val trustedContactRepository: TrustedContactRepository,
    private val speechToTextManager: SpeechToTextManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val gemmaRouter: GemmaRouter,
    private val userPreferences: UserPreferences,
    private val appContext: Context,
    private val resetDemoDataAction: suspend () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            userName = userPreferences.userName,
            hotwordSupported = BuildConfig.AASA_ENABLE_HOTWORD,
            hotwordEnabled = BuildConfig.AASA_ENABLE_HOTWORD && userPreferences.hotwordEnabled
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Set once the greeting TTS has finished, so the speech-event
     * collector knows the next [SpeechEvent.Recognized] is the
     * elder's reply to the greeting (no special handling needed,
     * but we use this to gate the auto-listen-after-greeting flow
     * so it only fires once per launch).
     */
    private var greetingSpoken: Boolean = false
    private var autoListenAfterGreeting: Boolean = false
    private val medicineLensAnalyzer: MedicineLensAnalyzer by lazy {
        MedicineLensAnalyzer(appContext, medicationRepository)
    }
    private val documentReaderAnalyzer: DocumentReaderAnalyzer by lazy {
        DocumentReaderAnalyzer(appContext)
    }

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
        autoStartHotwordIfOptedIn()
        refreshHeartbeatStatus()
        startWellnessPolling()
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
        userPreferences.lastForegroundActivityAtMs = System.currentTimeMillis()
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
                pendingEmergencyNumber = null,
                pendingScamRisk = null,
                pendingScamSignals = emptyList(),
                pendingSafeAction = null,
                pendingScamMessageText = null,
                pendingHeartbeatState = null,
                pendingSilenceHours = null,
                pendingPairedContactName = null
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
                        pendingScamRisk = pending.scamRisk,
                        pendingScamSignals = pending.scamSignals,
                        pendingSafeAction = pending.safeAction,
                        pendingScamMessageText = pending.scamMessageText,
                        pendingHeartbeatState = pending.heartbeatState,
                        pendingSilenceHours = pending.silenceHours,
                        pendingPairedContactName = pending.pairedContactName,
                        gemmaConnection = GemmaConnectionState.CONNECTED,
                        selectedGemmaMode = if (gemmaRouter.usingBridge) {
                            GemmaRuntimeMode.MAC_BRIDGE
                        } else {
                            GemmaRuntimeMode.ON_DEVICE
                        },
                        gemmaModelLabel = gemmaRouter.selectedRunnerLabel,
                        gemmaStatusDetail = null
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
                        pendingScamRisk = null,
                        pendingScamSignals = emptyList(),
                        pendingSafeAction = null,
                        pendingScamMessageText = null,
                        pendingHeartbeatState = null,
                        pendingSilenceHours = null,
                        pendingPairedContactName = null,
                        gemmaConnection = GemmaConnectionState.DISCONNECTED,
                        gemmaModelLabel = gemmaRouter.selectedRunnerLabel,
                        gemmaStatusDetail = error.toReadableMessage()
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
            ToolActionTypes.SCAM_ANALYSIS -> PendingAction(
                actionType = actionType,
                contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)?.takeIf { it.isNotBlank() },
                phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)?.takeIf { it.isNotBlank() },
                scamRisk = (data[ToolResultKeys.SCAM_RISK] as? String)?.takeIf { it.isNotBlank() },
                scamSignals = readSignalList(data[ToolResultKeys.SCAM_SIGNALS]),
                safeAction = (data[ToolResultKeys.SAFE_ACTION] as? String)?.takeIf { it.isNotBlank() },
                scamMessageText = (data[ToolResultKeys.MESSAGE_TEXT] as? String)?.takeIf { it.isNotBlank() }
            )
            ToolActionTypes.WELLNESS_CHECK -> PendingAction(
                actionType = actionType,
                contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)?.takeIf { it.isNotBlank() },
                phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)?.takeIf { it.isNotBlank() },
                alertMessage = (data[ToolResultKeys.ALERT_MESSAGE] as? String)?.takeIf { it.isNotBlank() },
                scamMessageText = (data[ToolResultKeys.MESSAGE_TEXT] as? String)?.takeIf { it.isNotBlank() },
                heartbeatState = (data[ToolResultKeys.HEARTBEAT_STATE] as? String)?.takeIf { it.isNotBlank() },
                silenceHours = (data[ToolResultKeys.SILENCE_HOURS] as? Number)?.toInt()
            )
            ToolActionTypes.NEIGHBOR_CHECK -> PendingAction(
                actionType = actionType,
                contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)?.takeIf { it.isNotBlank() },
                pairedContactName = (data[ToolResultKeys.PAIRED_CONTACT_NAME] as? String)
                    ?.takeIf { it.isNotBlank() },
                phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)?.takeIf { it.isNotBlank() },
                alertMessage = (data[ToolResultKeys.ALERT_MESSAGE] as? String)?.takeIf { it.isNotBlank() },
                scamMessageText = (data[ToolResultKeys.MESSAGE_TEXT] as? String)?.takeIf { it.isNotBlank() },
                silenceHours = (data[ToolResultKeys.SILENCE_HOURS] as? Number)?.toInt()
            )
            else -> PendingAction.NONE
        }
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

    fun dismissPendingAction() {
        _uiState.value = _uiState.value.copy(
            pendingActionType = null,
            pendingContactName = null,
            pendingPhoneNumber = null,
            pendingAlertMessage = null,
            pendingEmergencyNumber = null,
            pendingScamRisk = null,
            pendingScamSignals = emptyList(),
            pendingSafeAction = null,
            pendingScamMessageText = null,
            pendingHeartbeatState = null,
            pendingSilenceHours = null,
            pendingPairedContactName = null
        )
    }

    private data class PendingAction(
        val actionType: String? = null,
        val contactName: String? = null,
        val phoneNumber: String? = null,
        val alertMessage: String? = null,
        val emergencyNumber: String? = null,
        val scamRisk: String? = null,
        val scamSignals: List<String> = emptyList(),
        val safeAction: String? = null,
        val scamMessageText: String? = null,
        val heartbeatState: String? = null,
        val silenceHours: Int? = null,
        val pairedContactName: String? = null
    ) {
        companion object {
            val NONE = PendingAction()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun logDailyHeartbeat() {
        if (_uiState.value.isLoggingHeartbeat) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(isLoggingHeartbeat = true)
            runCatching {
                presencePingRepository.logPing(now)
                userPreferences.lastForegroundActivityAtMs = now
                userPreferences.lastWellnessPromptDayStartMs = presencePingRepository.dayStart(now)
                true
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isLoggingHeartbeat = false,
                    heartbeatLoggedToday = true
                )
                publishTransientMessage("Thanks. Your daily check-in is saved.")
                dismissPendingAction()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoggingHeartbeat = false,
                    errorMessage = "Could not save today's check-in: ${error.toReadableMessage()}"
                )
            }
        }
    }

    fun onForegroundActivity() {
        userPreferences.lastForegroundActivityAtMs = System.currentTimeMillis()
        refreshHeartbeatStatus()
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
                        pendingScamRisk = null,
                        pendingScamSignals = emptyList(),
                        pendingSafeAction = null,
                        pendingScamMessageText = null,
                        pendingHeartbeatState = null,
                        pendingSilenceHours = null,
                        pendingPairedContactName = null,
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
        // The Application process lifecycle starts the hotword service
        // only after the app backgrounds. Keeping it out of foreground
        // avoids fighting the in-app greeting and tap-to-talk recognizer.
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

    fun setLensMode(mode: LensMode) {
        if (_uiState.value.selectedLensMode == mode) return
        _uiState.value = _uiState.value.copy(
            selectedLensMode = mode,
            medicineLensResult = null,
            medicineLensError = null
        )
    }

    fun analyzeMedicinePhoto(uri: Uri) {
        if (_uiState.value.isMedicineLensAnalyzing) return
        val mode = _uiState.value.selectedLensMode
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isMedicineLensAnalyzing = true,
                medicineLensError = null,
                medicineLensResult = null
            )
            when (mode) {
                LensMode.MEDICINE -> {
                    runCatching { medicineLensAnalyzer.analyze(uri) }
                        .onSuccess { result ->
                            _uiState.value = _uiState.value.copy(
                                isMedicineLensAnalyzing = false,
                                medicineLensResult = LensCardData.Medicine(result),
                                medicineLensError = null
                            )
                            if (result.summary.isNotBlank()) {
                                textToSpeechManager.speak(result.summary)
                            }
                        }
                        .onFailure { error ->
                            _uiState.value = _uiState.value.copy(
                                isMedicineLensAnalyzing = false,
                                medicineLensResult = null,
                                medicineLensError = "I could not read that medicine photo clearly: " +
                                    error.toReadableMessage()
                            )
                        }
                }
                LensMode.DOCUMENT -> {
                    runCatching { documentReaderAnalyzer.analyze(uri) }
                        .onSuccess { result ->
                            val cardData = DocumentReadingCardData(
                                summary = (result.data[ToolResultKeys.DOCUMENT_SUMMARY] as? String)
                                    ?: "I couldn't summarize this page.",
                                risk = (result.data[ToolResultKeys.DOCUMENT_RISK] as? String) ?: "LOW"
                            )
                            _uiState.value = _uiState.value.copy(
                                isMedicineLensAnalyzing = false,
                                medicineLensResult = LensCardData.Document(cardData),
                                medicineLensError = null
                            )
                            if (result.message.isNotBlank()) {
                                textToSpeechManager.speak(result.message)
                            }
                        }
                        .onFailure { error ->
                            _uiState.value = _uiState.value.copy(
                                isMedicineLensAnalyzing = false,
                                medicineLensResult = null,
                                medicineLensError = "I could not read that document clearly: " +
                                    error.toReadableMessage()
                            )
                        }
                    }
                }
            }
        }
    }

    fun onMedicinePhotoNotCaptured() {
        val modeCopy = if (_uiState.value.selectedLensMode == LensMode.MEDICINE) {
            "medicine label"
        } else {
            "document"
        }
        _uiState.value = _uiState.value.copy(
            medicineLensError = "No photo was captured. Please try again with the $modeCopy flat and in good light."
        )
    }

    fun clearMedicineLens() {
        _uiState.value = _uiState.value.copy(
            medicineLensResult = null,
            medicineLensError = null
        )
    }

    /**
     * Public hook for callers (Compose-side launchers, tests) that
     * already have a recognized transcript. Mirrors the path the
     * recognizer takes when [SpeechEvent.Recognized] arrives.
     */
    fun onSpeechRecognized(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        userPreferences.lastSpokenInteractionAtMs = System.currentTimeMillis()
        if (isWakePhrase(cleaned)) {
            _uiState.value = _uiState.value.copy(
                recognizedSpeech = cleaned,
                inputText = "",
                voiceError = null,
                agentAction = null,
                toolExecutionSuccess = null,
                toolResultMessage = null,
                toolResultData = emptyMap(),
                persistedToolResultMessage = null
            )
            maybeGreetUser(force = true, fromWakeWord = true)
            return
        }
        _uiState.value = _uiState.value.copy(
            recognizedSpeech = cleaned,
            inputText = cleaned,
            voiceError = null
        )
        sendMessage(cleaned)
    }

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return normalized in WAKE_PHRASES
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
                when (event) {
                    is TtsEvent.Error -> {
                        _uiState.value = _uiState.value.copy(voiceError = event.message)
                    }
                    TtsEvent.Done -> {
                        // Phase 11: after the launch greeting finishes,
                        // open the mic automatically so the elder can
                        // reply hands-free without tapping anything.
                        if (autoListenAfterGreeting) {
                            autoListenAfterGreeting = false
                            if (_uiState.value.hasMicPermission &&
                                !_uiState.value.isListening &&
                                !_uiState.value.isLoading
                            ) {
                                startListening()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Phase 8: on-device Gemma 4 health probe.
    // ---------------------------------------------------------------

    /**
     * Periodically check the LiteRT-LM model and engine so the
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

    fun selectGemmaMode(mode: GemmaRuntimeMode) {
        if (_uiState.value.selectedGemmaMode == mode) return
        gemmaRouter.forceBridge = mode == GemmaRuntimeMode.MAC_BRIDGE
        _uiState.value = _uiState.value.copy(
            selectedGemmaMode = mode,
            gemmaConnection = GemmaConnectionState.CONNECTING,
            gemmaModelLabel = gemmaRouter.selectedRunnerLabel,
            gemmaStatusDetail = null
        )
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
        runCatching { gemmaRouter.isSelectedRunnerAvailable() }
            .onSuccess { available ->
                val mode = if (gemmaRouter.usingBridge) {
                    GemmaRuntimeMode.MAC_BRIDGE
                } else {
                    GemmaRuntimeMode.ON_DEVICE
                }
                _uiState.value = _uiState.value.copy(
                    selectedGemmaMode = mode,
                    gemmaConnection = if (available) {
                        GemmaConnectionState.CONNECTED
                    } else {
                        GemmaConnectionState.DISCONNECTED
                    },
                    gemmaModelLabel = gemmaRouter.selectedRunnerLabel,
                    gemmaStatusDetail = if (available) {
                        null
                    } else {
                        gemmaRouter.selectedStatusReason
                    }
                )
            }
            .onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    gemmaConnection = GemmaConnectionState.DISCONNECTED,
                    gemmaModelLabel = gemmaRouter.selectedRunnerLabel,
                    gemmaStatusDetail = throwable.toReadableMessage()
                )
            }
    }

    private fun refreshHeartbeatStatus() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dayStart = presencePingRepository.dayStart(now)
            val loggedToday = presencePingRepository.hasPingInWindow(dayStart, now + 1)
            _uiState.value = _uiState.value.copy(heartbeatLoggedToday = loggedToday)
        }
    }

    private fun startWellnessPolling() {
        viewModelScope.launch {
            while (isActive) {
                evaluateWellnessSilence()
                delay(WELLNESS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun evaluateWellnessSilence() {
        val now = System.currentTimeMillis()
        val dayStart = presencePingRepository.dayStart(now)
        val morningWindow = presencePingRepository.learnedMorningWindow(now)
        val windowStart = morningWindow.startMsForDay(dayStart)
        val windowEnd = morningWindow.endMsForDay(dayStart)
        val hasPing = presencePingRepository.hasPingInWindow(dayStart, now + 1)
        _uiState.value = _uiState.value.copy(heartbeatLoggedToday = hasPing)
        if (hasPing || now < windowEnd) return

        val hadForeground = userPreferences.lastForegroundActivityAtMs in windowStart until windowEnd
        val hadSpoken = userPreferences.lastSpokenInteractionAtMs in windowStart until windowEnd
        val hadMessage = conversationRepository.hasUserMessageInWindow(windowStart, windowEnd)
        if (hadForeground || hadSpoken || hadMessage) return

        val silenceHours = ((now - windowEnd) / (60L * 60L * 1000L)).toInt().coerceAtLeast(1)
        if (userPreferences.lastWellnessPromptDayStartMs != dayStart) {
            triggerWellnessCheck(
                state = WellnessCheckTool.STATE_ELDER_PROMPT,
                silenceHours = silenceHours,
                morningWindow = morningWindow
            )
            userPreferences.lastWellnessPromptDayStartMs = dayStart
            return
        }

        val shouldEscalate = silenceHours >= WELLNESS_ESCALATION_HOURS &&
            (now - userPreferences.lastWellnessEscalationAtMs) >= WELLNESS_ESCALATION_COOLDOWN_MS
        if (shouldEscalate) {
            triggerEscalationCheck(
                silenceHours = silenceHours,
                morningWindow = morningWindow
            )
            userPreferences.lastWellnessEscalationAtMs = now
        }
    }

    private suspend fun triggerEscalationCheck(
        silenceHours: Int,
        morningWindow: PresencePingRepository.MorningWindow
    ) {
        val hasNeighborHelper = trustedContactRepository.findPrimaryContact()
            ?.let { trustedContactRepository.findPairedCareProviderFor(it.id) != null }
            ?: false
        if (hasNeighborHelper) {
            triggerNeighborCheck(
                silenceHours = silenceHours,
                morningWindow = morningWindow
            )
        } else {
            triggerWellnessCheck(
                state = WellnessCheckTool.STATE_ESCALATED,
                silenceHours = silenceHours,
                morningWindow = morningWindow
            )
        }
    }

    private suspend fun triggerWellnessCheck(
        state: String,
        silenceHours: Int,
        morningWindow: PresencePingRepository.MorningWindow
    ) {
        val syntheticMessage = if (state == WellnessCheckTool.STATE_ESCALATED) {
            "Daily heartbeat timeout: wellness check escalation."
        } else {
            "Daily heartbeat timeout: wellness check timeout."
        }
        val result = orchestrator.handleUserMessage(syntheticMessage)
        val smsBody = if (state == WellnessCheckTool.STATE_ESCALATED) {
            "Aasa wellness check: no heartbeat for $silenceHours hours. Please check in soon. $WELLNESS_DEEP_LINK"
        } else {
            "Hi Priya, it's me. I'm okay today. - Sent from Aasa"
        }
        val patchedResult = result.toolResult.copy(
            data = result.toolResult.data + mapOf(
                WellnessCheckTool.ARG_STATE to state,
                WellnessCheckTool.ARG_SILENCE_HOURS to silenceHours,
                WellnessCheckTool.ARG_WINDOW_START_HOUR to morningWindow.startHour24,
                WellnessCheckTool.ARG_WINDOW_END_HOUR to morningWindow.endHour24,
                WellnessCheckTool.ARG_DEEP_LINK to WELLNESS_DEEP_LINK,
                ToolResultKeys.MESSAGE_TEXT to smsBody
            )
        )
        val pending = parsePendingAction(patchedResult)
        _uiState.value = _uiState.value.copy(
            pendingActionType = pending.actionType,
            pendingContactName = pending.contactName,
            pendingPhoneNumber = pending.phoneNumber,
            pendingAlertMessage = pending.alertMessage,
            pendingScamMessageText = pending.scamMessageText,
            pendingHeartbeatState = pending.heartbeatState,
            pendingSilenceHours = pending.silenceHours,
            pendingPairedContactName = pending.pairedContactName
        )
    }

    private suspend fun triggerNeighborCheck(
        silenceHours: Int,
        morningWindow: PresencePingRepository.MorningWindow
    ) {
        val syntheticMessage = "Daily heartbeat timeout: neighbor check escalation."
        val result = orchestrator.handleUserMessage(syntheticMessage)
        val helperName = (result.toolResult.data[ToolResultKeys.CONTACT_NAME] as? String).orEmpty()
        val recipientName = (result.toolResult.data[ToolResultKeys.PAIRED_CONTACT_NAME] as? String).orEmpty()
        val neighborSms = buildString {
            append("Hi ")
            append(if (helperName.isBlank()) "neighbor" else helperName)
            append(", Aasa noticed ")
            append(if (recipientName.isBlank()) "your paired elder" else recipientName)
            append(" has been quiet for ")
            append(silenceHours.coerceAtLeast(1))
            append(" hours. If it feels safe, please do a quick doorstep check. ")
            append(WELLNESS_DEEP_LINK)
        }
        val patchedResult = result.toolResult.copy(
            data = result.toolResult.data + mapOf(
                NeighborCheckTool.ARG_SILENCE_HOURS to silenceHours,
                WellnessCheckTool.ARG_WINDOW_START_HOUR to morningWindow.startHour24,
                WellnessCheckTool.ARG_WINDOW_END_HOUR to morningWindow.endHour24,
                NeighborCheckTool.ARG_DEEP_LINK to WELLNESS_DEEP_LINK,
                ToolResultKeys.MESSAGE_TEXT to neighborSms
            )
        )
        val pending = parsePendingAction(patchedResult)
        _uiState.value = _uiState.value.copy(
            pendingActionType = pending.actionType,
            pendingContactName = pending.contactName,
            pendingPhoneNumber = pending.phoneNumber,
            pendingAlertMessage = pending.alertMessage,
            pendingScamMessageText = pending.scamMessageText,
            pendingSilenceHours = pending.silenceHours,
            pendingPairedContactName = pending.pairedContactName
        )
    }

    override fun onCleared() {
        speechToTextManager.destroy()
        textToSpeechManager.shutdown()
        super.onCleared()
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    // ---------------------------------------------------------------
    // Phase 11: launch greeting + name personalization.
    // ---------------------------------------------------------------

    /**
     * Speak a warm, time-of-day-aware greeting the first time the
     * Home screen is composed in a session (or after a cool-down).
     * Called from [com.aasa.eldercare.ui.home.HomeScreen]'s
     * `LaunchedEffect(Unit)` once mic permission has been resolved.
     *
     * If the user already has mic permission, we also flip the
     * "auto-listen after greeting" flag so the elder can answer the
     * "How are you feeling?" question hands-free.
     */
    fun maybeGreetUser(force: Boolean = false, fromWakeWord: Boolean = false) {
        if (greetingSpoken && !force) return
        val now = System.currentTimeMillis()
        if (!force && !userPreferences.shouldGreet(now)) {
            // Within the cool-down window — silently skip but mark
            // the in-process flag so we don't re-check every recomp.
            greetingSpoken = true
            return
        }
        val name = userPreferences.userName
        val greeting = if (fromWakeWord) {
            GreetingBuilder.buildWakeGreeting(userName = name)
        } else {
            GreetingBuilder.build(userName = name)
        }
        greetingSpoken = true
        autoListenAfterGreeting = _uiState.value.hasMicPermission
        userPreferences.markGreeted(now)
        textToSpeechManager.speak(greeting)
    }

    /**
     * Persist a new display name for the elder. Empty / blank values
     * fall back to the default ("friend"). The greeting picks up the
     * change on the next launch (or via [maybeGreetUser(force = true)]).
     */
    fun updateUserName(newName: String) {
        val cleaned = newName.trim()
        userPreferences.userName = cleaned.ifBlank { UserPreferences.DEFAULT_NAME }
        _uiState.value = _uiState.value.copy(userName = userPreferences.userName)
    }

    // ---------------------------------------------------------------
    // Phase 12: "Hey Aasa" wake-word opt-in.
    // ---------------------------------------------------------------

    /**
     * Flip the always-on wake-word foreground service on or off.
     *
     * Pre-condition for `enabled = true`:
     *  - the build was made with `-PaasaEnableHotword=true`
     *  - mic permission is already granted (the screen requests it
     *    via the standard permission launcher before calling this)
     *
     * When enabled we persist the choice so the service restarts on
     * the next app launch. When disabled we both stop the service
     * and clear the persisted flag.
     */
    fun setHotwordEnabled(enabled: Boolean) {
        if (!BuildConfig.AASA_ENABLE_HOTWORD) {
            _uiState.value = _uiState.value.copy(
                hotwordSupported = false,
                hotwordEnabled = false,
                hotwordError = "Wake word is not enabled in this build."
            )
            return
        }
        if (enabled) {
            if (!_uiState.value.hasMicPermission) {
                _uiState.value = _uiState.value.copy(
                    hotwordError = "Microphone permission is needed for \"Hey Aasa\"."
                )
                return
            }
            userPreferences.hotwordEnabled = true
            _uiState.value = _uiState.value.copy(
                hotwordEnabled = true,
                hotwordError = null
            )
        } else {
            userPreferences.hotwordEnabled = false
            runCatching { HotwordService.stop(appContext) }
            _uiState.value = _uiState.value.copy(
                hotwordEnabled = false,
                hotwordError = null
            )
        }
    }

    fun clearHotwordError() {
        _uiState.value = _uiState.value.copy(hotwordError = null)
    }

    /**
     * If the build supports hotword AND the elder previously opted in
     * AND mic permission is granted, re-start the service when the
     * Home VM is created (covers reboot, process death, app launch).
     */
    private fun autoStartHotwordIfOptedIn() {
        if (!BuildConfig.AASA_ENABLE_HOTWORD) return
        if (!userPreferences.hotwordEnabled) return
        // Mic permission isn't known yet at construction time —
        // [setMicPermissionGranted] re-checks below.
    }

    /**
     * Hook called from the Home screen after the mic-permission probe
     * runs. If the elder is opted in and the permission is granted,
     * (re-)start the service idempotently.
     */
    private fun maybeStartHotwordService() {
        if (!BuildConfig.AASA_ENABLE_HOTWORD) return
        if (!userPreferences.hotwordEnabled) return
        if (!_uiState.value.hasMicPermission) return
        runCatching { HotwordService.start(appContext) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    hotwordEnabled = true,
                    hotwordError = null
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    hotwordError = "Could not start wake word: " +
                        error.toReadableMessage()
                )
            }
    }

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
                medicationRepository = application.medicationRepository,
                presencePingRepository = application.presencePingRepository,
                trustedContactRepository = application.trustedContactRepository,
                speechToTextManager = SpeechToTextManager(application.applicationContext),
                textToSpeechManager = TextToSpeechManager(application.applicationContext),
                gemmaRouter = application.gemmaRouter,
                userPreferences = application.userPreferences,
                appContext = application.applicationContext,
                resetDemoDataAction = { application.resetDemoData() }
            ) as T
        }
    }

    private companion object {
        const val HEALTH_PROBE_INTERVAL_MS = 15_000L
        const val WELLNESS_POLL_INTERVAL_MS = 30_000L
        const val WELLNESS_ESCALATION_HOURS = 14
        const val WELLNESS_ESCALATION_COOLDOWN_MS = 6L * 60L * 60L * 1000L
        const val WELLNESS_DEEP_LINK = "aasa://wellness-check?source=heartbeat"
        val WAKE_PHRASES = setOf(
            "hey aasa",
            "hey asa",
            "ok aasa",
            "okay aasa"
        )
    }
}
