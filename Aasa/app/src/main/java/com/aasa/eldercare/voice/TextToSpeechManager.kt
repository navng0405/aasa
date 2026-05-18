package com.aasa.eldercare.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class TtsEvent {
    object Ready : TtsEvent()
    object Started : TtsEvent()
    object Done : TtsEvent()
    data class Error(val message: String) : TtsEvent()
}

/**
 * Thin wrapper around Android's [TextToSpeech]. Handles:
 *  - Engine initialization (which is async)
 *  - Locale fallback (Locale.getDefault → Locale.US if unavailable)
 *  - "Speaking" state via an [UtteranceProgressListener]
 *  - Safe shutdown
 *
 * Always call [shutdown] when the owning component (the
 * [com.aasa.eldercare.ui.home.HomeViewModel]) is cleared so the
 * native TTS service stops holding references.
 */
class TextToSpeechManager(
    context: Context
) {

    private val tts: TextToSpeech
    @Volatile private var isReady: Boolean = false

    private val _events = MutableSharedFlow<TtsEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { initStatus ->
            handleInitResult(initStatus)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                _events.tryEmit(TtsEvent.Started)
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                _events.tryEmit(TtsEvent.Done)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                _events.tryEmit(TtsEvent.Error("Speech playback failed."))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                _events.tryEmit(TtsEvent.Error("Speech playback failed (code $errorCode)."))
            }
        })
    }

    fun speak(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        if (!isReady) {
            _status.value = "Voice playback not ready yet. Please try again."
            _events.tryEmit(TtsEvent.Error("Text-to-speech is not ready yet."))
            return
        }
        val utteranceId = "aasa-${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = tts.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _isSpeaking.value = false
            _events.tryEmit(TtsEvent.Error("Could not start speaking."))
        } else {
            _status.value = null
            _isSpeaking.value = true
        }
    }

    fun stop() {
        try {
            tts.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
        _isSpeaking.value = false
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        _isSpeaking.value = false
        isReady = false
    }

    private fun handleInitResult(initStatus: Int) {
        if (initStatus != TextToSpeech.SUCCESS) {
            isReady = false
            _status.value = "Voice playback is unavailable on this device."
            _events.tryEmit(TtsEvent.Error("Text-to-speech failed to initialize."))
            return
        }

        val preferred = Locale.getDefault()
        val applied = applyLocaleWithFallback(preferred)
        if (applied != null) {
            configureWarmVoice(applied)
            isReady = true
            _status.value = null
            _events.tryEmit(TtsEvent.Ready)
        } else {
            isReady = false
            _status.value = "Voice playback language is not supported on this device."
            _events.tryEmit(TtsEvent.Error("Text-to-speech language not supported."))
        }
    }

    private fun configureWarmVoice(locale: Locale) {
        runCatching {
            val localVoice = tts.voices
                ?.filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                        voice.locale.language == locale.language
                }
                ?.maxWithOrNull(
                    compareBy(
                        { it.quality },
                        { -it.latency }
                    )
                )
            if (localVoice != null) {
                tts.voice = localVoice
            }
        }.onFailure { t ->
            Log.w(TAG, "voice selection failed", t)
        }

        // Aasa should sound calm and elder-friendly, not rushed or chirpy.
        runCatching { tts.setSpeechRate(WARM_SPEECH_RATE) }
        runCatching { tts.setPitch(WARM_PITCH) }
    }

    /**
     * Try the preferred locale first, then Locale.US as a hackathon-safe
     * fallback. Returns the locale that actually got applied or `null`
     * if both are unsupported.
     */
    private fun applyLocaleWithFallback(preferred: Locale): Locale? {
        if (trySetLanguage(preferred)) return preferred
        if (preferred != Locale.US && trySetLanguage(Locale.US)) return Locale.US
        return null
    }

    private fun trySetLanguage(locale: Locale): Boolean {
        return try {
            val res = tts.setLanguage(locale)
            res != TextToSpeech.LANG_MISSING_DATA &&
                res != TextToSpeech.LANG_NOT_SUPPORTED
        } catch (t: Throwable) {
            Log.w(TAG, "setLanguage($locale) failed", t)
            false
        }
    }

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val WARM_SPEECH_RATE = 0.88f
        private const val WARM_PITCH = 0.96f
    }
}
