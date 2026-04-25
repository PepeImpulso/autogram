package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Actividad de historial de mensajes.
 */
@AndroidEntryPoint
class HistoryActivity : BaseLocaleActivity() {
    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                DebugScreenshotOverlay {
                    HistoryScreen(viewModel = viewModel, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        /**
         * Optional intent extra: open History scoped to a specific
         * conversation instead of the currently-active target. Used by
         * the per-circle pending badges on the Home screen
         * (v1.20.14) so the user can tap on the "1" next to a circle
         * and land on that circle's message history.
         */
        const val EXTRA_CONVERSATION_ID: String = "conversationId"
    }
}
