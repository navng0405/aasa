package com.aasa.eldercare.model

import android.util.Log
import com.aasa.eldercare.network.AgentMessageResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 9: picks between on-device Gemma 4 and, only when explicitly enabled,
 * the Mac bridge dev fallback.
 *
 * Decision rules (in order):
 *  1. If `forceBridge` is true → bridge.
 *  2. If on-device reports `isAvailable() == true` → on-device.
 *  3. Else if bridge reports `isAvailable() == true` → bridge.
 *  4. Else → throw, so the caller can show a friendly "no model available" UI.
 *
 * Generation-time fallback: if the selected on-device runner throws *during*
 * `sendMessage`, we try the bridge once before giving up. This keeps the demo
 * resilient to a flaky native engine.
 *
 * Exposes [activeRunnerLabel] + [lastRoutingReason] as Compose-friendly StateFlows
 * so the Home status card can show the user which path served the last turn.
 */
class GemmaRouter(
    private val onDevice: OnDeviceGemmaRunner,
    private val bridge: ModelRunner,
    private val bridgeEnabled: Boolean
) : ModelRunner {

    override val label: String get() = activeRunnerLabel.value

    private val _activeRunnerLabel: MutableStateFlow<String> =
        MutableStateFlow(onDevice.label)
    val activeRunnerLabel: StateFlow<String> = _activeRunnerLabel.asStateFlow()

    private val _lastRoutingReason: MutableStateFlow<String> =
        MutableStateFlow("Initial state — no turn served yet.")
    val lastRoutingReason: StateFlow<String> = _lastRoutingReason.asStateFlow()

    @Volatile var forceBridge: Boolean = false

    val usingBridge: Boolean
        get() = forceBridge

    val selectedRunnerLabel: String
        get() = if (forceBridge) bridge.label else onDevice.label

    val selectedStatusReason: String?
        get() = if (forceBridge) {
            "Mac bridge unreachable. Start FastAPI and use adb reverse or a LAN base URL."
        } else {
            onDevice.statusReason
        }

    override suspend fun isAvailable(): Boolean {
        return onDevice.isAvailable() || (bridgeEnabled && bridge.isAvailable())
    }

    suspend fun isSelectedRunnerAvailable(): Boolean {
        return if (forceBridge) {
            bridge.isAvailable()
        } else {
            onDevice.isAvailable()
        }
    }

    override suspend fun sendMessage(message: String): AgentMessageResponse {
        // 1) Forced bridge — useful for A/B during the demo.
        if (forceBridge) {
            return runBridge(message, reasonPrefix = "Force-bridge toggle ON")
        }

        // 2) On-device preferred.
        val onDeviceOk = try {
            onDevice.isAvailable()
        } catch (t: Throwable) {
            Log.w(TAG, "onDevice.isAvailable() threw", t)
            false
        }

        if (onDeviceOk) {
            try {
                val resp = onDevice.sendMessage(message)
                _activeRunnerLabel.value = onDevice.label
                _lastRoutingReason.value =
                    "On-device Gemma 4 served this turn (LiteRT-LM)."
                return resp
            } catch (t: Throwable) {
                Log.w(TAG, "On-device generation failed, falling back to bridge", t)
                if (!bridgeEnabled) {
                    _activeRunnerLabel.value = "On-device unavailable"
                    _lastRoutingReason.value =
                        "On-device generation failed (${t.javaClass.simpleName}). Bridge fallback is disabled."
                    throw IllegalStateException(
                        "On-device Gemma failed: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
                return runBridge(
                    message,
                    reasonPrefix = "On-device failed (${t.javaClass.simpleName}); fell back to bridge"
                )
            }
        }

        if (!bridgeEnabled) {
            _activeRunnerLabel.value = "On-device unavailable"
            _lastRoutingReason.value =
                "On-device unavailable (${onDevice.statusReason ?: "unknown"}). Bridge fallback is disabled."
            throw IllegalStateException(
                "On-device Gemma model is not ready. Push the .litertlm file to ${onDevice.expectedModelPath} and restart Aasa."
            )
        }

        // 3) Optional bridge fallback.
        return runBridge(
            message,
            reasonPrefix = "On-device unavailable (${onDevice.statusReason ?: "unknown"}); using bridge"
        )
    }

    private suspend fun runBridge(message: String, reasonPrefix: String): AgentMessageResponse {
        val bridgeOk = try {
            bridge.isAvailable()
        } catch (_: Throwable) {
            false
        }
        if (!bridgeOk) {
            _activeRunnerLabel.value = "Unavailable"
            _lastRoutingReason.value = "$reasonPrefix. Bridge is also unreachable."
            throw IllegalStateException(
                if (bridgeEnabled) {
                    "No Gemma backend is available. Side-load the model file or start the Mac bridge."
                } else {
                    "On-device Gemma model is not ready. Push the .litertlm file to ${onDevice.expectedModelPath} and restart Aasa."
                }
            )
        }
        val resp = bridge.sendMessage(message)
        _activeRunnerLabel.value = bridge.label
        _lastRoutingReason.value = "$reasonPrefix."
        return resp
    }

    companion object {
        private const val TAG = "GemmaRouter"
    }
}
