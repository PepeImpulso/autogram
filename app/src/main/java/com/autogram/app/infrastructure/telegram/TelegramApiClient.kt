package com.autogram.app.infrastructure.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cliente completo de la Telegram Bot API.
 * Soporta envío de voz (OGG/OPUS), mensajes de texto, polling de actualizaciones
 * y descarga de archivos. Incluye manejo de errores y rate limiting.
 */
@Singleton
class TelegramApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var api: TelegramApi? = null
    private var currentBotToken: String? = null

    /**
     * Configura el cliente con el token del bot.
     * Si el token no ha cambiado, no recrea el cliente Retrofit.
     * BUG-06: Synchronized to prevent race conditions from concurrent callers.
     */
    @Synchronized
    fun configure(botToken: String) {
        if (botToken == currentBotToken && api != null) return
        currentBotToken = botToken
        val url = "https://api.telegram.org/bot$botToken/"
        api = retrofit2.Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(converterFactory())
            .build()
            .create(TelegramApi::class.java)
        Timber.d("TelegramApiClient configurado con nuevo token")
    }

    private fun converterFactory() = retrofit2.converter.gson.GsonConverterFactory.create()

    private fun requireApi(): TelegramApi {
        return api ?: throw IllegalStateException("TelegramApiClient no configurado. Llama a configure(botToken) primero.")
    }

    /**
     * Envía un archivo de audio como nota de voz a Telegram.
     * Formato esperado: OGG con códec OPUS (formato nativo de Telegram).
     */
    suspend fun sendVoice(chatId: String, voiceFilePath: String): Result<TelegramMessage> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(voiceFilePath)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("Archivo de audio no encontrado: $voiceFilePath"))
                }

                val requestBody = file.asRequestBody("audio/ogg".toMediaTypeOrNull())
                val voicePart = MultipartBody.Part.createFormData("voice", file.name, requestBody)
                val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = requireApi().sendVoice(chatId = chatIdBody, voice = voicePart)

                if (response.ok && response.result != null) {
                    Timber.d("Voz enviada correctamente. Message ID: ${response.result.message_id}")
                    Result.success(response.result)
                } else {
                    // BUG-07: Handle FLOOD_WAIT (429) with retry_after
                    val retryAfter = response.parameters?.retry_after
                    val error = if (response.error_code == 429 && retryAfter != null) {
                        "Rate limited. Reintentar en ${retryAfter}s"
                    } else {
                        "Telegram API error: ${response.description} (code: ${response.error_code})"
                    }
                    Timber.e(error)
                    Result.failure(Exception(error, TelegramRateLimitException(retryAfter)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error enviando voz a Telegram")
                Result.failure(e)
            }
        }

    /**
     * Envía un mensaje de texto a Telegram.
     */
    suspend fun sendMessage(chatId: String, text: String): Result<TelegramMessage> =
        withContext(Dispatchers.IO) {
            try {
                // BUG-13: Now uses @Query params (no RequestBody needed)
                val response = requireApi().sendMessage(chatId = chatId, text = text)

                if (response.ok && response.result != null) {
                    Timber.d("Mensaje enviado. Message ID: ${response.result.message_id}")
                    Result.success(response.result)
                } else {
                    // BUG-07: Handle FLOOD_WAIT (429) with retry_after
                    val retryAfter = response.parameters?.retry_after
                    val error = if (response.error_code == 429 && retryAfter != null) {
                        "Rate limited. Reintentar en ${retryAfter}s"
                    } else {
                        "Telegram API error: ${response.description}"
                    }
                    Timber.e(error)
                    Result.failure(Exception(error, TelegramRateLimitException(retryAfter)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error enviando mensaje a Telegram")
                Result.failure(e)
            }
        }

    /**
     * Obtiene actualizaciones del bot mediante long-polling.
     * Timeout por defecto: 30 segundos.
     */
    suspend fun getUpdates(offset: Long? = null): Result<List<TelegramUpdate>> =
        withContext(Dispatchers.IO) {
            try {
                val response = requireApi().getUpdates(offset = offset)
                if (response.ok) {
                    Result.success(response.result ?: emptyList())
                } else {
                    Result.failure(Exception("Telegram API error: ${response.description}"))
                }
            } catch (e: Exception) {
                // Ignorar timeout de long-polling (es comportamiento normal)
                if (e is java.net.SocketTimeoutException) {
                    return@withContext Result.success(emptyList())
                }
                Timber.e(e, "Error obteniendo actualizaciones")
                Result.failure(e)
            }
        }

    /**
     * Obtiene la URL de descarga de un archivo de Telegram.
     */
    suspend fun getFileUrl(fileId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val token = currentBotToken ?: return@withContext Result.failure(
                    IllegalStateException("Bot token no configurado")
                )
                val response = requireApi().getFile(fileId)
                if (response.ok && response.result?.file_path != null) {
                    Result.success("https://api.telegram.org/file/bot$token/${response.result.file_path}")
                } else {
                    Result.failure(Exception("No se pudo obtener la ruta del archivo"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error obteniendo URL del archivo")
                Result.failure(e)
            }
        }

    /**
     * Descarga un archivo de voz de Telegram al almacenamiento local.
     */
    suspend fun downloadVoiceFile(fileId: String, outputDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val urlResult = getFileUrl(fileId)
                if (urlResult.isFailure) return@withContext Result.failure(urlResult.exceptionOrNull()!!)

                val url = urlResult.getOrThrow()
                outputDir.mkdirs()
                val outputFile = File(outputDir, "${System.currentTimeMillis()}.ogg")

                val request = okhttp3.Request.Builder().url(url).build()
                // BUG-08: Use execute().body?.use{} to ensure response body is closed
                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("Error descargando: HTTP ${resp.code}"))
                    }

                    resp.body?.byteStream()?.use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                Timber.d("Archivo descargado: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                Result.success(outputFile)
            } catch (e: Exception) {
                Timber.e(e, "Error descargando archivo de voz")
                Result.failure(e)
            }
        }

    /**
     * Verifica que el token del bot es válido haciendo una petición getMe.
     */
    suspend fun testConnection(): Result<TelegramBotInfo> =
        withContext(Dispatchers.IO) {
            try {
                val response = requireApi().getMe()
                if (response.ok && response.result != null) {
                    Result.success(response.result)
                } else {
                    Result.failure(Exception("Token inválido: ${response.description}"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error verificando conexión")
                Result.failure(e)
            }
        }
}

/** BUG-07: Exception to carry retry_after info for FLOOD_WAIT responses */
class TelegramRateLimitException(val retryAfterSeconds: Int?) : Exception()
