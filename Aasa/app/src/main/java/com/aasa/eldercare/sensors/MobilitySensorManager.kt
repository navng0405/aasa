package com.aasa.eldercare.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 8.7: foreground-only mobility recorder.
 *
 * Records [Sensor.TYPE_ACCELEROMETER] (and [Sensor.TYPE_GYROSCOPE]
 * when available) for a fixed [RECORDING_DURATION_MS] window, then
 * stops automatically and emits a [MobilityCheckEvent.Completed] with
 * the captured samples. This class deliberately:
 *
 *  - never starts on its own,
 *  - never runs in the background (the screen calls [stop] in
 *    onCleared and on dispose),
 *  - never persists raw motion samples to disk,
 *  - never exposes a continuous monitoring API.
 *
 * The hardware listener is registered on the main thread so callers
 * don't need their own Looper, and tick events are scheduled on the
 * same handler so the UI countdown matches real elapsed time.
 *
 * IMPORTANT — this is a hackathon-grade recorder. Downstream feature
 * extraction in [MobilityFeatureExtractor] is also a heuristic and
 * does NOT perform clinical gait analysis.
 */
class MobilitySensorManager(
    context: Context
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val handler: Handler = Handler(Looper.getMainLooper())

    private val _events = MutableSharedFlow<MobilityCheckEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<MobilityCheckEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(
        if (accelerometer != null) MobilityCheckState.IDLE else MobilityCheckState.UNAVAILABLE
    )
    val state: StateFlow<MobilityCheckState> = _state.asStateFlow()

    /**
     * Buffered samples for the current recording. Cleared at start.
     * Synchronized through [bufferLock] because sensor callbacks land
     * on the main thread but [stop] / [stopAndComplete] can be invoked
     * from the same thread (or via the Handler), and we want sample
     * append + drain to remain safe.
     */
    private val sampleBuffer: ArrayList<MotionSample> = ArrayList()

    /**
     * Latest gyroscope reading. The gyro stream usually arrives at a
     * different cadence than the accelerometer; we attach the most
     * recent gyro reading to each accelerometer sample so the data
     * remains a single chronological list.
     */
    @Volatile
    private var lastGyro: FloatArray? = null

    private val bufferLock = Any()

    @Volatile private var inProgress: Boolean = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!inProgress) return
            val elapsed = System.currentTimeMillis() - startWallClockMs
            val remainingMs = (RECORDING_DURATION_MS - elapsed).coerceAtLeast(0L)
            val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
            _events.tryEmit(MobilityCheckEvent.Tick(remainingSeconds))
            if (remainingMs > 0L) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    private val completionRunnable = Runnable {
        if (inProgress) {
            stopAndComplete()
        }
    }

    @Volatile
    private var startWallClockMs: Long = 0L

    fun isAvailable(): Boolean = accelerometer != null

    fun hasGyroscope(): Boolean = gyroscope != null

    /**
     * Begin a 10-second recording. Idempotent: a second call while
     * already recording is a no-op. Returns false (and emits
     * [MobilityCheckEvent.Unavailable]) if the device has no
     * accelerometer or registration fails.
     */
    fun start(): Boolean {
        if (inProgress) return true
        val sensor = accelerometer
        if (sensor == null) {
            _state.value = MobilityCheckState.UNAVAILABLE
            _events.tryEmit(
                MobilityCheckEvent.Unavailable(
                    "This device does not have an accelerometer. Use Simulate Stable Walk or Simulate Unsteady Walk."
                )
            )
            return false
        }

        synchronized(bufferLock) {
            sampleBuffer.clear()
            lastGyro = null
        }

        val accelOk = registerListener(sensor)
        if (!accelOk) {
            _state.value = MobilityCheckState.UNAVAILABLE
            _events.tryEmit(
                MobilityCheckEvent.Unavailable(
                    "Could not register the accelerometer for the mobility check."
                )
            )
            return false
        }
        gyroscope?.let { registerListener(it) }

        inProgress = true
        _state.value = MobilityCheckState.RECORDING
        startWallClockMs = System.currentTimeMillis()
        _events.tryEmit(MobilityCheckEvent.Started)
        handler.post(tickRunnable)
        handler.postDelayed(completionRunnable, RECORDING_DURATION_MS)
        return true
    }

    private fun registerListener(sensor: Sensor): Boolean = try {
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    } catch (t: Throwable) {
        Log.w(TAG, "registerListener(${sensor.name}) failed", t)
        false
    }

    /**
     * Stop early without emitting a completion. Used by the screen's
     * Reset button and from onCleared so we never leak the listener
     * after navigating away.
     */
    fun stop() {
        if (!inProgress && _state.value != MobilityCheckState.RECORDING) {
            cancelPendingCallbacks()
            return
        }
        cancelPendingCallbacks()
        unregisterListener()
        inProgress = false
        if (_state.value != MobilityCheckState.UNAVAILABLE) {
            _state.value = MobilityCheckState.IDLE
        }
        _events.tryEmit(MobilityCheckEvent.Cancelled)
    }

    private fun stopAndComplete() {
        cancelPendingCallbacks()
        unregisterListener()
        val snapshot = synchronized(bufferLock) { ArrayList(sampleBuffer) }
        inProgress = false
        if (_state.value != MobilityCheckState.UNAVAILABLE) {
            _state.value = MobilityCheckState.IDLE
        }
        _events.tryEmit(MobilityCheckEvent.Completed(snapshot))
    }

    private fun cancelPendingCallbacks() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(completionRunnable)
    }

    private fun unregisterListener() {
        try {
            sensorManager.unregisterListener(this)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterListener failed", t)
        }
    }

    // ----------------------------------------------------------------
    // SensorEventListener
    // ----------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (!inProgress) return
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = floatArrayOf(e.values[0], e.values[1], e.values[2])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val gyro = lastGyro
                val sample = MotionSample(
                    timestampNanos = e.timestamp,
                    ax = e.values[0],
                    ay = e.values[1],
                    az = e.values[2],
                    gx = gyro?.get(0),
                    gy = gyro?.get(1),
                    gz = gyro?.get(2)
                )
                synchronized(bufferLock) { sampleBuffer.add(sample) }
            }
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "MobilitySensorManager"

        /** Length of the foreground walk check (10 seconds). */
        const val RECORDING_DURATION_MS: Long = 10_000L

        /** UI countdown tick (1 second). */
        private const val TICK_INTERVAL_MS: Long = 1_000L
    }
}
