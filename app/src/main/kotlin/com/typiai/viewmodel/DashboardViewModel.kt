package com.typiai.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typiai.domain.GeminiResult
import com.typiai.domain.TriggerCommand
import com.typiai.repository.TypiRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DashboardUiState(
    val apiKey: String = "",
    val isServiceEnabled: Boolean = false,
    val playgroundInput: String = "",
    val playgroundOutput: String = "",
    val playgroundLoading: Boolean = false,
    val playgroundError: String? = null,
    val selectedCommand: TriggerCommand = TriggerCommand.TYPI,
    val apiKeyInput: String = "",
    val isApiKeyVisible: Boolean = false,
    val isSavingApiKey: Boolean = false,
    val apiKeySaveSuccess: Boolean = false,
    val apiKeyTestResult: String? = null,
    val isTestingApiKey: Boolean = false,
    val snackbarMessage: String? = null,
    val currentModel: String = "gemini-2.5-flash"
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TypiRepository(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeApiKey()
    }

    private fun observeApiKey() {
        viewModelScope.launch {
            repository.apiKeyFlow.collect { apiKey ->
                _uiState.update { state ->
                    state.copy(
                        apiKey = apiKey,
                        apiKeyInput = if (state.apiKeyInput.isEmpty() && apiKey.isNotEmpty()) {
                            maskApiKey(apiKey)
                        } else state.apiKeyInput
                    )
                }
            }
        }
    }

    fun updateServiceStatus(context: Context) {
        val enabled = isAccessibilityServiceEnabled(context)
        _uiState.update { it.copy(isServiceEnabled = enabled) }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/${com.typiai.accessibility.TypiAccessibilityService::class.java.canonicalName}"
        val accessibilityEnabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Exception) { 0 }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            return settingValue.split(':').any { it.equals(serviceName, ignoreCase = true) }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun updateApiKeyInput(key: String) {
        _uiState.update { it.copy(apiKeyInput = key, apiKeyTestResult = null, apiKeySaveSuccess = false) }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }
    }

    fun saveApiKey() {
        val input = _uiState.value.apiKeyInput.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "Please enter a valid API key") }
            return
        }
        // Don't save if it's the masked version
        if (input.contains("•")) {
            _uiState.update { it.copy(snackbarMessage = "API key unchanged") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingApiKey = true) }
            repository.saveApiKey(input)
            _uiState.update {
                it.copy(
                    isSavingApiKey = false,
                    apiKeySaveSuccess = true,
                    snackbarMessage = "API key saved successfully",
                    currentModel = repository.getCurrentModel()
                )
            }
        }
    }

    fun testApiKey() {
        val input = _uiState.value.apiKeyInput.trim()
        if (input.isBlank() || input.contains("•")) {
            _uiState.update { it.copy(snackbarMessage = "Enter your API key first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingApiKey = true, apiKeyTestResult = null) }
            val result = repository.testApiKey(input)
            val message = when (result) {
                is GeminiResult.Success -> "✓ API key is valid! Model: ${result.text.take(20)}..."
                is GeminiResult.Error -> "✗ ${result.message}"
                else -> ""
            }
            _uiState.update { it.copy(isTestingApiKey = false, apiKeyTestResult = message) }
        }
    }

    fun updatePlaygroundInput(text: String) {
        _uiState.update { it.copy(playgroundInput = text, playgroundError = null) }
    }

    fun selectCommand(command: TriggerCommand) {
        _uiState.update { it.copy(selectedCommand = command) }
    }

    fun runPlayground() {
        val state = _uiState.value
        if (state.playgroundInput.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "Please enter some text first") }
            return
        }
        if (state.apiKey.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "Please configure your API key first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(playgroundLoading = true, playgroundError = null, playgroundOutput = "") }
            val result = repository.processText(state.playgroundInput, state.selectedCommand)
            when (result) {
                is GeminiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            playgroundLoading = false,
                            playgroundOutput = result.text,
                            currentModel = repository.getCurrentModel()
                        )
                    }
                }
                is GeminiResult.Error -> {
                    _uiState.update {
                        it.copy(playgroundLoading = false, playgroundError = result.message)
                    }
                }
                else -> {}
            }
        }
    }

    fun clearPlayground() {
        _uiState.update { it.copy(playgroundInput = "", playgroundOutput = "", playgroundError = null) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun maskApiKey(key: String): String {
        return if (key.length > 8) {
            "${key.take(4)}${"•".repeat(key.length - 8)}${key.takeLast(4)}"
        } else "•".repeat(key.length)
    }
}
