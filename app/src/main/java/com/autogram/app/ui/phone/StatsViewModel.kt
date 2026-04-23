package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.usecase.GetStatsUseCase
import com.autogram.app.domain.usecase.Stats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val getStatsUseCase: GetStatsUseCase
) : ViewModel() {

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    init {
        refresh()
    }

    /**
     * Recompute over the full message history. Moved OFF the Flow-based
     * `observe()` because the underlying
     * `observeAllAcrossConversations()` is now LIMIT 500 for UI
     * latency — observing it would silently cap lifetime counts at
     * the last 500 messages. StatsViewModel instead fires a one-shot
     * snapshot on open + explicit refresh, which is the natural
     * UX for a stats screen anyway (users don't expect live updates).
     */
    fun refresh() {
        viewModelScope.launch {
            _stats.value = getStatsUseCase.getLifetimeStats()
        }
    }
}
