package com.autogram.app.infrastructure.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitor del estado de llamadas telefónicas.
 * Detecta cuando hay una llamada en curso para pausar la reproducción de audio.
 */
@Singleton
class CallStateMonitor @Inject constructor(
    private val context: Context
) {
    private val _isInCall = MutableStateFlow(false)
    val isInCall: StateFlow<Boolean> = _isInCall

    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    /**
     * Inicia la monitorización del estado de llamadas.
     * Requiere permiso READ_PHONE_STATE.
     */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Permiso READ_PHONE_STATE no concedido")
            return
        }

        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in API 31+")
            override fun onCallStateChanged(state: Int) {
                _isInCall.value = when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> true
                    TelephonyManager.CALL_STATE_RINGING -> true
                    else -> false
                }
                Timber.d("Estado de llamada: $state, isInCall=${_isInCall.value}")
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        Timber.d("CallStateMonitor iniciado")
    }

    /**
     * Detiene la monitorización.
     */
    fun stop() {
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        listener = null
        Timber.d("CallStateMonitor detenido")
    }
}
