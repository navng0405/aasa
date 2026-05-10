package com.aasa.eldercare.model

import com.aasa.eldercare.network.AgentMessageResponse

interface ModelRunner {
    suspend fun sendMessage(message: String): AgentMessageResponse
}
