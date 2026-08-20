package com.typiai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.typiai.domain.HistoryEntry
import com.typiai.domain.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "typiai_prefs")

class PreferencesDataStore(private val context: Context) {

    companion object {
        private val API_KEY = stringPreferencesKey("api_key_encrypted")
        private val REQUESTS_TODAY = intPreferencesKey("requests_today")
        private val TOTAL_REQUESTS = intPreferencesKey("total_requests")
        private val SUCCESSFUL_REQUESTS = intPreferencesKey("successful_requests")
        private val FAILED_REQUESTS = intPreferencesKey("failed_requests")
        private val LAST_REQUEST_TIME = longPreferencesKey("last_request_time")
        private val LAST_USED_DATE = stringPreferencesKey("last_used_date")
        private val LAST_COMMAND = stringPreferencesKey("last_command")
        private val TOTAL_RESPONSE_TIME = longPreferencesKey("total_response_time")
        private val DARK_MODE = booleanPreferencesKey("dark_mode")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val DEBOUNCE_MS = intPreferencesKey("debounce_ms")
        // History stored as JSON array string
        private val HISTORY_JSON = stringPreferencesKey("history_json")
        // Per-command counters: "cmd_count_fix", "cmd_count_emoji", etc.
        private fun cmdCountKey(trigger: String) =
            intPreferencesKey("cmd_count_${trigger.removePrefix("@")}")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        const val DEFAULT_DEBOUNCE_MS = 650
        const val MAX_HISTORY_ENTRIES = 50
    }

    // ── Obfuscation ───────────────────────────────────────────────────────────
    private fun obfuscateKey(key: String): String {
        if (key.isBlank()) return ""
        return key.reversed().map { it + 3 }.joinToString("") { it.toChar().toString() }
    }

    private fun deobfuscateKey(key: String): String {
        if (key.isBlank()) return ""
        return try {
            key.map { it - 3 }.joinToString("") { it.toChar().toString() }.reversed()
        } catch (e: Exception) { "" }
    }

    // ── Flows ─────────────────────────────────────────────────────────────────
    val apiKeyFlow: Flow<String> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val stored = prefs[API_KEY] ?: ""
            if (stored.isNotBlank()) deobfuscateKey(stored) else ""
        }

    val usageStatsFlow: Flow<UsageStats> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val today = DATE_FORMAT.format(Date())
            val lastDate = prefs[LAST_USED_DATE] ?: ""
            val requestsToday = if (lastDate == today) prefs[REQUESTS_TODAY] ?: 0 else 0
            UsageStats(
                requestsToday = requestsToday,
                totalRequests = prefs[TOTAL_REQUESTS] ?: 0,
                successfulRequests = prefs[SUCCESSFUL_REQUESTS] ?: 0,
                failedRequests = prefs[FAILED_REQUESTS] ?: 0,
                lastRequestTime = prefs[LAST_REQUEST_TIME] ?: 0L,
                lastUsedDate = lastDate,
                lastCommand = prefs[LAST_COMMAND] ?: "",
                totalResponseTimeMs = prefs[TOTAL_RESPONSE_TIME] ?: 0L
            )
        }

    val darkModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[DARK_MODE] ?: false }

    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[DYNAMIC_COLOR] ?: true }

    val notificationsEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[NOTIFICATIONS_ENABLED] ?: true }

    val debounceMsFlow: Flow<Int> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[DEBOUNCE_MS] ?: DEFAULT_DEBOUNCE_MS }

    val historyFlow: Flow<List<HistoryEntry>> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val json = prefs[HISTORY_JSON] ?: "[]"
            HistoryEntry.listFromJson(json)
        }

    // Per-command usage map: trigger -> count
    fun commandUsageFlow(trigger: String): Flow<Int> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[cmdCountKey(trigger)] ?: 0 }

    // ── Writes ────────────────────────────────────────────────────────────────
    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = if (apiKey.isNotBlank()) obfuscateKey(apiKey) else ""
        }
    }

    suspend fun recordRequest(
        success: Boolean,
        command: String,
        responseTimeMs: Long = 0,
        inputText: String = "",
        outputText: String = ""
    ) {
        val today = DATE_FORMAT.format(Date())
        context.dataStore.edit { prefs ->
            val lastDate = prefs[LAST_USED_DATE] ?: ""
            val currentToday = if (lastDate == today) prefs[REQUESTS_TODAY] ?: 0 else 0
            prefs[REQUESTS_TODAY] = currentToday + 1
            prefs[TOTAL_REQUESTS] = (prefs[TOTAL_REQUESTS] ?: 0) + 1
            if (success) {
                prefs[SUCCESSFUL_REQUESTS] = (prefs[SUCCESSFUL_REQUESTS] ?: 0) + 1
            } else {
                prefs[FAILED_REQUESTS] = (prefs[FAILED_REQUESTS] ?: 0) + 1
            }
            prefs[LAST_REQUEST_TIME] = System.currentTimeMillis()
            prefs[LAST_USED_DATE] = today
            prefs[LAST_COMMAND] = command
            prefs[TOTAL_RESPONSE_TIME] = (prefs[TOTAL_RESPONSE_TIME] ?: 0) + responseTimeMs

            // Per-command count
            val cmdKey = cmdCountKey(command)
            prefs[cmdKey] = (prefs[cmdKey] ?: 0) + 1

            // History
            if (inputText.isNotBlank() || outputText.isNotBlank()) {
                val existing = HistoryEntry.listFromJson(prefs[HISTORY_JSON] ?: "[]").toMutableList()
                val entry = HistoryEntry(
                    inputText = inputText.take(500),
                    outputText = outputText.take(1000),
                    command = command,
                    responseTimeMs = responseTimeMs,
                    success = success
                )
                existing.add(0, entry) // newest first
                // Keep only the last MAX_HISTORY_ENTRIES
                val trimmed = if (existing.size > MAX_HISTORY_ENTRIES) {
                    existing.take(MAX_HISTORY_ENTRIES)
                } else existing
                prefs[HISTORY_JSON] = HistoryEntry.listToJson(trimmed)
            }
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { prefs -> prefs[HISTORY_JSON] = "[]" }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DARK_MODE] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDebounceMs(ms: Int) {
        context.dataStore.edit { prefs -> prefs[DEBOUNCE_MS] = ms.coerceIn(200, 2000) }
    }

    suspend fun clearAllData() {
        context.dataStore.edit { prefs ->
            prefs.remove(REQUESTS_TODAY)
            prefs.remove(TOTAL_REQUESTS)
            prefs.remove(SUCCESSFUL_REQUESTS)
            prefs.remove(FAILED_REQUESTS)
            prefs.remove(LAST_REQUEST_TIME)
            prefs.remove(LAST_USED_DATE)
            prefs.remove(LAST_COMMAND)
            prefs.remove(TOTAL_RESPONSE_TIME)
            prefs.remove(HISTORY_JSON)
        }
    }
}
