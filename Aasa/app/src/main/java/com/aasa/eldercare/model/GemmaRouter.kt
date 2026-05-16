package com.aasa.eldercare.model

import android.util.Log
import com.aasa.eldercare.network.AgentMessageResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 9: picks between on-device Gemma 4 (preferred) and the Mac bridge
 * (dev fallback) on every turn.
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
    private val bridge: ModelRunner
) : ModelRunner {

    override val label: String get() = activeRunnerLabel.value

    private val _activeRunnerLabel: MutableStateFlow<String> =
        MutableStateFlow(onDevice.label)
    val activeRunnerLabel: StateFlow<String> = _activeRunnerLabel.asStateFlow()

    private val _lastRoutingReason: MutableStateFlow<String> =
        MutableStateFlow("Initial state — no turn served yet.")
    val lastRoutingReason: StateFlow<String> = _lastRoutingReason.asStateFlow()

    @Volatile var forceBridge: Boolean = false

    override suspend fun isAvailable(): Boolean {
        // The router is available iff at least one underlying runner is.
        return onDevice.isAvailable() || bridge.isAvailable()
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
                return runBridge(
                    message,
                    reasonPrefix = "On-device failed (${t.javaClass.simpleName}); fell back to bridge"
                )
            }
        }

        // 3) Bridge fallback.
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
                "No Gemma backend is available. Side-load the model file or start the Mac bridge."
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
