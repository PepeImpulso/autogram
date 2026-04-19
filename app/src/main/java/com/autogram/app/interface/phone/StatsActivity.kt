package com.autogram.app.interface.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autogram.app.interface.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class StatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                StatsScreen(onBack = { finish() })
            }
        }
    }
}
