package com.aasa.eldercare.ui.mobility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.sensors.MobilityCheckEvent
import com.aasa.eldercare.sensors.MobilityFeatureExtractor
import com.aasa.eldercare.sensors.MobilityFeatures
import com.aasa.eldercare.sensors.MobilitySensorManager
import com.aasa.eldercare.tools.ToolActionTypes
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import com.aasa.eldercare.voice.TextToSpeechManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 8.7: drives the Mobility Shield screen.
 *
 * Owns:
 *  - a [MobilitySensorManager] that records 10 seconds of accelerometer
 *    (+ gyroscope) data, foreground only, then auto-stops.
 *  - the existing [AgentOrchestrator] so both real and simulated walks
 *    flow through the real Gemma 4 server + MobilityShieldTool path.
 *  - a fresh [TextToSpeechManager] so the screen can speak the
 *    "please walk slowly for 10 seconds" prompt and the gentle
 *    assistant response without fighting the Home screen TTS.
 *
 * IMPORTANT — this is a hackathon-grade mobility check. It is NOT
 * medical-grade and does NOT diagnose Parkinson's, dementia, stroke,
 * or any neurological disease. The ViewModel never auto-acts and
 * never alerts a physician.
 */
class MobilityShieldViewModel(
    private val orchestrator: AgentOrchestrator,
    private val mobilitySensorManager: MobilitySensorManager,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MobilityShieldUiState(
            sensorAvailable = mobilitySensorManager.isAvailable(),
            hasGyroscope = mobilitySensorManager.hasGyroscope()
        )
    )
    val uiState: StateFlow<MobilityShieldUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null

    init {
        observeSensorEvents()
    }

    // ---------------------------------------------------------------
    // Public API for the screen
    // ---------------------------------------------------------------

    /**
     * Begin a real 10-second walk check. Speaks the prompt first, then
     * starts the recorder. The recorder auto-stops after 10 seconds
     * and emits a [MobilityCheckEvent.Completed] which we route to
     * Gemma + MobilityShieldTool.
     */
    fun startCheck() {
        if (_uiState.value.isBusy) return
        if (!mobilitySensorManager.isAvailable()) {
            _uiState.value = _uiState.value.copy(
                sensorAvailable = false,
                sensorErrorMessage = "This device does not have an accelerometer. " +
                    "Use Simulate Stable Walk or Simulate Unsteady Walk for the demo."
            )
            return
        }

        clearResultFields()
        _uiState.value = _uiState.value.copy(
            phase = MobilityShieldUiState.Phase.RECORDING,
            secondsRemaining = MobilityShieldUiState.TOTAL_RECORDING_SECONDS,
            sensorErrorMessage = null,
            errorMessage = null
        )
        textToSpeechManager.speak(START_PROMPT)
        mobilitySensorManager.start()
    }

    /**
     * Cancel an in-flight recording without analyzing. Mirrors what
     * [reset] does but kept as a separate verb in case the screen
     * adds an explicit "Stop check" affordance.
     */
    fun cancelRecording() {
        mobilitySensorManager.stop()
        analysisJob?.cancel()
        analysisJob = null
        _uiState.value = _uiState.value.copy(
            phase = MobilityShieldUiState.Phase.IDLE,
            secondsRemaining = MobilityShieldUiState.TOTAL_RECORDING_SECONDS
        )
    }

    /**
     * Reset to IDLE so the elder can run another check (or stop
     * interacting). Cancels any in-flight job, clears the result card,
     * and stops any ongoing TTS playback.
     */
    fun reset() {
        analysisJob?.cancel()
        analysisJob = null
        mobilitySensorManager.stop()
        textToSpeechManager.stop()
        _uiState.value = _uiState.value.copy(
            phase = MobilityShieldUiState.Phase.IDLE,
            secondsRemaining = MobilityShieldUiState.TOTAL_RECORDING_SECONDS,
            features = null,
            confidenceScore = null,
            stabilityLabel = null,
            riskLevel = null,
            assistantResponse = null,
            toolMessage = null,
            recommendedAction = null,
            contactName = null,
            phoneNumber = null,
            alertMessage = null,
            errorMessage = null
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Bypass the recorder and feed Gemma a preset "stable walk"
     * feature set. This still goes through the real
     * [AgentOrchestrator] / Gemma 4 round-trip so the demo exercises
     * the full pipeline, just without forcing the presenter to walk.
     */
    fun simulateStableWalk() {
        if (_uiState.value.isBusy) return
        val features = MobilityFeatureExtractor.simulated(
            durationSeconds = 10.0,
            averageAcceleration = 9.85,
            accelerationVariance = 0.9,
            peakAcceleration = 11.4,
            sideToSideSwayScore = 0.7,
            abruptPauses = 0,
            mobilityConfidenceScore = 88
        )
        runAnalysis(features = features, simulationLabel = "Simulated Stable Walk")
    }

    /**
     * Bypass the recorder and feed Gemma a preset "unsteady walk"
     * feature set. Like [simulateStableWalk] this still hits Gemma 4
     * and MobilityShieldTool so the result card behaves identically
     * to a real check.
     */
    fun simulateUnsteadyWalk() {
        if (_uiState.value.isBusy) return
        val features = MobilityFeatureExtractor.simulated(
            durationSeconds = 10.0,
            averageAcceleration = 10.6,
            accelerationVariance = 8.4,
            peakAcceleration = 21.2,
            sideToSideSwayScore = 4.1,
            abruptPauses = 3,
            mobilityConfidenceScore = 58
        )
        runAnalysis(features = features, simulationLabel = "Simulated Unsteady Walk")
    }

    // ---------------------------------------------------------------
    // Internal flow
    // ---------------------------------------------------------------

    private fun observeSensorEvents() {
        viewModelScope.launch {
            mobilitySensorManager.state.collect { sensorState ->
                _uiState.value = _uiState.value.copy(recordingState = sensorState)
            }
        }
        viewModelScope.launch {
            mobilitySensorManager.events.collect { event ->
                when (event) {
                    MobilityCheckEvent.Started -> {
                        _uiState.value = _uiState.value.copy(
                            phase = MobilityShieldUiState.Phase.RECORDING,
                            secondsRemaining = MobilityShieldUiState.TOTAL_RECORDING_SECONDS
                        )
                    }
                    is MobilityCheckEvent.Tick -> {
                        if (_uiState.value.phase == MobilityShieldUiState.Phase.RECORDING) {
                            _uiState.value = _uiState.value.copy(
                                secondsRemaining = event.secondsRemaining
                            )
                        }
                    }
                    is MobilityCheckEvent.Completed -> {
                        val features = MobilityFeatureExtractor.extract(event.samples)
                        runAnalysis(features = features, simulationLabel = null)
                    }
                    MobilityCheckEvent.Cancelled -> Unit
                    is MobilityCheckEvent.Unavailable -> {
                        _uiState.value = _uiState.value.copy(
                            sensorAvailable = false,
                            sensorErrorMessage = event.message,
                            phase = MobilityShieldUiState.Phase.IDLE
                        )
                    }
                }
            }
        }
    }

    private fun runAnalysis(features: MobilityFeatures, simulationLabel: String?) {
        if (analysisJob?.isActive == true) return
        analysisJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                phase = MobilityShieldUiState.Phase.ANALYZING,
                features = features,
                errorMessage = null
            )

            val prompt = buildOrchestratorPrompt(features, simulationLabel)
            val result = runCatching { orchestrator.handleUserMessage(prompt) }
            val agentResult = result.getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    phase = MobilityShieldUiState.Phase.IDLE,
                    errorMessage = "Aasa could not analyze this mobility check: " +
                        (error.message ?: "unknown error")
                )
                return@launch
            }

            val parsed = parseMobilityResult(agentResult.toolResult)
            if (parsed == null) {
                _uiState.value = _uiState.value.copy(
                    phase = MobilityShieldUiState.Phase.IDLE,
                    errorMessage = "Mobility tool did not return a result. Please try again."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                phase = MobilityShieldUiState.Phase.RESULT_READY,
                confidenceScore = parsed.score,
                stabilityLabel = parsed.label,
                riskLevel = parsed.risk,
                assistantResponse = agentResult.action.assistantResponse
                    .takeIf { it.isNotBlank() },
                toolMessage = agentResult.toolResult.message.takeIf { it.isNotBlank() },
                recommendedAction = parsed.recommendedAction,
                contactName = parsed.contactName,
                phoneNumber = parsed.phoneNumber,
                alertMessage = parsed.alertMessage
            )

            val spoken = agentResult.action.assistantResponse
                .takeIf { it.isNotBlank() }
                ?: agentResult.toolResult.message
            if (spoken.isNotBlank()) {
                textToSpeechManager.speak(spoken)
            }
        }
    }

    /**
     * Build the synthetic prompt the Mobility Shield screen sends to
     * the orchestrator. The wording matches phrases recognized by
     * [com.aasa.eldercare.tools.IntentKeywords.isMobilityCheckRequest]
     * so the deterministic override always routes to MobilityShieldTool.
     */
    private fun buildOrchestratorPrompt(
        features: MobilityFeatures,
        simulationLabel: String?
    ): String {
        val opener = simulationLabel?.let { "Mobility check completed ($it)." }
            ?: "Mobility check completed."
        return buildString {
            append(opener)
            append(" Analyze this 10-second motion summary. Do not diagnose. ")
            append("Return a MOBILITY_CHECK JSON response.\n")
            append("Features:\n")
            append("- mobilityConfidenceScore: ${features.mobilityConfidenceScore}\n")
            append("- stabilityLabel: ${features.stabilityLabel}\n")
            append("- averageAcceleration: ${features.averageAcceleration}\n")
            append("- accelerationVariance: ${features.accelerationVariance}\n")
            append("- peakAcceleration: ${features.peakAcceleration}\n")
            append("- sideToSideSwayScore: ${features.sideToSideSwayScore}\n")
            append("- abruptPauses: ${features.abruptPauses}\n")
            append("- smoothnessScore: ${features.smoothnessScore}\n")
            append("- durationSeconds: ${features.durationSeconds}\n")
            append("User context: elder safety check.")
        }
    }

    private data class MobilityResultPayload(
        val score: Int,
        val label: String,
        val risk: String,
        val recommendedAction: String?,
        val alertMessage: String?,
        val contactName: String?,
        val phoneNumber: String?
    )

    private fun parseMobilityResult(result: ToolResult): MobilityResultPayload? {
        val data = result.data
        val actionType = data[ToolResultKeys.ACTION_TYPE] as? String
        if (actionType != ToolActionTypes.MOBILITY_CHECK) return null
        val score = (data[ToolResultKeys.MOBILITY_CONFIDENCE_SCORE] as? Number)?.toInt()
            ?: return null
        val label = (data[ToolResultKeys.STABILITY_LABEL] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: MobilityFeatures.labelForScore(score)
        val risk = (data[ToolResultKeys.EFFECTIVE_RISK_LEVEL] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "LOW"
        return MobilityResultPayload(
            score = score,
            label = label,
            risk = risk,
            recommendedAction = (data[ToolResultKeys.RECOMMENDED_ACTION] as? String)
                ?.takeIf { it.isNotBlank() },
            alertMessage = (data[ToolResultKeys.ALERT_MESSAGE] as? String)
                ?.takeIf { it.isNotBlank() },
            contactName = (data[ToolResultKeys.CONTACT_NAME] as? String)
                ?.takeIf { it.isNotBlank() },
            phoneNumber = (data[ToolResultKeys.PHONE_NUMBER] as? String)
                ?.takeIf { it.isNotBlank() }
        )
    }

    private fun clearResultFields() {
        _uiState.value = _uiState.value.copy(
            features = null,
            confidenceScore = null,
            stabilityLabel = null,
            riskLevel = null,
            assistantResponse = null,
            toolMessage = null,
            recommendedAction = null,
            contactName = null,
            phoneNumber = null,
            alertMessage = null
        )
    }

    override fun onCleared() {
        analysisJob?.cancel()
        mobilitySensorManager.stop()
        textToSpeechManager.shutdown()
        super.onCleared()
    }

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MobilityShieldViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            val ctx = application.applicationContext
            return MobilityShieldViewModel(
                orchestrator = application.agentOrchestrator,
                mobilitySensorManager = MobilitySensorManager(ctx),
                textToSpeechManager = TextToSpeechManager(ctx)
            ) as T
        }
    }

    private companion object {
        const val START_PROMPT =
            "Please hold your phone normally and walk slowly for 10 seconds."
    }
}
