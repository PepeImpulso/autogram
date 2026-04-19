package com.autogram.app.infrastructure.telegram

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

// --- Modelos de datos de la Telegram Bot API ---

data class TelegramUpdate(
    val update_id: Long,
    val message: TelegramMessage? = null,
    // BUG-27: Handle edited messages
    val edited_message: TelegramMessage? = null
)

data class TelegramMessage(
    val message_id: Long,
    val chat: TelegramChat,
    val text: String? = null,
    val voice: TelegramVoice? = null,
    val from: TelegramUser? = null,
    val date: Long = 0
)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val first_name: String? = null,
    val username: String? = null
)

data class TelegramUser(
    val id: Long,
    val is_bot: Boolean = false,
    val first_name: String = "",
    val username: String? = null
)

data class TelegramVoice(
    val file_id: String,
    val file_unique_id: String = "",
    val duration: Int,
    val mime_type: String? = null,
    val file_size: Long? = null
)

data class TelegramFile(
    val file_id: String,
    val file_unique_id: String = "",
    val file_path: String? = null,
    val file_size: Long? = null
)

data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    val error_code: Int? = null,
    val parameters: TelegramResponseParameters? = null
)

data class TelegramResponseParameters(
    val retry_after: Int? = null,
    val migrate_to_chat_id: Long? = null
)

data class TelegramBotInfo(
    val id: Long,
    val is_bot: Boolean = true,
    val first_name: String,
    val username: String? = null
)

// --- Interfaz Retrofit para la Telegram Bot API ---

interface TelegramApi {
    @Multipart
    @POST("sendVoice")
    suspend fun sendVoice(
        @Part("chat_id") chatId: RequestBody,
        @Part voice: MultipartBody.Part
    ): TelegramResponse<TelegramMessage>

    // BUG-13: Use @Query params instead of @Multipart for text messages
    @POST("sendMessage")
    suspend fun sendMessage(
        @Query("chat_id") chatId: String,
        @Query("text") text: String
    ): TelegramResponse<TelegramMessage>

    @GET("getUpdates")
    suspend fun getUpdates(
        @Query("offset") offset: Long? = null,
        @Query("timeout") timeout: Int = 30,
        @Query("allowed_updates") allowedUpdates: String = "[\"message\",\"edited_message\"]"
    ): TelegramResponse<List<TelegramUpdate>>

    @GET("getFile")
    suspend fun getFile(
        @Query("file_id") fileId: String
    ): TelegramResponse<TelegramFile>

    @GET("getMe")
    suspend fun getMe(): TelegramResponse<TelegramBotInfo>
}
