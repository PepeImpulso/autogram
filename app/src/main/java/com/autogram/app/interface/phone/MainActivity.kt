package com.autogram.app.interface.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.autogram.app.interface.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Voice messaging for Android Auto")
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(onClick = {
                                startActivity(
                                    android.content.Intent(
                                        this@MainActivity,
                                        SettingsActivity::class.java
                                    )
                                )
                            }) {
                                Text("Settings")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                startActivity(
                                    android.content.Intent(
                                        this@MainActivity,
                                        HistoryActivity::class.java
                                    )
                                )
                            }) {
                                Text("History")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Spacer(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier = modifier)
}
