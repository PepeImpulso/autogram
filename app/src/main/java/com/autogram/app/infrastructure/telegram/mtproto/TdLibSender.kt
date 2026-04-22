package com.autogram.app.infrastructure.telegram.mtproto

import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

/**
 * Subset of [TdLibClient] that the use case layer depends on. Pulling these
 * methods behind an interface lets unit tests mock them without dragging in
 * the JNI library load (`System.loadLibrary("tdjni")`) that happens when
 * the real TdLibClient class is initialized — JVM unit tests have no
 * libtdjni.so on the classpath.
 */
interface TdLibSender {
    val authState: StateFlow<AuthState>
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): TdApi.Object
}
