package com.autogram.app.ui.phone

import android.app.Activity
import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.R
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.platform.ScreenshotCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * v1.20.15 — wraps any Activity's Compose root with a floating screenshot
 * button visible only when Settings → Developer mode is ON. Captures the
 * current Activity window, drops it in the Pictures/Autogram gallery
 * folder, and opens the system share sheet.
 *
 * Replaces v1.20.14's per-screen integration (the in-header camera icon
 * on MainActivity), so every screen — Home, History, Settings, Stats,
 * etc. — gets the same affordance and the user can capture bugs from
 * wherever they hit them.
 */
@Composable
fun DebugScreenshotOverlay(
    viewModel: DebugOverlayViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val enabled by viewModel.developerMode.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (enabled) {
            val ctx = LocalContext.current
            FloatingActionButton(
                onClick = {
                    val activity = ctx as? Activity ?: return@FloatingActionButton
                    val uri = ScreenshotCapture.captureAndShare(activity)
                    val msgRes = if (uri != null) R.string.debug_screenshot_saved
                                 else R.string.debug_screenshot_failed
                    Toast.makeText(
                        activity,
                        activity.getString(msgRes),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    // Bottom-end above the bottom-nav strip on screens that
                    // have one. Screens without a nav bar still get a
                    // sensible inset from the system gesture area.
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
                    .size(44.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = stringResource(R.string.debug_screenshot_button_cd)
                )
            }
        }
    }
}

@HiltViewModel
class DebugOverlayViewModel @Inject constructor(
    application: Application,
    settingsRepository: SettingsRepository
) : AndroidViewModel(application) {
    val developerMode: StateFlow<Boolean> = settingsRepository.observe()
        .map { it.developerMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
