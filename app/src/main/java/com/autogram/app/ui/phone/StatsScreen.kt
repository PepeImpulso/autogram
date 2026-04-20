package com.autogram.app.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autogram.app.R
import com.autogram.app.domain.usecase.DailyCount
import com.autogram.app.domain.usecase.Stats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx.getString(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val s = stats
            if (s == null || (s.totalSent == 0 && s.totalReceived == 0 && s.totalFailed == 0)) {
                Text(ctx.getString(R.string.stats_no_data))
            } else {
                StatsSummary(s)
                Spacer(Modifier.height(8.dp))
                Text(
                    ctx.getString(R.string.stats_last_7_days),
                    style = MaterialTheme.typography.titleMedium
                )
                BarChart(s.last7Days)
            }
        }
    }
}

@Composable
private fun StatsSummary(s: Stats) {
    val ctx = LocalContext.current
    val cards = listOf(
        ctx.getString(R.string.stats_total_sent) to s.totalSent.toString(),
        ctx.getString(R.string.stats_total_received) to s.totalReceived.toString(),
        ctx.getString(R.string.stats_success_rate) to "${(s.successRate * 100).toInt()}%",
        ctx.getString(R.string.stats_avg_duration) to formatDuration(s.avgDurationMs)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    StatCard(label, value, modifier = Modifier.weight(1f))
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BarChart(days: List<DailyCount>) {
    val maxValue = (days.maxOfOrNull { it.sent + it.received } ?: 1).coerceAtLeast(1)
    val chartHeight = 140.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight + 36.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { day ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                val sentFraction = day.sent.toFloat() / maxValue
                val receivedFraction = day.received.toFloat() / maxValue
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.height(chartHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(chartHeight * receivedFraction)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(chartHeight * sentFraction)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(day.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(MaterialTheme.colorScheme.primary, "Enviados")
        LegendDot(MaterialTheme.colorScheme.secondary, "Recibidos")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val seconds = ms / 1000
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}
