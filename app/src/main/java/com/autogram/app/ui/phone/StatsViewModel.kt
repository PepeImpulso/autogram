package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.usecase.GetStatsUseCase
import com.autogram.app.domain.usecase.Stats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    getStatsUseCase: GetStatsUseCase
) : ViewModel() {

    val stats: StateFlow<Stats?> = getStatsUseCase.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
