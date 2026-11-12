package dev.uphotosync.app.backup

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Time maths for the scheduled backup, kept free of Android types so it's unit-testable. */
object BackupSchedule {

    /**
     * The first time strictly after [from] that falls at [minutes] past midnight on one of
     * [days] (ISO 1 = Monday ... 7 = Sunday); null if no days are selected.
     */
    fun next(from: LocalDateTime, minutes: Int, days: Set<Int>): LocalDateTime? {
        if (days.isEmpty()) return null
        val time = LocalTime.of(minutes / 60 % 24, minutes % 60)
        var date: LocalDate = from.toLocalDate()
        repeat(8) {
            val candidate = date.atTime(time)
            if (candidate.isAfter(from) && candidate.dayOfWeek.value in days) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * Whether a run starting at [now] counts as the scheduled run for one of [days].
     * Android may start the job late (waiting for charging or Wi-Fi), so a run within
     * [graceHours] after the set time on a selected day still counts, even if that pushed
     * it past midnight. Later than that, the day's run is skipped.
     */
    fun isDue(now: LocalDateTime, minutes: Int, days: Set<Int>, graceHours: Long = 1): Boolean =
        (0L..1L).any { back ->
            val day = now.toLocalDate().minusDays(back)
            val target = day.atTime(LocalTime.of(minutes / 60 % 24, minutes % 60))
            day.dayOfWeek.value in days &&
                !now.isBefore(target.minusMinutes(15)) &&
                now.isBefore(target.plusHours(graceHours))
        }

    fun formatTime(minutes: Int): String =
        LocalTime.of(minutes / 60 % 24, minutes % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
