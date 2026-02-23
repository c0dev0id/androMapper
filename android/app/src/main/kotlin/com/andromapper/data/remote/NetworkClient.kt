package com.andromapper.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private var serverBaseUrl: String = "https://example.com/"

    /** Set the server URL. Must end with '/'. */
    fun configure(baseUrl: String) {
        serverBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        _retrofit = null  // invalidate cached instance
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            // Retry once on failure
            .addInterceptor { chain ->
                val request = chain.request()
                var response = runCatching { chain.proceed(request) }.getOrNull()
                if (response == null || !response.isSuccessful) {
                    response?.close()
                    response = runCatching { chain.proceed(request) }.getOrNull()
                }
                response ?: chain.proceed(request)
            }
            .build()
    }

    private var _retrofit: Retrofit? = null

    private val retrofit: Retrofit
        get() = _retrofit ?: Retrofit.Builder()
            .baseUrl(serverBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .also { _retrofit = it }

    val apiService: ApiService by lazy { retrofit.create(ApiService::class.java) }

    /** Raw OkHttp client for tile downloads outside Retrofit. */
    fun getOkHttpClient(): OkHttpClient = okHttpClient
}
