package com.aasa.gemmabridgepoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AasaPocUiState(
    val serverUrl: String = "http://127.0.0.1:8000",
    val message: String = "I took my BP tablet.",
    val isLoading: Boolean = false,
    val status: String = "Ready. Start your FastAPI server, then run: adb reverse tcp:8000 tcp:8000",
    val error: String? = null,
    val parsedResult: AgentMessageResponse? = null,
    val rawResponse: String = ""
)

class MainViewModel : ViewModel() {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val _uiState = MutableStateFlow(AasaPocUiState())
    val uiState: StateFlow<AasaPocUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrl = value) }
    }

    fun onMessageChanged(value: String) {
        _uiState.update { it.copy(message = value) }
    }

    fun fillMedicationExample() {
        _uiState.update {
            it.copy(
                message = "I took my BP tablet.",
                error = null,
                status = "Medication example loaded."
            )
        }
    }

    fun fillSafetyExample() {
        _uiState.update {
            it.copy(
                message = "I feel dizzy and my chest hurts.",
                error = null,
                status = "Safety example loaded."
            )
        }
    }

    fun clear() {
        activeJob?.cancel()
        _uiState.value = AasaPocUiState(
            serverUrl = _uiState.value.serverUrl,
            message = "",
            status = "Cleared."
        )
    }

    fun checkServer() {
        runNetworkAction(
            loadingStatus = "Checking server..."
        ) {
            val response = createApiService(_uiState.value.serverUrl).checkServer()
            val reachableStatus = if (response.isSuccessful) {
                "Server reached at ${_uiState.value.serverUrl}."
            } else {
                "Server responded at ${_uiState.value.serverUrl} with HTTP ${response.code()}."
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    status = reachableStatus,
                    error = null
                )
            }
        }
    }

    fun runAasaJsonPrompt() {
        val currentMessage = _uiState.value.message.trim()
        if (currentMessage.isEmpty()) {
            _uiState.update {
                it.copy(
                    status = "Add a message before running the prompt.",
                    error = "Message cannot be empty."
                )
            }
            return
        }

        runNetworkAction(
            loadingStatus = "Calling /agent/message..."
        ) {
            val response = createApiService(_uiState.value.serverUrl)
                .sendMessage(AgentMessageRequest(message = currentMessage))

            _uiState.update {
                it.copy(
                    isLoading = false,
                    status = "Received response from /agent/message.",
                    error = null,
                    parsedResult = response,
                    rawResponse = response.rawResponse.orEmpty().ifBlank { gson.toJson(response) }
                )
            }
        }
    }

    private fun runNetworkAction(
        loadingStatus: String,
        block: suspend () -> Unit
    ) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    status = loadingStatus,
                    error = null
                )
            }

            try {
                block()
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        status = "Request failed.",
                        error = exception.toUserMessage()
                    )
                }
            }
        }
    }

    private fun createApiService(serverUrl: String): ApiService {
        val baseUrl = serverUrl.trim().ensureTrailingSlash()
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"

    private fun Exception.toUserMessage(): String = when (this) {
        is IllegalArgumentException -> "Invalid server URL. Use a full URL like http://127.0.0.1:8000"
        is HttpException -> "HTTP ${code()}: ${message()}"
        is IOException -> "Network error: ${message ?: "Could not reach the server. Check adb reverse and FastAPI."}"
        else -> message ?: "Unexpected error."
    }
}
