package com.typiai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typiai.domain.HistoryEntry
import com.typiai.repository.TypiRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val isLoading: Boolean = true,
    val showClearDialog: Boolean = false,
    val clearSuccess: Boolean = false,
    val filterCommand: String = "", // empty = show all
    val searchQuery: String = ""
) {
    val filteredEntries: List<HistoryEntry>
        get() {
            var result = entries
            if (filterCommand.isNotBlank()) {
                result = result.filter { it.command == filterCommand }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                result = result.filter {
                    it.inputText.lowercase().contains(q) ||
                    it.outputText.lowercase().contains(q) ||
                    it.command.lowercase().contains(q)
                }
            }
            return result
        }

    val availableCommands: List<String>
        get() = entries.map { it.command }.distinct().sorted()
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TypiRepository(application)

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            repository.historyFlow.collect { entries ->
                _uiState.update { it.copy(entries = entries, isLoading = false) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilterCommand(command: String) {
        _uiState.update { it.copy(filterCommand = command) }
    }

    fun showClearDialog() {
        _uiState.update { it.copy(showClearDialog = true) }
    }

    fun dismissClearDialog() {
        _uiState.update { it.copy(showClearDialog = false) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _uiState.update { it.copy(showClearDialog = false, clearSuccess = true) }
        }
    }

    fun resetClearSuccess() {
        _uiState.update { it.copy(clearSuccess = false) }
    }
}
