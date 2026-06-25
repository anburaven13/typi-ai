package com.typiai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typiai.domain.UsageStats
import com.typiai.repository.TypiRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UsageUiState(
    val stats: UsageStats = UsageStats(),
    val isLoading: Boolean = false,
    val showClearDialog: Boolean = false,
    val clearSuccess: Boolean = false
)

class UsageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TypiRepository(application)

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            repository.usageStatsFlow.collect { stats ->
                _uiState.update { it.copy(stats = stats, isLoading = false) }
            }
        }
    }

    fun showClearDialog() {
        _uiState.update { it.copy(showClearDialog = true) }
    }

    fun dismissClearDialog() {
        _uiState.update { it.copy(showClearDialog = false) }
    }

    fun clearUsageData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showClearDialog = false) }
            repository.clearUsageData()
            _uiState.update { it.copy(isLoading = false, clearSuccess = true) }
        }
    }

    fun resetClearSuccess() {
        _uiState.update { it.copy(clearSuccess = false) }
    }
}
