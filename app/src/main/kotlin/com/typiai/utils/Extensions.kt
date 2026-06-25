package com.typiai.utils

import java.text.SimpleDateFormat
import java.util.*

fun Long.toFormattedTime(): String {
    if (this == 0L) return "Never"
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(this))
}

fun Long.toRelativeTime(): String {
    if (this == 0L) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(this))
        }
    }
}

fun String.truncate(maxLength: Int = 50): String {
    return if (length <= maxLength) this else "${take(maxLength)}..."
}
