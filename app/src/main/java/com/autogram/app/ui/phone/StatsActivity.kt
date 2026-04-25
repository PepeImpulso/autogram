package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class StatsActivity : BaseLocaleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                DebugScreenshotOverlay {
                    StatsScreen(onBack = { finish() })
                }
            }
        }
    }
}
