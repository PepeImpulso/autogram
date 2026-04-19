package com.autogram.app.interface.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autogram.app.interface.theme.AutogramTheme
import com.autogram.app.service.AutogramPollingService
import dagger.hilt.android.AndroidEntryPoint

/**
 * Actividad principal de la aplicación en el teléfono.
 * Muestra acceso a configuración, historial y control del servicio de polling.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Iniciar el servicio de polling
        startPollingService()

        setContent {
            AutogramTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Autogram", style = MaterialTheme.typography.headlineLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Voice messaging for Android Auto", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(onClick = {
                                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            }) {
                                Text("Configuración")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
                            }) {
                                Text("Historial")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startPollingService() {
        val serviceIntent = Intent(this, AutogramPollingService::class.java).apply {
            action = AutogramPollingService.ACTION_START
        }
        startForegroundService(serviceIntent)
    }
}
