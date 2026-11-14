package ru.portfolio.watch

/** Avoid duplicate network scans when WorkManager and AlarmManager fire near each other. */
object BackgroundPolicy {
    fun skipRecentSuccess(nowMs: Long, lastSuccessMs: Long, intervalMinutes: Long): Boolean {
        if (lastSuccessMs <= 0L || nowMs < lastSuccessMs) return false
        val intervalMs = intervalMinutes.coerceIn(15L, 10080L) * 60000L
        val threshold = (intervalMs - 120000L).coerceAtMost(13L * 60000L)
        return nowMs - lastSuccessMs < threshold
    }
}
