package com.typiai.domain

data class UsageStats(
    val requestsToday: Int = 0,
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val lastRequestTime: Long = 0L,
    val lastUsedDate: String = "",
    val lastCommand: String = "",
    val averageResponseTimeMs: Long = 0L
) {
    val successRate: Float
        get() = if (totalRequests == 0) 0f
        else (successfulRequests.toFloat() / totalRequests.toFloat()) * 100f
}
