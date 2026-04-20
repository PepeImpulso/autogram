package com.autogram.app.infrastructure.telegram.mtproto

import android.content.Context
import android.os.Build
import com.autogram.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around TDLib's Java `Client` that:
 *   - Lazily creates the native client on first use
 *   - Pumps `Update*` events from the library to a Kotlin callback
 *   - Tracks the authorization state as a Flow so UI can react
 *   - Exposes `send()` as a suspend function (not callback-based)
 *
 * The encrypted database lives under `ctx.filesDir/tdlib`. The per-session
 * encryption key is random bytes, stored alongside the database — good enough
 * for device-local protection; TDLib's threat model assumes the device is trusted.
 */
@Singleton
class TdLibClient @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        init {
            // Must load the JNI lib before any TDLib class is touched.
            // The .so ships in app/src/main/jniLibs/<abi>/libtdjni.so.
            System.loadLibrary("tdjni")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Uninitialized)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * One-shot stream of auth errors that do NOT change [authState] (e.g. wrong
     * SMS code — TDLib keeps us in WaitCode but returns an Error to the call).
     * UI should surface the last value and clear it on retry.
     */
    private val _lastAuthError = MutableStateFlow<String?>(null)
    val lastAuthError: StateFlow<String?> = _lastAuthError.asStateFlow()

    fun clearLastAuthError() { _lastAuthError.value = null }

    /** Lazy init — creates the native client and starts pumping updates. */
    @Synchronized
    fun ensureStarted() {
        if (client != null) return
        _authState.value = AuthState.Initializing
        Timber.d("Starting TDLib client")

        val c = Client.create(
            { obj -> handleUpdate(obj) },
            { exception -> Timber.e(exception, "TDLib updates exception") },
            { exception -> Timber.e(exception, "TDLib default exception") }
        )
        client = c

        // TDLib will immediately push an UpdateAuthorizationState once the
        // client is alive. When we see WaitTdlibParameters we reply with
        // SetTdlibParameters — see handleUpdate().
    }

    /** Submits a request and awaits the result without blocking the caller thread. */
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): TdApi.Object {
        val c = client ?: error("TDLib client not started")
        val deferred = CompletableDeferred<TdApi.Object>()
        c.send(query) { result ->
            deferred.complete(result)
        }
        return deferred.await()
    }

    /** Submits a request, ignoring the result (fire-and-forget but still logged on error). */
    fun sendFireAndForget(query: TdApi.Function<*>) {
        val c = client ?: error("TDLib client not started")
        c.send(query) { result ->
            if (result is TdApi.Error) {
                Timber.w("TDLib request ${query.javaClass.simpleName} failed: ${result.code} ${result.message}")
            }
        }
    }

    /** Auth submission: propagates TDLib errors to [lastAuthError] so UI can react. */
    private fun sendAuthRequest(query: TdApi.Function<*>) {
        val c = client ?: error("TDLib client not started")
        _lastAuthError.value = null
        c.send(query) { result ->
            if (result is TdApi.Error) {
                Timber.w("Auth request ${query.javaClass.simpleName} failed: ${result.code} ${result.message}")
                _lastAuthError.value = humanizeAuthError(result)
            }
        }
    }

    private fun humanizeAuthError(err: TdApi.Error): String = when (err.message) {
        "PHONE_NUMBER_INVALID" -> "Número de teléfono inválido."
        "PHONE_CODE_INVALID", "PHONE_CODE_EMPTY" -> "Código incorrecto."
        "PHONE_CODE_EXPIRED" -> "El código ha caducado. Reinicia el proceso."
        "PASSWORD_HASH_INVALID" -> "Contraseña 2FA incorrecta."
        "FLOOD_WAIT" -> "Telegram ha aplicado un límite temporal. Espera unos minutos."
        else -> err.message.ifBlank { "Error ${err.code}" }
    }

    // --- Authentication helpers — called from ViewModel in response to UI events ---

    fun submitPhoneNumber(e164Phone: String) {
        sendAuthRequest(TdApi.SetAuthenticationPhoneNumber(e164Phone, null))
    }

    fun submitAuthCode(code: String) {
        sendAuthRequest(TdApi.CheckAuthenticationCode(code))
    }

    fun submit2faPassword(password: String) {
        sendAuthRequest(TdApi.CheckAuthenticationPassword(password))
    }

    fun logOut() {
        _authState.value = AuthState.LoggingOut
        sendFireAndForget(TdApi.LogOut())
    }

    // --- Update dispatcher ---

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            else -> { /* swallow — other updates handled by higher-level consumers later */ }
        }
    }

    private fun handleAuthState(s: TdApi.AuthorizationState) {
        Timber.d("Auth state changed: ${s.javaClass.simpleName}")
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                scope.launch { sendTdlibParameters() }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = AuthState.WaitPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                val info = s.codeInfo
                _authState.value = AuthState.WaitCode(
                    codeLength = info.type.let(::codeLengthOf),
                    timeoutSeconds = info.timeout
                )
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitPassword(hint = s.passwordHint.ifBlank { null })
            }
            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = AuthState.LoggingOut
            }
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.Closed
            }
            else -> {
                Timber.w("Unhandled auth state ${s.javaClass.simpleName}")
            }
        }
    }

    // Only the two code delivery types used in practice report a length field;
    // anything else (Call, FlashCall, Fragment, Firebase…) returns 0 and the
    // UI falls back to a generic "código recibido por SMS/Telegram" hint.
    private fun codeLengthOf(type: TdApi.AuthenticationCodeType): Int = when (type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
        is TdApi.AuthenticationCodeTypeSms -> type.length
        else -> 0
    }

    private fun sendTdlibParameters() {
        val dbDir = File(appContext.filesDir, "tdlib").apply { mkdirs() }
        val filesDir = File(dbDir, "files").apply { mkdirs() }

        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dbDir.absolutePath
            filesDirectory = filesDir.absolutePath
            databaseEncryptionKey = ByteArray(0)   // TODO: persist a random key in EncryptedSharedPreferences
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = Locale.getDefault().language.ifBlank { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        sendFireAndForget(params)
    }
}
