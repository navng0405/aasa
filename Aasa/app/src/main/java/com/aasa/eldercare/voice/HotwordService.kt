package com.aasa.eldercare.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aasa.eldercare.MainActivity

/**
 * Phase 12: opt-in always-on "Hey Aasa" wake-word foreground service.
 *
 * ## Honest design caveats
 * This is a **hackathon-grade** implementation, not a production hotword:
 *  - It runs Android's stock [SpeechRecognizer] in a re-listen loop and
 *    substring-matches `"hey aasa" / "ok aasa" / "aasa"` in the recognized
 *    text. There is no dedicated keyword-spotting model.
 *  - Battery cost is real (~5–10 %/h while active). The persistent
 *    notification is mandatory under Android 14 FGS rules.
 *  - Doze / app-standby will eventually pause the service. It is **not**
 *    a reliable replacement for Google Assistant's hardware-accelerated
 *    hotword pipeline.
 *  - Some OEM `SpeechRecognizer` implementations refuse to run from a
 *    background service. On those devices the service simply fails fast
 *    and the user is told to use Google Assistant ("Hey Google, open Aasa").
 *
 * ## Privacy contract
 *  - Started **only** after the elder flips the opt-in toggle on Home.
 *  - Posts a permanent foreground notification while listening
 *    (the user always knows the mic is hot).
 *  - Never transcribes anything beyond the wake phrase — the moment we
 *    see the wake word, we cancel the recognizer, launch [MainActivity],
 *    and the in-app greeting + STT flow takes over.
 *  - Stopped by [stop] (toggle off), by the notification's Stop action,
 *    or by `onTaskRemoved` (user swipes the app away).
 */
class HotwordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening: Boolean = false
    private var stopping: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundSelf()
                return START_NOT_STICKY
            }
        }

        if (!hasMicPermission()) {
            Log.w(TAG, "Missing RECORD_AUDIO; refusing to start hotword service.")
            stopForegroundSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer unavailable; refusing to start hotword service.")
            stopForegroundSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        ensureRecognizer()
        startListeningInternal()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the elder swipes the app away, stop listening too. They
        // can re-enable from Home next time.
        stopForegroundSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        createChannelIfNeeded()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundSelf() {
        stopping = true
        runCatching { recognizer?.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hey Aasa listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Aasa is listening for 'Hey Aasa'."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, HotwordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aasa is listening for \u201CHey Aasa\u201D")
            .setContentText("Tap to open Aasa. The mic is on while this is showing.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .build()
    }

    // ------------------------------------------------------------------
    // Recognizer loop
    // ------------------------------------------------------------------

    private fun ensureRecognizer() {
        if (recognizer != null) return
        runOnMain {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(buildListener())
            recognizer = r
        }
    }

    private fun startListeningInternal() {
        if (stopping) return
        runOnMain {
            if (stopping) return@runOnMain
            if (listening) return@runOnMain
            val r = recognizer ?: return@runOnMain
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                listening = true
                r.startListening(intent)
            } catch (t: Throwable) {
                listening = false
                Log.w(TAG, "startListening failed", t)
                scheduleRestart()
            }
        }
    }

    private fun scheduleRestart() {
        if (stopping) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    private val restartRunnable = Runnable {
        listening = false
        startListeningInternal()
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word detected — launching MainActivity.")
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FROM_WAKE_WORD, true)
        }
        try {
            startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to launch MainActivity from hotword.", t)
        }
        // Brief cool-down so the in-app greeting/listen flow can take
        // over without us re-grabbing the mic immediately.
        listening = false
        runCatching { recognizer?.cancel() }
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, POST_WAKE_COOLDOWN_MS)
    }

    private fun buildListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listening = false
            // Most errors here ("no match", "speech timeout") are
            // expected during continuous listening. Just relisten.
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase()
                ?.trim()
                .orEmpty()
            if (containsWakeWord(text)) {
                onWakeWordDetected()
            } else {
                scheduleRestart()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase()
                ?.trim()
                .orEmpty()
            if (containsWakeWord(text)) {
                onWakeWordDetected()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun containsWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        return WAKE_PHRASES.any { text.contains(it) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "HotwordService"
        private const val CHANNEL_ID = "aasa_hotword"
        private const val NOTIFICATION_ID = 4711
        private const val RESTART_DELAY_MS = 600L
        private const val POST_WAKE_COOLDOWN_MS = 3_500L

        const val ACTION_STOP = "com.aasa.eldercare.voice.HOTWORD_STOP"
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"

        /**
         * Phrases that count as a wake word. Substring match on a
         * lowercased recognition string — mirrors the style of
         * `tools/SafetyKeywords.kt` so the trade-offs (false positives
         * on "asa", "asia") are obvious. Tune as needed.
         */
        private val WAKE_PHRASES = listOf(
            "hey aasa",
            "hey asa",
            "ok aasa",
            "okay aasa",
            "aasa"
        )

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HotwordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
