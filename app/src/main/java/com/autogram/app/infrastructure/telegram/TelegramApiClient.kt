package com.autogram.app.infrastructure.telegram

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var api: TelegramApi? = null
    private var baseUrl: String? = null

    fun configure(botToken: String) {
        val url = "https://api.telegram.org/bot$botToken/"
        if (url == baseUrl) return
        baseUrl = url
        api = Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TelegramApi::class.java)
    }

    private fun requireApi(): TelegramApi {
        return api ?: throw IllegalStateException("TelegramApiClient not configured. Call configure(botToken) first.")
    }

    suspend fun sendVoice(chatId: String, voiceFilePath: String): Result<TelegramMessage> {
        return try {
            val api = requireApi()
            // TODO: Build MultipartBody.Part from file
            Result.failure(NotImplementedError("sendVoice not yet implemented"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUpdates(offset: Long? = null): Result<List<TelegramUpdate>> {
        return try {
            val response = requireApi().getUpdates(offset = offset)
            if (response.ok) {
                Result.success(response.result ?: emptyList())
            } else {
                Result.failure(Exception("Telegram API error: ${response.description}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFileUrl(fileId: String, botToken: String): Result<String> {
        return try {
            val response = requireApi().getFile(fileId)
            if (response.ok && response.result?.file_path != null) {
                Result.success("https://api.telegram.org/file/bot$botToken/${response.result.file_path}")
            } else {
                Result.failure(Exception("Failed to get file path"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
