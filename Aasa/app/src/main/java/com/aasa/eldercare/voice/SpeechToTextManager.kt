package com.aasa.eldercare.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * One event in the speech-to-text lifecycle. Consumers (the
 * [com.aasa.eldercare.ui.home.HomeViewModel]) flatten these into UI
 * state and never have to know about Android's [RecognitionListener].
 */
sealed class SpeechEvent {
    object ReadyForSpeech : SpeechEvent()
    object BeginningOfSpeech : SpeechEvent()
    object EndOfSpeech : SpeechEvent()
    data class Partial(val text: String) : SpeechEvent()
    data class Recognized(val text: String) : SpeechEvent()
    data class Error(val message: String) : SpeechEvent()
}

/**
 * Thin wrapper around Android's [SpeechRecognizer]. Owns the
 * recognizer instance and the [RecognitionListener]; emits coarse
 * [SpeechEvent]s through a [SharedFlow] and tracks [isListening] so
 * the UI can flip the mic button label.
 *
 * All [SpeechRecognizer] interactions happen on the main thread per
 * the platform contract – callers may invoke the public methods from
 * any thread.
 *
 * Lifecycle:
 *  - The first [startListening] call lazily creates the recognizer.
 *  - [destroy] must be called when the ViewModel is cleared so the
 *    recognizer's audio session is released.
 */
class SpeechToTextManager(
    private val context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(language: Locale = Locale.getDefault()) {
        runOnMain {
            if (!isRecognitionAvailable()) {
                emit(SpeechEvent.Error("Speech recognition is not available on this device."))
                return@runOnMain
            }
            ensureRecognizer()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    context.packageName
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
            }

            try {
                _isListening.value = true
                recognizer?.startListening(intent)
            } catch (t: Throwable) {
                _isListening.value = false
                Log.w(TAG, "startListening failed", t)
                emit(SpeechEvent.Error("Could not start listening. Please try again."))
            }
        }
    }

    fun stopListening() {
        runOnMain {
            try {
                recognizer?.stopListening()
            } catch (t: Throwable) {
                Log.w(TAG, "stopListening failed", t)
            }
            _isListening.value = false
        }
    }

    fun cancel() {
        runOnMain {
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "cancel failed", t)
            }
            _isListening.value = false
        }
    }

    fun destroy() {
        runOnMain {
            try {
                recognizer?.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy failed", t)
            }
            recognizer = null
            _isListening.value = false
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(buildListener())
        recognizer = r
    }

    private fun buildListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            emit(SpeechEvent.ReadyForSpeech)
        }

        override fun onBeginningOfSpeech() {
            emit(SpeechEvent.BeginningOfSpeech)
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            emit(SpeechEvent.EndOfSpeech)
        }

        override fun onError(error: Int) {
            _isListening.value = false
            emit(SpeechEvent.Error(describeError(error)))
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                emit(SpeechEvent.Recognized(text))
            } else {
                emit(SpeechEvent.Error("Sorry, I didn't catch that. Please try again."))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) emit(SpeechEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone error. Please check your device and try again."
        SpeechRecognizer.ERROR_CLIENT ->
            "Voice client error. Please try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is needed for voice input."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Voice recognition needs a network connection."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Sorry, I didn't catch that. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Voice recognizer is busy. Please try again."
        SpeechRecognizer.ERROR_SERVER ->
            "Voice server error. Please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "I didn't hear anything. Please try again."
        else -> "Voice input failed. Please try again."
    }

    private fun emit(event: SpeechEvent) {
        _events.tryEmit(event)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "SpeechToTextManager"
    }
}
