package com.autogram.app.infrastructure.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors phone call state. Used to auto-pause playback while a call is
 * active. Degrades gracefully when READ_PHONE_STATE is denied — playback
 * continues but won't pause for incoming calls.
 */
@Singleton
class CallStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _isInCall = MutableStateFlow(false)
    val isInCall: StateFlow<Boolean> = _isInCall

    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    /**
     * Start monitoring call state. Requires READ_PHONE_STATE; if not granted
     * the monitor stays idle and isInCall stays false (graceful degradation).
     */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("READ_PHONE_STATE not granted; CallStateMonitor disabled")
            return
        }

        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // BUG-33: Use TelephonyCallback on API 31+, PhoneStateListener on older
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    _isInCall.value = state == TelephonyManager.CALL_STATE_OFFHOOK ||
                            state == TelephonyManager.CALL_STATE_RINGING
                    Timber.d("Call state: $state, isInCall=${_isInCall.value}")
                }
            }
            telephonyCallback = callback
            telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31+")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    _isInCall.value = when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> true
                        TelephonyManager.CALL_STATE_RINGING -> true
                        else -> false
                    }
                    Timber.d("Call state: $state, isInCall=${_isInCall.value}")
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        Timber.d("CallStateMonitor started")
    }

    /** Stop monitoring call state. */
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
        }
        legacyListener = null
        telephonyCallback = null
        Timber.d("CallStateMonitor stopped")
    }
}
