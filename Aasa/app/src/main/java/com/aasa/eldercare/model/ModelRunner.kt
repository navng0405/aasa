package com.aasa.eldercare.model

import com.aasa.eldercare.network.AgentMessageResponse

/**
 * Contract every Gemma backend must satisfy. The orchestrator depends only on this
 * interface; concrete runners (on-device LiteRT-LM, FastAPI bridge, future variants)
 * implement it.
 *
 * Phase 9 extended this interface from a single-method "send message" into a small
 * lifecycle so `GemmaRouter` can probe availability before routing.
 */
interface ModelRunner {

    /**
     * Short, human-friendly label shown in the Home status card.
     * Examples: "On-device · Gemma 4 E2B", "Mac bridge · gemma4:e2b".
     */
    val label: String

    /**
     * Cheap availability check. Implementations must not throw:
     *   - on-device: true iff the model file exists AND the engine has
     *     initialized (or can be initialized) successfully.
     *   - bridge: true iff `/health` returned 200 within the last probe window.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Run one user turn through the model. Must return an [AgentMessageResponse]
     * shaped identically regardless of backend.
     */
    suspend fun sendMessage(message: String): AgentMessageResponse
}
