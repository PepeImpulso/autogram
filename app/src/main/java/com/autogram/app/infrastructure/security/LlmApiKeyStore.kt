package com.autogram.app.infrastructure.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystore-backed storage for the LLM API key. Per audit issue #70: the
 * key was previously persisted as plaintext in `settings.summaryLlmApiKey`,
 * exposing it to forensic extraction, rooted devices and adb-backup
 * scenarios. We move it to [EncryptedSharedPreferences] (AES-256-GCM,
 * key wrapped by Android Keystore) and leave the Room column as a
 * migration breadcrumb that gets blanked the first time a value is read.
 *
 * The same MasterKey-builder pattern is used by `TdLibClient` for the
 * TDLib database key, so this is a drop-in extension of the existing
 * security posture rather than new infrastructure.
 *
 * Failure modes are non-fatal: if EncryptedSharedPreferences fails to
 * initialise (very rare — corrupt keystore on a wiped device), reads
 * return empty and writes are dropped. The summarisation feature
 * degrades to "no key configured" rather than crashing the app.
 */
@Singleton
class LlmApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        cached?.let { return it }
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            Timber.w(it, "LlmApiKeyStore: EncryptedSharedPreferences init failed")
        }.getOrNull()?.also { cached = it }
    }

    fun get(): String = prefs()?.getString(KEY_API, "").orEmpty()

    fun set(value: String) {
        val p = prefs() ?: return
        if (value.isEmpty()) p.edit().remove(KEY_API).apply()
        else p.edit().putString(KEY_API, value).apply()
    }

    fun clear() {
        prefs()?.edit()?.clear()?.commit()
        cached = null
        context.deleteSharedPreferences(FILE_NAME)
    }

    /** True when the encrypted store currently holds a non-empty value. */
    fun isPopulated(): Boolean = get().isNotEmpty()

    companion object {
        private const val FILE_NAME = "autogram_llm_secure"
        private const val KEY_API = "summary_llm_api_key"
    }
}
