package com.aasa.eldercare.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("health")
    suspend fun checkServer(): Response<HealthResponse>

    @POST("agent/message")
    suspend fun sendMessage(
        @Body request: AgentMessageRequest
    ): AgentMessageResponse
}
