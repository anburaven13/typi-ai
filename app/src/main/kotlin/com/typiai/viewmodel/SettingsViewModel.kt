package com.typiai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typiai.data.PreferencesDataStore
import com.typiai.repository.TypiRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val darkMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val debounceMs: Int = PreferencesDataStore.DEFAULT_DEBOUNCE_MS,
    val showClearHistoryDialog: Boolean = false,
    val showClearDataDialog: Boolean = false,
    val snackbarMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TypiRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.darkModeFlow,
                repository.dynamicColorFlow,
                repository.notificationsEnabledFlow,
                repository.debounceMsFlow
            ) { dark, dynamic, notif, debounce ->
                SettingsUiState(
                    darkMode = dark,
                    dynamicColor = dynamic,
                    notificationsEnabled = notif,
                    debounceMs = debounce
                )
            }.collect { state ->
                _uiState.update { current ->
                    state.copy(
                        showClearHistoryDialog = current.showClearHistoryDialog,
                        showClearDataDialog = current.showClearDataDialog,
                        snackbarMessage = current.snackbarMessage
                    )
                }
            }
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { repository.setDarkMode(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setNotificationsEnabled(enabled) }
    }

    fun setDebounceMs(ms: Int) {
        viewModelScope.launch { repository.setDebounceMs(ms) }
    }

    fun showClearHistoryDialog() {
        _uiState.update { it.copy(showClearHistoryDialog = true) }
    }

    fun dismissClearHistoryDialog() {
        _uiState.update { it.copy(showClearHistoryDialog = false) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _uiState.update { it.copy(showClearHistoryDialog = false, snackbarMessage = "History cleared") }
        }
    }

    fun showClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = true) }
    }

    fun dismissClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = false) }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearUsageData()
            _uiState.update { it.copy(showClearDataDialog = false, snackbarMessage = "All data cleared") }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
