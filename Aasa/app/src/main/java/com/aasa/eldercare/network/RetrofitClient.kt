package com.aasa.eldercare.network

import android.util.Log
import com.aasa.eldercare.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provides a single Retrofit-backed [ApiService] talking to the local Gemma
 * FastAPI bridge running on the developer Mac.
 *
 * By default, the Pixel device reaches the Mac via:
 *     adb reverse tcp:8000 tcp:8000
 * so from the device's point of view the server lives at 127.0.0.1:8000.
 *
 * For Wi-Fi/LAN testing, build with:
 *     ./gradlew :app:installDebug -PaasaGemmaBaseUrl=http://<mac-ip>:8000/
 */
object RetrofitClient {

    private const val TAG = "RetrofitClient"
    private val baseUrl: String = BuildConfig.AASA_GEMMA_BASE_URL

    private val loggingInterceptor: HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Log.i(TAG, "Gemma bridge base URL: $baseUrl")
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}
