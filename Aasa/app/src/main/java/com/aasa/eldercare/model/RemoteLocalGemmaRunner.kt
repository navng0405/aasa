package com.aasa.eldercare.model

import com.aasa.eldercare.network.AgentMessageRequest
import com.aasa.eldercare.network.AgentMessageResponse
import com.aasa.eldercare.network.ApiService
import java.util.Locale

/**
 * Mac-side dev fallback. As of Phase 9 this is **not** the default path —
 * `OnDeviceGemmaRunner` is. The bridge stays around for:
 *  - the emulator / x86_64 hosts where LiteRT-LM is unavailable,
 *  - devices that have not yet had `gemma-4-E2B-it.litertlm` side-loaded,
 *  - the "Force bridge" debug toggle, useful for A/B in the demo.
 */
class RemoteLocalGemmaRunner(
    private val apiService: ApiService
) : ModelRunner {

    override val label: String = "Mac bridge · gemma4:e2b"

    override suspend fun isAvailable(): Boolean = try {
        apiService.checkServer().isSuccessful
    } catch (_: Throwable) {
        false
    }

    override suspend fun sendMessage(
        message: String,
        recentContext: String
    ): AgentMessageResponse {
        return apiService.sendMessage(
            AgentMessageRequest(
                message = message,
                recentContext = recentContext,
                deviceLocale = Locale.getDefault().toLanguageTag()
            )
        )
    }
}
