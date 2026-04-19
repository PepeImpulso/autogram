package com.autogram.app.interface.phone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autogram.app.R

// Stub: filled in by Step 8 (statistics dashboard).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx.getString(R.string.stats_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()) {
            Text(ctx.getString(R.string.stats_no_data))
        }
    }
}
