package com.autogram.app.infrastructure.telegram

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class TelegramUpdate(
    val update_id: Long,
    val message: TelegramMessage? = null
)

data class TelegramMessage(
    val message_id: Long,
    val chat: TelegramChat,
    val text: String? = null,
    val voice: TelegramVoice? = null
)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null
)

data class TelegramVoice(
    val file_id: String,
    val duration: Int,
    val mime_type: String? = null
)

data class TelegramFile(
    val file_id: String,
    val file_path: String? = null
)

data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    val error_code: Int? = null
)

interface TelegramApi {
    @Multipart
    @POST("sendVoice")
    suspend fun sendVoice(
        @Part("chat_id") chatId: RequestBody,
        @Part voice: MultipartBody.Part
    ): TelegramResponse<TelegramMessage>

    @GET("getUpdates")
    suspend fun getUpdates(
        @Query("offset") offset: Long? = null,
        @Query("timeout") timeout: Int = 30,
        @Query("allowed_updates") allowedUpdates: String = "[\"message\"]"
    ): TelegramResponse<List<TelegramUpdate>>

    @GET("getFile")
    suspend fun getFile(
        @Query("file_id") fileId: String
    ): TelegramResponse<TelegramFile>
}
