package com.aasa.eldercare.ui.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.tools.ToolResultKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 10 — Health Briefing screen state.
 *
 * @param briefingText the elder-friendly paragraph produced by
 *   [com.aasa.eldercare.tools.HealthBriefingTool].
 * @param highlights short bullet lines ("Sleep: 6.2 hours last night").
 * @param isMockData true when no wearable was connected and the
 *   numbers came from synthetic demo data. The UI MUST surface
 *   the "Demo data — no wearable connected" pill in that case.
 * @param snapshotSource human-readable source string for the data.
 */
data class HealthBriefingUiState(
    val isLoading: Boolean = false,
    val briefingText: String? = null,
    val highlights: List<String> = emptyList(),
    val sleepHours: Double? = null,
    val avgRestingHeartRateBpm: Int? = null,
    val steps: Int? = null,
    val isMockData: Boolean = true,
    val snapshotSource: String? = null,
    val errorMessage: String? = null
)

class HealthBriefingViewModel(
    private val orchestrator: AgentOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthBriefingUiState())
    val uiState: StateFlow<HealthBriefingUiState> = _uiState.asStateFlow()

    /**
     * Fires the synthetic "Generate my morning briefing" prompt
     * through the agent orchestrator. The deterministic override in
     * [AgentOrchestrator] always routes this to HealthBriefingTool,
     * so the on-device Gemma 4 runner only sees it for telemetry —
     * the actual numbers come straight from Health Connect.
     */
    fun generateBriefing() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                orchestrator.handleUserMessage(BRIEFING_PROMPT)
            }.onSuccess { result ->
                val data = result.toolResult.data
                _uiState.value = HealthBriefingUiState(
                    isLoading = false,
                    briefingText = (data[ToolResultKeys.BRIEFING_TEXT] as? String)
                        ?: result.toolResult.message,
                    highlights = (data[ToolResultKeys.BRIEFING_HIGHLIGHTS] as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList(),
                    sleepHours = (data[ToolResultKeys.SLEEP_HOURS] as? Number)?.toDouble(),
                    avgRestingHeartRateBpm = (data[ToolResultKeys.AVG_RESTING_HEART_RATE] as? Number)?.toInt(),
                    steps = (data[ToolResultKeys.STEPS] as? Number)?.toInt(),
                    isMockData = (data[ToolResultKeys.IS_MOCK_DATA] as? Boolean) ?: true,
                    snapshotSource = data[ToolResultKeys.SNAPSHOT_SOURCE] as? String,
                    errorMessage = null
                )
            }.onFailure { t ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = t.message ?: "Could not generate briefing."
                )
            }
        }
    }

    companion object {
        private const val BRIEFING_PROMPT = "Generate my morning briefing."

        fun factory(app: AasaApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HealthBriefingViewModel(app.agentOrchestrator) as T
            }
    }
}
