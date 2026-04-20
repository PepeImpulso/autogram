package com.autogram.app.infrastructure.telegram.mtproto

sealed class AuthState {
    object Uninitialized : AuthState()
    object Initializing : AuthState()
    object WaitPhoneNumber : AuthState()

    data class WaitCode(
        val codeLength: Int,
        val timeoutSeconds: Int
    ) : AuthState()

    data class WaitPassword(
        val hint: String?
    ) : AuthState()

    object Ready : AuthState()
    object LoggingOut : AuthState()
    object Closed : AuthState()

    data class Error(val message: String, val recoverable: Boolean = true) : AuthState()
}
