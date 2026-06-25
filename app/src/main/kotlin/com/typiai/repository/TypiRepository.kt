package com.typiai.repository

import android.content.Context
import com.typiai.ai.GeminiHelper
import com.typiai.data.PreferencesDataStore
import com.typiai.domain.GeminiResult
import com.typiai.domain.TriggerCommand
import com.typiai.domain.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class TypiRepository(private val context: Context) {

    private val dataStore = PreferencesDataStore(context)
    private var geminiHelper: GeminiHelper? = null

    val apiKeyFlow: Flow<String> = dataStore.apiKeyFlow
    val usageStatsFlow: Flow<UsageStats> = dataStore.usageStatsFlow
    val darkModeFlow: Flow<Boolean> = dataStore.darkModeFlow
    val dynamicColorFlow: Flow<Boolean> = dataStore.dynamicColorFlow

    private suspend fun getHelper(): GeminiHelper? {
        val apiKey = dataStore.apiKeyFlow.first()
        if (apiKey.isBlank()) return null
        if (geminiHelper == null || needsReinit(apiKey)) {
            geminiHelper = GeminiHelper(apiKey)
        }
        return geminiHelper
    }

    private var lastApiKey: String = ""
    private fun needsReinit(apiKey: String): Boolean {
        return if (lastApiKey != apiKey) {
            lastApiKey = apiKey
            true
        } else false
    }

    suspend fun processText(text: String, command: TriggerCommand): GeminiResult {
        val helper = getHelper()
            ?: return GeminiResult.Error(
                "No API key configured. Please add your Gemini API key in the Dashboard.",
                isRetryable = false
            )

        val result = helper.processText(text, command)
        val success = result is GeminiResult.Success
        val responseTime = if (result is GeminiResult.Success) result.responseTimeMs else 0L
        dataStore.recordRequest(
            success = success,
            command = command.trigger,
            responseTimeMs = responseTime
        )
        return result
    }

    suspend fun saveApiKey(apiKey: String) {
        dataStore.saveApiKey(apiKey)
        geminiHelper = null // Force re-init
        lastApiKey = ""
    }

    suspend fun testApiKey(apiKey: String): GeminiResult {
        val helper = GeminiHelper(apiKey)
        return helper.testConnection()
    }

    suspend fun setDarkMode(enabled: Boolean) = dataStore.setDarkMode(enabled)
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.setDynamicColor(enabled)
    suspend fun clearUsageData() = dataStore.clearAllData()

    fun getCurrentModel(): String = geminiHelper?.getCurrentModel() ?: "gemini-2.5-flash"
}
