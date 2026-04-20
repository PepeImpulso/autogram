package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TelegramLoginViewModel @Inject constructor(
    private val tdLibClient: TdLibClient
) : ViewModel() {

    data class UiState(
        val phoneInput: String = "",
        val codeInput: String = "",
        val passwordInput: String = "",
        val busy: Boolean = false,
        val errorMessage: String? = null,
        val authState: AuthState = AuthState.Uninitialized
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Kick the client off. If a session already exists TDLib transitions
        // straight through to Ready and we never show any login UI.
        tdLibClient.ensureStarted()

        viewModelScope.launch {
            combine(tdLibClient.authState, tdLibClient.lastAuthError) { s, err -> s to err }
                .collectLatest { (s, err) ->
                    _ui.value = _ui.value.copy(
                        authState = s,
                        busy = false,
                        errorMessage = err ?: (s as? AuthState.Error)?.message
                    )
                }
        }
    }

    fun setPhone(v: String) { tdLibClient.clearLastAuthError(); _ui.value = _ui.value.copy(phoneInput = v) }
    fun setCode(v: String) { tdLibClient.clearLastAuthError(); _ui.value = _ui.value.copy(codeInput = v) }
    fun setPassword(v: String) { tdLibClient.clearLastAuthError(); _ui.value = _ui.value.copy(passwordInput = v) }

    fun submitPhone() {
        val phone = _ui.value.phoneInput.trim()
        if (!phone.startsWith("+") || phone.length < 8) {
            _ui.value = _ui.value.copy(errorMessage = "Número inválido. Formato internacional: +34…")
            return
        }
        _ui.value = _ui.value.copy(busy = true)
        tdLibClient.submitPhoneNumber(phone)
    }

    fun submitCode() {
        val code = _ui.value.codeInput.trim()
        if (code.isEmpty()) {
            _ui.value = _ui.value.copy(errorMessage = "Introduce el código que recibiste")
            return
        }
        _ui.value = _ui.value.copy(busy = true)
        tdLibClient.submitAuthCode(code)
    }

    fun submitPassword() {
        val pw = _ui.value.passwordInput
        if (pw.isEmpty()) {
            _ui.value = _ui.value.copy(errorMessage = "Introduce tu contraseña 2FA")
            return
        }
        _ui.value = _ui.value.copy(busy = true)
        tdLibClient.submit2faPassword(pw)
    }
}
