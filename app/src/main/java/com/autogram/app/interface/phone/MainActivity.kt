package com.autogram.app.interface.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.interface.theme.AutogramTheme
import com.autogram.app.service.AutogramPollingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Actividad principal de la aplicación en el teléfono.
 * Si no hay conversación configurada, redirige a OnboardingActivity.
 * Si la hay, arranca el servicio de polling y muestra el menú principal.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var conversationRepository: ConversationRepository

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val active = conversationRepository.getActive()
            if (active == null || active.botToken.isBlank() || active.targetChatId.isBlank()) {
                val i = Intent(this@MainActivity, OnboardingActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                startActivity(i)
                finish()
                return@launch
            }
            startPollingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainMenu()
                }
            }
        }
    }

    @Composable
    private fun MainMenu() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Autogram", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Voice messaging for Android Auto", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(32.dp))
                Button(onClick = {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }, modifier = Modifier.width(220.dp)) {
                    Text("Configuración")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
                }, modifier = Modifier.width(220.dp)) {
                    Text("Historial")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    startActivity(Intent(this@MainActivity, StatsActivity::class.java))
                }, modifier = Modifier.width(220.dp)) {
                    Text("Estadísticas")
                }
            }
        }
    }

    private fun startPollingService() {
        val serviceIntent = Intent(this, AutogramPollingService::class.java).apply {
            action = AutogramPollingService.ACTION_START
        }
        // BUG-15: Use ContextCompat for safer foreground service start on Android 12+
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error starting foreground service")
        }
    }
}
