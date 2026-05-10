package com.aasa.gemmabridgepoc

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET

interface ApiService {
    @GET("health")
    suspend fun checkServer(): Response<Unit>

    @POST("agent/message")
    suspend fun sendMessage(
        @Body request: AgentMessageRequest
    ): AgentMessageResponse
}
