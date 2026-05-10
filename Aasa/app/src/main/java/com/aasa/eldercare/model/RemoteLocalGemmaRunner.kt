package com.aasa.eldercare.model

import com.aasa.eldercare.network.AgentMessageRequest
import com.aasa.eldercare.network.AgentMessageResponse
import com.aasa.eldercare.network.ApiService

class RemoteLocalGemmaRunner(
    private val apiService: ApiService
) : ModelRunner {
    override suspend fun sendMessage(message: String): AgentMessageResponse {
        return apiService.sendMessage(AgentMessageRequest(message = message))
    }
}
