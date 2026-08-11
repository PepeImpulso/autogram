package com.autogram.app.infrastructure.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small synchronous wrapper around AndroidX CarConnection.
 *
 * CarConnection exposes a LiveData value, which is perfect for UI but awkward
 * inside AudioPlayer where routing has to be decided during play(). We keep the
 * LiveData observer warm for normal updates and fall back to the same provider
 * query AndroidX uses when the cached value is still empty.
 */
@Singleton
class CarConnectionMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val carConnection = CarConnection(appContext)

    @Volatile private var lastKnownType: Int? = null

    init {
        val attachObserver = Runnable {
            carConnection.type.value?.let { updateType(it) }
            carConnection.type.observeForever(Observer { type ->
                updateType(type ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            })
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachObserver.run()
        } else {
            Handler(Looper.getMainLooper()).post(attachObserver)
        }
    }

    fun currentType(): Int {
        val queried = if (isAutomotiveOs()) {
            CarConnection.CONNECTION_TYPE_NATIVE
        } else {
            queryProjectionType()
        }
        val resolved = resolveConnectionType(queried, lastKnownType)
        updateType(resolved)
        return resolved
    }

    fun isConnected(type: Int = currentType()): Boolean =
        type == CarConnection.CONNECTION_TYPE_NATIVE ||
            type == CarConnection.CONNECTION_TYPE_PROJECTION

    fun typeLabel(type: Int = currentType()): String = when (type) {
        CarConnection.CONNECTION_TYPE_NATIVE -> "native"
        CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
        CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "not_connected"
        else -> "unknown_$type"
    }

    private fun updateType(type: Int) {
        val previous = lastKnownType
        lastKnownType = type
        if (previous != type) {
            Timber.i("Car connection changed: ${typeLabel(type)}")
        }
    }

    private fun isAutomotiveOs(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    private fun queryProjectionType(): Int? {
        return runCatching {
            appContext.contentResolver.query(
                PROJECTION_HOST_URI,
                arrayOf(CarConnection.CAR_CONNECTION_STATE),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(CarConnection.CAR_CONNECTION_STATE)
                // An empty/malformed provider response means "unknown", not
                // "disconnected". Gearhead can briefly return an empty cursor
                // while an Android Auto beta host reconnects; preserving the
                // LiveData cache prevents that blip from selecting a muted BT
                // A2DP route during an otherwise active projection session.
                if (idx < 0 || !cursor.moveToFirst()) null else cursor.getInt(idx)
            }
        }.onFailure {
            Timber.d(it, "CarConnection provider query failed")
        }.getOrNull()
    }

    companion object {
        private val PROJECTION_HOST_URI: Uri by lazy {
            Uri.Builder()
                .scheme("content")
                .authority("androidx.car.app.connection")
                .build()
        }

        internal fun resolveConnectionType(queried: Int?, cached: Int?): Int =
            queried ?: cached ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    }
}
