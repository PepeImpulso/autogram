package com.autogram.app.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autogram.app.R
import com.autogram.app.domain.usecase.DailyCount
import com.autogram.app.domain.usecase.Stats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.stats_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        val s = stats
        val empty = s == null || (s.totalSent == 0 && s.totalReceived == 0 && s.totalFailed == 0)
        if (empty) {
            StatsEmptyState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            s?.let {
                StatsSummaryGrid(it)
                ChartSection(it.last7Days)
            }
        }
    }
}

@Composable
private fun StatsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.stats_empty_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun StatsSummaryGrid(s: Stats) {
    val sent = s.totalSent
    val received = s.totalReceived
    val successRate = (s.successRate * 100).toInt()
    val avg = formatDuration(s.avgDurationMs)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                label = stringResource(R.string.stats_total_sent),
                value = sent.toString(),
                icon = Icons.AutoMirrored.Filled.CallMade,
                accent = MaterialTheme.colorScheme.primaryContainer,
                accentOn = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.stats_total_received),
                value = received.toString(),
                icon = Icons.AutoMirrored.Filled.CallReceived,
                accent = MaterialTheme.colorScheme.secondaryContainer,
                accentOn = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                label = stringResource(R.string.stats_success_rate),
                value = "$successRate%",
                icon = Icons.Filled.CheckCircle,
                accent = MaterialTheme.colorScheme.tertiaryContainer,
                accentOn = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.stats_avg_duration),
                value = avg,
                icon = Icons.Filled.Timer,
                accent = MaterialTheme.colorScheme.surfaceVariant,
                accentOn = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    accentOn: Color,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Surface(
                shape = CircleShape,
                color = accent,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accentOn,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartSection(days: List<DailyCount>) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        stringResource(R.string.stats_last_7_days),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.stats_last7_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            BarChart(days)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(MaterialTheme.colorScheme.primary, stringResource(R.string.stats_legend_sent))
                LegendDot(MaterialTheme.colorScheme.secondary, stringResource(R.string.stats_legend_received))
            }
        }
    }
}

@Composable
private fun BarChart(days: List<DailyCount>) {
    val maxValue = (days.maxOfOrNull { it.sent + it.received } ?: 1).coerceAtLeast(1)
    val chartHeight = 120.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight + 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { day ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                val sentFraction = day.sent.toFloat() / maxValue
                val receivedFraction = day.received.toFloat() / maxValue
                // Rounded-top stacked bar. Keeps total-relative proportion so
                // the reader can compare days at a glance even when one
                // category dominates.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.height(chartHeight)
                ) {
                    if (receivedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(chartHeight * receivedFraction)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 6.dp,
                                        topEnd = 6.dp,
                                        bottomStart = if (sentFraction > 0f) 0.dp else 3.dp,
                                        bottomEnd = if (sentFraction > 0f) 0.dp else 3.dp
                                    )
                                )
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                    }
                    if (sentFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(chartHeight * sentFraction)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = if (receivedFraction > 0f) 0.dp else 6.dp,
                                        topEnd = if (receivedFraction > 0f) 0.dp else 6.dp,
                                        bottomStart = 3.dp,
                                        bottomEnd = 3.dp
                                    )
                                )
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
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
