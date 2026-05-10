package com.aasa.eldercare.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Phase 8.6: foreground-only fall detection.
 *
 * IMPORTANT — this is a hackathon-grade heuristic, not a medical
 * device. It runs only while the FallTriage screen is visible (the
 * ViewModel calls [stop] in onCleared) and never claims to diagnose.
 *
 * Heuristic:
 *  1. Read [Sensor.TYPE_ACCELEROMETER] at SENSOR_DELAY_GAME (~20 Hz).
 *  2. Compute total magnitude: sqrt(x² + y² + z²) (includes gravity).
 *  3. When magnitude exceeds [SPIKE_THRESHOLD_MS2] (default 25 m/s²),
 *     enter SPIKE state and stamp the time.
 *  4. For the next [STILLNESS_WINDOW_MS] (~2.5 s), count samples whose
 *     magnitude stays inside ±[STILLNESS_TOLERANCE_MS2] of gravity
 *     (~9.81 m/s²). If we observe at least [STILLNESS_MIN_SAMPLES]
 *     such samples (≈ 1.5 s of near-stillness), emit a PossibleFall
 *     event and reset.
 *  5. If the window expires without enough stillness samples, reset.
 *
 * Concurrency:
 *  - Sensor callbacks land on the main thread by default.
 *  - State transitions are synchronized so [simulateFall] (called from
 *    UI) and [onSensorChanged] cannot race.
 *  - Events are exposed as a [SharedFlow]; the ViewModel collects on
 *    [androidx.lifecycle.viewModelScope].
 */
class FallDetectionManager(
    context: Context
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _events = MutableSharedFlow<FallDetectionEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val events: SharedFlow<FallDetectionEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(FallDetectionState.IDLE)
    val state: StateFlow<FallDetectionState> = _state.asStateFlow()

    private var spikeStartElapsedMs: Long = 0L
    private var peakMagnitudeSinceSpike: Float = 0f
    private var stillnessSamplesSinceSpike: Int = 0
    private var inSpikeWindow: Boolean = false
    private val lock = Any()

    fun isAvailable(): Boolean = accelerometer != null

    /**
     * Begin sampling. Idempotent: a second call while monitoring is a
     * no-op. Returns false (and emits Unavailable) if the device has
     * no accelerometer.
     */
    fun start(): Boolean {
        if (_state.value == FallDetectionState.MONITORING ||
            _state.value == FallDetectionState.POSSIBLE_FALL
        ) {
            return true
        }
        val sensor = accelerometer
        if (sensor == null) {
            _state.value = FallDetectionState.UNAVAILABLE
            _events.tryEmit(
                FallDetectionEvent.Unavailable(
                    "This device does not have an accelerometer."
                )
            )
            return false
        }
        return try {
            val ok = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            if (ok) {
                resetWindow()
                _state.value = FallDetectionState.MONITORING
                _events.tryEmit(FallDetectionEvent.Started)
                true
            } else {
                _state.value = FallDetectionState.UNAVAILABLE
                _events.tryEmit(
                    FallDetectionEvent.Unavailable(
                        "Could not register accelerometer listener."
                    )
                )
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            _state.value = FallDetectionState.UNAVAILABLE
            _events.tryEmit(
                FallDetectionEvent.Unavailable("Sensor error: ${t.message ?: "unknown"}")
            )
            false
        }
    }

    /**
     * Stop sampling. Safe to call multiple times. The screen calls
     * this from onCleared and from the manual "Stop Fall Detection"
     * button.
     */
    fun stop() {
        try {
            sensorManager.unregisterListener(this)
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
        synchronized(lock) { resetWindow() }
        if (_state.value != FallDetectionState.UNAVAILABLE) {
            _state.value = FallDetectionState.IDLE
        }
        _events.tryEmit(FallDetectionEvent.Stopped)
    }

    /**
     * Bypass the sensor heuristic and emit a synthetic PossibleFall
     * event. Used by the "Simulate Fall" button so the demo runs
     * cleanly on devices that won't be physically dropped.
     */
    fun simulateFall() {
        synchronized(lock) {
            resetWindow()
            _state.value = FallDetectionState.POSSIBLE_FALL
        }
        _events.tryEmit(
            FallDetectionEvent.PossibleFall(
                peakMagnitude = SIMULATED_PEAK,
                stillnessSamples = STILLNESS_MIN_SAMPLES,
                simulated = true
            )
        )
    }

    /**
     * Manually return the monitor to the IDLE state after a triage
     * card has been dismissed, so a follow-up Simulate Fall fires
     * cleanly.
     */
    fun acknowledgeFall() {
        synchronized(lock) { resetWindow() }
        if (_state.value == FallDetectionState.POSSIBLE_FALL) {
            // If the listener is still registered we go back to monitoring;
            // otherwise back to idle.
            _state.value = if (accelerometer != null) {
                FallDetectionState.MONITORING
            } else {
                FallDetectionState.IDLE
            }
        }
    }

    // ----------------------------------------------------------------
    // SensorEventListener
    // ----------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()

        synchronized(lock) {
            if (!inSpikeWindow) {
                if (magnitude >= SPIKE_THRESHOLD_MS2) {
                    inSpikeWindow = true
                    spikeStartElapsedMs = now
                    peakMagnitudeSinceSpike = magnitude
                    stillnessSamplesSinceSpike = 0
                }
                return
            }

            // We are inside the post-spike observation window.
            val elapsed = now - spikeStartElapsedMs
            if (magnitude > peakMagnitudeSinceSpike) {
                peakMagnitudeSinceSpike = magnitude
            }
            val isStill = kotlin.math.abs(magnitude - GRAVITY_MS2) <= STILLNESS_TOLERANCE_MS2
            if (isStill) {
                stillnessSamplesSinceSpike++
            }

            if (stillnessSamplesSinceSpike >= STILLNESS_MIN_SAMPLES) {
                val peak = peakMagnitudeSinceSpike
                val samples = stillnessSamplesSinceSpike
                resetWindow()
                _state.value = FallDetectionState.POSSIBLE_FALL
                _events.tryEmit(
                    FallDetectionEvent.PossibleFall(
                        peakMagnitude = peak,
                        stillnessSamples = samples,
                        simulated = false
                    )
                )
                return
            }

            if (elapsed >= STILLNESS_WINDOW_MS) {
                resetWindow()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ----------------------------------------------------------------

    private fun resetWindow() {
        inSpikeWindow = false
        spikeStartElapsedMs = 0L
        peakMagnitudeSinceSpike = 0f
        stillnessSamplesSinceSpike = 0
    }

    companion object {
        private const val TAG = "FallDetectionManager"

        // ~9.81 m/s² baseline gravity. We treat readings within
        // ±STILLNESS_TOLERANCE_MS2 of this as "barely moving".
        private const val GRAVITY_MS2: Float = 9.81f

        // Magnitude threshold (in m/s²) that flags a likely impact.
        // Tuned for hackathon demo reliability — real medical-grade
        // detectors use multi-axis filtering and ML.
        private const val SPIKE_THRESHOLD_MS2: Float = 25f

        // Tolerance around gravity that counts as "still".
        private const val STILLNESS_TOLERANCE_MS2: Float = 2.5f

        // How long we wait for stillness after a spike (~2.5 s).
        private const val STILLNESS_WINDOW_MS: Long = 2_500L

        // ~1.5 s of near-still samples at SENSOR_DELAY_GAME (~20 Hz)
        // is enough for the demo heuristic.
        private const val STILLNESS_MIN_SAMPLES: Int = 30

        private const val SIMULATED_PEAK: Float = 32f
    }
}
