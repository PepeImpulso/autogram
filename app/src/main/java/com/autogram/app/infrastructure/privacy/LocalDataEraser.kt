package com.autogram.app.infrastructure.privacy

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.autogram.app.infrastructure.persistence.AutogramDatabase
import com.autogram.app.infrastructure.security.LlmApiKeyStore
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.service.MtprotoReceiverService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Removes the Telegram session and every user-created local Autogram artifact. */
@Singleton
class LocalDataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AutogramDatabase,
    private val tdLibClient: TdLibClient,
    private val llmApiKeyStore: LlmApiKeyStore
) {
    suspend fun erase(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.stopService(
                Intent(context, MtprotoReceiverService::class.java).apply {
                    action = MtprotoReceiverService.ACTION_STOP
                }
            )
            WorkManager.getInstance(context).cancelAllWork()
            tdLibClient.logOutAndClearLocalSession()
            database.clearAllTables()
            llmApiKeyStore.clear()

            context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            sharedPrefsDir.listFiles { file -> file.extension == "xml" }
                ?.forEach { file -> context.deleteSharedPreferences(file.nameWithoutExtension) }

            context.getSystemService(NotificationManager::class.java)?.cancelAll()
            Timber.i("Local Autogram data erased by user request")
        }
    }
}
