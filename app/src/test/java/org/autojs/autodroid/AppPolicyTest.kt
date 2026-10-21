package org.autojs.autodroid

import org.autojs.autodroid.net.ServerAddress
import org.autojs.autodroid.schedule.ScheduleManager
import org.autojs.autodroid.schedule.ScheduledRun
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AppPolicyTest {
    @Test fun serverAddressesKeepReverseProxyPrefixAndNormalizeApiSuffix() {
        assertEquals("http://example.com/platform", ServerAddress.normalize(" http://example.com/platform/api/ "))
        assertEquals("https://example.com", ServerAddress.normalize("https://example.com/"))
        for (invalid in listOf("http", "httpx://example.com", "example.com", "https://user:password@example.com", "http://example.com/?token=secret", "http://example.com/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize(invalid) }
        }
    }

    @Test fun schedulesCannotCrossPlatformOrAccountAndLegacyTasksNeedBinding() {
        val run = ScheduledRun(serverUrl = "http://platform", username = "alice")
        assertTrue(run.belongsTo("http://platform", "alice"))
        assertFalse(run.belongsTo("http://other", "alice"))
        assertFalse(run.belongsTo("http://platform", "bob"))
        assertFalse(ScheduledRun().belongsTo("http://platform", "alice"))
    }

    @Test fun expiredOnceTasksNeverCatchUpOnBootOrReenable() {
        val now = 1_000_000L
        assertEquals(0L, ScheduleManager.computeNextRun(ScheduledRun(repeatType = "ONCE", onceAtMillis = now - 1), now))
        assertEquals(0L, ScheduleManager.computeNextRun(ScheduledRun(repeatType = "ONCE", onceAtMillis = now), now))
        assertEquals(now + 1, ScheduleManager.computeNextRun(ScheduledRun(repeatType = "ONCE", onceAtMillis = now + 1), now))
    }

    @Test fun weeklyTasksAtTheScheduledTimeAdvanceToNextWeek() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 19, 9, 0) }.timeInMillis
            val run = ScheduledRun(repeatType = "WEEKLY", weekday = 1, timeOfDayMinutes = 540)
            assertEquals(now + 7 * 24 * 60 * 60_000L, ScheduleManager.computeNextRun(run, now))
        } finally { TimeZone.setDefault(original) }
    }
}
