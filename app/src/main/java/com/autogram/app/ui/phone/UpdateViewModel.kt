package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.BuildConfig
import com.autogram.app.domain.usecase.CheckForUpdatesUseCase
import com.autogram.app.domain.usecase.UpdateInfo
import com.autogram.app.infrastructure.platform.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    application: Application,
    private val checkForUpdatesUseCase: CheckForUpdatesUseCase,
    private val updateInstaller: UpdateInstaller
) : AndroidViewModel(application) {

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val latest: String) : State()
        data class Available(val info: UpdateInfo) : State()
        data class Downloading(val message: String? = null) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    init {
        viewModelScope.launch {
            updateInstaller.events().collect { ev ->
                when (ev) {
                    is UpdateInstaller.InstallEvent.Started -> _state.value = State.Downloading()
                    is UpdateInstaller.InstallEvent.Progress ->
                        _state.value = State.Downloading(ev.message)
                    is UpdateInstaller.InstallEvent.Completed -> {
                        // OS prompt takes over from here.
                        _state.value = State.Idle
                    }
                    is UpdateInstaller.InstallEvent.Failed ->
                        _state.value = State.Error(ev.reason)
                }
            }
        }
    }

    fun check(userInitiated: Boolean) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        viewModelScope.launch {
            _state.value = State.Checking
            val result = checkForUpdatesUseCase.check()
            _state.value = when {
                result.isFailure -> {
                    if (userInitiated) State.Error(result.exceptionOrNull()?.message ?: "Error de red")
                    else State.Idle
                }
                else -> {
                    val info = result.getOrThrow()
                    if (info.isUpdateAvailable) State.Available(info)
                    else if (userInitiated) State.UpToDate(info.latestVersion)
                    else State.Idle
                }
            }
        }
    }

    fun dismiss() {
        _state.value = State.Idle
    }

    fun installNow() {
        val s = _state.value
        if (s !is State.Available) return
        val url = s.info.apkUrl
        if (url.isNullOrBlank()) {
            _state.value = State.Error("La release no tiene APK adjunto")
            return
        }
        val filename = CheckForUpdatesUseCase.apkFileName(s.info.latestVersion)
        updateInstaller.downloadAndInstall(url, filename, s.info.apkSha256)
    }
}
