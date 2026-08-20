package com.typiai.domain

import java.text.SimpleDateFormat
import java.util.*

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val inputText: String,
    val outputText: String,
    val command: String,
    val timestamp: Long = System.currentTimeMillis(),
    val responseTimeMs: Long = 0L,
    val success: Boolean = true
) {
    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedResponseTime: String
        get() = when {
            responseTimeMs <= 0L -> ""
            responseTimeMs < 1000 -> "${responseTimeMs}ms"
            else -> "${"%.1f".format(responseTimeMs / 1000.0)}s"
        }

    companion object {
        fun toJson(entry: HistoryEntry): String {
            return buildString {
                append("{")
                append("\"id\":\"${entry.id.escapeJson()}\",")
                append("\"inputText\":\"${entry.inputText.escapeJson()}\",")
                append("\"outputText\":\"${entry.outputText.escapeJson()}\",")
                append("\"command\":\"${entry.command.escapeJson()}\",")
                append("\"timestamp\":${entry.timestamp},")
                append("\"responseTimeMs\":${entry.responseTimeMs},")
                append("\"success\":${entry.success}")
                append("}")
            }
        }

        fun fromJson(json: String): HistoryEntry? {
            return try {
                fun extractString(key: String): String {
                    val pattern = "\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?.replace("\\n", "\n")
                        ?.replace("\\t", "\t")
                        ?: ""
                }
                fun extractLong(key: String): Long {
                    val pattern = "\"$key\":(\\d+)".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                fun extractBool(key: String): Boolean {
                    val pattern = "\"$key\":(true|false)".toRegex()
                    return pattern.find(json)?.groupValues?.get(1) == "true"
                }

                val id = extractString("id").ifBlank { UUID.randomUUID().toString() }
                val inputText = extractString("inputText")
                val outputText = extractString("outputText")
                val command = extractString("command")
                val timestamp = extractLong("timestamp")
                val responseTimeMs = extractLong("responseTimeMs")
                val success = extractBool("success")

                if (inputText.isBlank() && outputText.isBlank()) null
                else HistoryEntry(id, inputText, outputText, command, timestamp, responseTimeMs, success)
            } catch (e: Exception) {
                null
            }
        }

        fun listToJson(entries: List<HistoryEntry>): String =
            "[${entries.joinToString(",") { toJson(it) }}]"

        fun listFromJson(json: String): List<HistoryEntry> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                // Simple JSON array parser — split on top-level object boundaries
                val results = mutableListOf<HistoryEntry>()
                var depth = 0
                var start = -1
                for (i in json.indices) {
                    when (json[i]) {
                        '{' -> { if (depth == 0) start = i; depth++ }
                        '}' -> {
                            depth--
                            if (depth == 0 && start >= 0) {
                                fromJson(json.substring(start, i + 1))?.let { results.add(it) }
                                start = -1
                            }
                        }
                    }
                }
                results
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun String.escapeJson(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
