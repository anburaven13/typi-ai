package com.typiai.domain

sealed class GeminiResult {
    data class Success(val text: String, val responseTimeMs: Long = 0) : GeminiResult()
    data class Error(val message: String, val isRetryable: Boolean = false) : GeminiResult()
    object Loading : GeminiResult()
}
