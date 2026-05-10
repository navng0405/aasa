package com.aasa.eldercare.ui.home

import com.aasa.eldercare.agent.AgentAction

/**
 * Single source of truth for the Home / chat screen.
 *
 * Phase 4 surfaced:
 *  - the Gemma decision ([agentAction])
 *  - the live tool execution outcome ([toolExecutionSuccess],
 *    [toolResultMessage], [toolResultData])
 *  - a redundant [persistedToolResultMessage] that is only populated
 *    when the tool actually wrote to Room, so the UI can show a
 *    "Persisted in Room" pill the user can trust.
 *
 * Phase 6 adds voice-first state:
 *  - [isListening]: the SpeechRecognizer is actively listening.
 *  - [isSpeaking]: TextToSpeech is reading the assistant response.
 *  - [recognizedSpeech]: latest partial / final transcript so the
 *    elder can see what the device thinks they said.
 *  - [voiceError]: human-readable last voice / TTS error.
 *  - [ttsStatus]: long-lived informational message about the TTS
 *    engine (e.g. "language not supported").
 *  - [hasMicPermission]: mirrors RECORD_AUDIO so the UI can switch
 *    the mic button into a "request permission" affordance.
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
    val isResettingDemoData: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val recognizedSpeech: String? = null,
    val voiceError: String? = null,
    val ttsStatus: String? = null,
    val hasMicPermission: Boolean = false
)
