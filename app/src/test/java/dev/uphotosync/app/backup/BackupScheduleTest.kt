package dev.uphotosync.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupScheduleTest {
    private val everyDay = (1..7).toSet()
    private val twoAm = 2 * 60

    // 2026-11-10 is a Tuesday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    @Test fun laterTodayIfTimeHasntPassed() {
        assertEquals(at(10, 2), BackupSchedule.next(at(10, 1, 30), twoAm, everyDay))
    }

    @Test fun tomorrowIfTimeHasPassed() {
        assertEquals(at(11, 2), BackupSchedule.next(at(10, 9), twoAm, everyDay))
    }

    @Test fun exactlyAtTheTimeMovesToTheNextDay() {
        assertEquals(at(11, 2), BackupSchedule.next(at(10, 2), twoAm, everyDay))
    }

    @Test fun skipsUnselectedDays() {
        // Saturday (6) only: from Tuesday the 10th, next is Saturday the 14th.
        assertEquals(at(14, 2), BackupSchedule.next(at(10, 9), twoAm, setOf(6)))
    }

    @Test fun sameWeekdayNextWeek() {
        // Tuesday only, after today's time: next Tuesday.
        assertEquals(at(17, 2), BackupSchedule.next(at(10, 3), twoAm, setOf(2)))
    }

    @Test fun noDaysMeansNoSchedule() {
        assertNull(BackupSchedule.next(at(10, 3), twoAm, emptySet()))
    }

    @Test fun dueAtAndShortlyAfterTheTime() {
        assertTrue(BackupSchedule.isDue(at(10, 2), twoAm, everyDay))
        assertTrue(BackupSchedule.isDue(at(10, 2, 45), twoAm, everyDay))
        assertTrue(BackupSchedule.isDue(at(10, 4, 30), twoAm, everyDay, graceHours = 3))
        assertTrue(BackupSchedule.isDue(at(10, 1, 50), twoAm, everyDay)) // a few minutes early is fine
    }

    @Test fun notDueOnUnselectedDayOrFarFromTheTime() {
        assertFalse(BackupSchedule.isDue(at(10, 2), twoAm, setOf(3))) // Tuesday not selected
        assertFalse(BackupSchedule.isDue(at(10, 14), twoAm, everyDay)) // well past the grace period
    }

    @Test fun lateRunPastMidnightCountsForThePreviousDay() {
        // 23:30 on Tuesday only, delayed until 01:00 Wednesday: still Tuesday's run.
        assertTrue(BackupSchedule.isDue(at(11, 1), 23 * 60 + 30, setOf(2), graceHours = 2))
    }

    @Test fun tooLateToStartIsSkipped() {
        // Set for 2:00, plugged in at 5:00: outside the default 1-hour window and a 2-hour one.
        assertFalse(BackupSchedule.isDue(at(10, 5), twoAm, everyDay))
        assertFalse(BackupSchedule.isDue(at(10, 5), twoAm, everyDay, graceHours = 2))
        assertFalse(BackupSchedule.isDue(at(10, 3), twoAm, everyDay)) // window ends at 3:00
        // But it's still recognised as that day's (missed) run, for the history note.
        assertTrue(BackupSchedule.isDue(at(10, 5), twoAm, everyDay, graceHours = 24))
    }
}
