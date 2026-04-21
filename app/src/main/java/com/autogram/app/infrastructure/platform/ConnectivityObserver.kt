package com.autogram.app.infrastructure.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable de conectividad. Emite `true` cuando la red activa tiene capacidad
 * `NET_CAPABILITY_VALIDATED` + `INTERNET` (ya validada por el sistema como que
 * sí sale a la WAN, no sólo "hay un AP").
 *
 * Se comparte como `StateFlow` para que cualquier consumidor obtenga el último
 * estado al suscribirse sin esperar a que cambie.
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val online: StateFlow<Boolean> = connectivityFlow()
        .distinctUntilChanged()
        .stateIn(
            scope,
            started = SharingStarted.Eagerly,
            initialValue = currentSnapshot()
        )

    fun isOnline(): Boolean = online.value

    private fun connectivityFlow(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Arrancar con el estado actual para que no se pierda un usuario que suscribe
        // después de que la red cambiara.
        trySend(currentSnapshot())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(hasValidatedInternet(cm, network))
            }

            override fun onLost(network: Network) {
                // Puede quedar otra red activa — re-evaluamos el estado global.
                trySend(currentSnapshot())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)

        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    private fun currentSnapshot(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        return hasValidatedInternet(cm, active)
    }

    private fun hasValidatedInternet(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
