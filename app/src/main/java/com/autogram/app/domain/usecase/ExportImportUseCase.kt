package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exporta y restaura la configuración del usuario (círculos fijados, respuestas
 * rápidas, ajustes y fotos asociadas) a un fichero `.autogram` cifrado con
 * AES-256-GCM usando una contraseña que el usuario elige.
 *
 * Formato del fichero (binario, big-endian):
 *   - magic  : 8 bytes — "AUGBKP01" (v1, 100k PBKDF2 iters) or "AUGBKP02" (v2, 600k iters)
 *   - salt   : 16 bytes (PBKDF2)
 *   - iv     : 12 bytes (GCM)
 *   - payload: ciphertext + tag auth (16 bytes) del JSON UTF-8
 *
 * El JSON lleva versión de esquema; si se añaden campos nuevos a futuro el
 * importador los ignora silenciosamente (Gson lo maneja solo).
 *
 * Iteración count history (all KDF params are per-magic):
 *  - v1 (AUGBKP01, shipped 1.7.0): 100_000 iters — below NIST 2024 minimum (600k).
 *    Kept ONLY for importing existing backups. New exports must not use this.
 *  - v2 (AUGBKP02, shipped 1.8.1): 600_000 iters (NIST SP 800-132 compliant).
 */
@Singleton
class ExportImportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepository: ConversationRepository,
    private val quickReplyRepository: QuickReplyRepository,
    private val settingsRepository: SettingsRepository
) {

    data class BackupPayload(
        val schemaVersion: Int = 1,
        val createdAt: Long = System.currentTimeMillis(),
        val appVersion: String = "",
        val settings: AppSettings,
        val quickReplies: List<QuickReply>,
        val conversations: List<Conversation>,
        /**
         * Key = conversation.id, Value = base64 del fichero de foto.
         * Solo incluye círculos con foto local para no inflar el backup.
         */
        val photos: Map<String, String>
    )

    /**
     * Generate the encrypted backup as a byte array. Caller decides where to
     * drop it (user-picked URI via SAF, share to Telegram, etc.).
     *
     * Always emits v2 format (AUGBKP02 magic, 600k PBKDF2 iterations). The key
     * material is zeroized before return so the raw bytes don't linger in the
     * JVM heap waiting for GC.
     */
    suspend fun export(password: CharArray): ByteArray {
        val payload = BackupPayload(
            settings = settingsRepository.get(),
            quickReplies = quickReplyRepository.getAll(),
            conversations = conversationRepository.getAll(),
            photos = buildPhotosMap(conversationRepository.getAll())
        )
        val json = Gson().toJson(payload).toByteArray(Charsets.UTF_8)
        val salt = randomBytes(SALT_SIZE)
        val iv = randomBytes(IV_SIZE)
        val key = deriveKey(password, salt, KDF_ITERATIONS_V2)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val ciphertext = cipher.doFinal(json)
            val out = ByteArrayOutputStream(MAGIC_V2.size + salt.size + iv.size + ciphertext.size)
            out.write(MAGIC_V2)
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
            return out.toByteArray()
        } finally {
            // SecretKeySpec exposes encoded key material; best-effort scrub so
            // it isn't sitting in the heap waiting for GC.
            runCatching { java.util.Arrays.fill(json, 0.toByte()) }
            runCatching {
                val field = SecretKeySpec::class.java.getDeclaredField("key")
                field.isAccessible = true
                (field.get(key) as? ByteArray)?.let { java.util.Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    sealed class ImportResult {
        data class Success(val restored: BackupPayload) : ImportResult()
        object WrongPassword : ImportResult()
        object Corrupt : ImportResult()
        object UnsupportedVersion : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Decrypt + apply a backup. Writes everything into Room (upserts
     * conversations + replaces quick-replies + overwrites settings) and
     * restores photo files under `filesDir/circle_photos/`.
     *
     * Reads both v1 (AUGBKP01, 100k PBKDF2 iters — legacy) and v2 (AUGBKP02,
     * 600k iters — current). Future formats added to [supportedMagics] will
     * work automatically; unknown magics surface as UnsupportedVersion so
     * the user gets a clear error instead of a silent Corrupt.
     */
    suspend fun import(encrypted: ByteArray, password: CharArray): ImportResult {
        // Minimum valid frame: magic + salt + iv + GCM tag
        if (encrypted.size < MAGIC_SIZE + SALT_SIZE + IV_SIZE + GCM_TAG_BITS / 8) {
            return ImportResult.Corrupt
        }
        val magic = encrypted.copyOfRange(0, MAGIC_SIZE)
        val iters = supportedMagics[magic.toList()]
            ?: return if (looksLikeAugbkp(magic)) ImportResult.UnsupportedVersion
                     else ImportResult.Corrupt
        val salt = encrypted.copyOfRange(MAGIC_SIZE, MAGIC_SIZE + SALT_SIZE)
        val iv = encrypted.copyOfRange(MAGIC_SIZE + SALT_SIZE, MAGIC_SIZE + SALT_SIZE + IV_SIZE)
        val body = encrypted.copyOfRange(MAGIC_SIZE + SALT_SIZE + IV_SIZE, encrypted.size)
        val key = deriveKey(password, salt, iters)
        val plaintext: ByteArray
        try {
            plaintext = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                cipher.doFinal(body)
            } catch (e: javax.crypto.AEADBadTagException) {
                return ImportResult.WrongPassword
            } catch (e: Exception) {
                Timber.w(e, "import decrypt failed")
                return ImportResult.Corrupt
            }
        } finally {
            runCatching {
                val field = SecretKeySpec::class.java.getDeclaredField("key")
                field.isAccessible = true
                (field.get(key) as? ByteArray)?.let { java.util.Arrays.fill(it, 0.toByte()) }
            }
        }
        val payload = try {
            Gson().fromJson(String(plaintext, Charsets.UTF_8), BackupPayload::class.java)
        } catch (e: JsonParseException) {
            return ImportResult.Corrupt
        } ?: return ImportResult.Corrupt
        runCatching { java.util.Arrays.fill(plaintext, 0.toByte()) }

        // BackupPayload.schemaVersion is JSON-level (vs binary magic, which is
        // crypto params). A v2 binary with a v99 JSON still means we don't
        // know what columns the new schema added — reject explicitly.
        if (payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
            return ImportResult.UnsupportedVersion
        }

        return try {
            applyPayload(payload)
            ImportResult.Success(payload)
        } catch (e: Exception) {
            Timber.e(e, "import applyPayload failed")
            ImportResult.Error(e.message ?: "apply failed")
        }
    }

    private fun looksLikeAugbkp(magic: ByteArray): Boolean =
        magic.size == MAGIC_SIZE &&
            magic[0] == 0x41.toByte() && magic[1] == 0x55.toByte() &&
            magic[2] == 0x47.toByte() && magic[3] == 0x42.toByte()

    private suspend fun applyPayload(payload: BackupPayload) {
        // Settings — overwrite the single row.
        settingsRepository.update(payload.settings)

        // Quick replies — delete all current, insert from backup.
        val existingReplies = quickReplyRepository.getAll()
        for (r in existingReplies) quickReplyRepository.delete(r.id)
        for (r in payload.quickReplies) quickReplyRepository.upsert(r)

        // Photos — restore files first so conversations can reference valid paths.
        val photoDir = File(context.filesDir, "circle_photos").apply { mkdirs() }
        val restoredPhotoPaths = mutableMapOf<String, String>()
        for ((convId, b64) in payload.photos) {
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val dst = File(photoDir, "$convId-restored.img")
                dst.writeBytes(bytes)
                restoredPhotoPaths[convId] = dst.absolutePath
            }.onFailure { Timber.w(it, "Failed to restore photo for $convId") }
        }

        // Conversations — upsert. Rewrite photoLocalPath when restored.
        for (conv in payload.conversations) {
            val withPhoto = conv.copy(
                photoLocalPath = restoredPhotoPaths[conv.id] ?: conv.photoLocalPath
            )
            conversationRepository.upsert(withPhoto)
        }
    }

    private fun buildPhotosMap(convs: List<Conversation>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (c in convs) {
            val path = c.photoLocalPath ?: continue
            val f = File(path)
            if (!f.exists()) continue
            out[c.id] = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        }
        return out
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password, salt, iterations, 256)
        val secret = factory.generateSecret(spec)
        val keyBytes = secret.encoded
        val out = SecretKeySpec(keyBytes, "AES")
        // Caller owns zeroization; we don't own the bytes anymore.
        return out
    }

    private fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        SecureRandom().nextBytes(out)
        return out
    }

    companion object {
        // "AUGBKP01" — v1 format (100k PBKDF2 iters). Read-only: we still accept these
        // to import old backups but never write them again.
        private val MAGIC_V1 = byteArrayOf(0x41, 0x55, 0x47, 0x42, 0x4B, 0x50, 0x30, 0x31)
        // "AUGBKP02" — v2 format (600k iters, NIST SP 800-132 compliant).
        private val MAGIC_V2 = byteArrayOf(0x41, 0x55, 0x47, 0x42, 0x4B, 0x50, 0x30, 0x32)
        private const val MAGIC_SIZE = 8
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val KDF_ITERATIONS_V1 = 100_000
        private const val KDF_ITERATIONS_V2 = 600_000
        private const val BACKUP_SCHEMA_VERSION = 1

        private val supportedMagics: Map<List<Byte>, Int> = mapOf(
            MAGIC_V1.toList() to KDF_ITERATIONS_V1,
            MAGIC_V2.toList() to KDF_ITERATIONS_V2
        )
    }
}
