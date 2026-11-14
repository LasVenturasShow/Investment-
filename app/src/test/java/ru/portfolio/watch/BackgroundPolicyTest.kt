package ru.portfolio.watch

import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun skipDuplicateTriggeredByBackupScheduler() {
        val now = 10000000L
        assertTrue(BackgroundPolicy.skipRecentSuccess(now, now - 11L * 60000L, 15L))
        assertFalse(BackgroundPolicy.skipRecentSuccess(now, now - 14L * 60000L, 15L))
        assertFalse(BackgroundPolicy.skipRecentSuccess(now, 0L, 15L))
        assertFalse(BackgroundPolicy.skipRecentSuccess(now, now + 1000L, 15L))
    }

    @Test fun delaysDoNotPreventLaterAttempts() {
        val now = 20000000L
        assertFalse(BackgroundPolicy.skipRecentSuccess(now, now - 15L * 60000L, 15L))
        assertTrue(BackgroundPolicy.skipRecentSuccess(now, now - 12L * 60000L, 60L))
        assertFalse(BackgroundPolicy.skipRecentSuccess(now, now - 14L * 60000L, 60L))
    }
}
