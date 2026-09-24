package jp.kenshopocket.app.domain.reminder

import java.time.*
import org.junit.Test
import org.junit.Assert.*

class PeriodRegressionTest {
    @Test fun weeklyPeriodUsesMondayEvenWhenOpenedOnThursday() {
        val rule = RepeatRule("WEEKLY", ZoneId.of("Asia/Tokyo"), LocalTime.MIDNIGHT, LocalTime.of(9, 0))
        val at = Instant.parse("2026-09-24T02:00:00Z")
        val period = ReminderLogic.calculatePeriod(at, rule)
        assertEquals(Instant.parse("2026-09-20T15:00:00Z"), period.start)
        assertEquals(Instant.parse("2026-09-27T15:00:00Z"), period.end)
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), ReminderLogic.nextRepeatingReminder(at, rule, emptyList()))
    }
    @Test fun reminderBeforeResetUsesStartBoundaryNotEarlierPeriod() {
        val rule = RepeatRule("DAILY", ZoneId.of("Asia/Tokyo"), LocalTime.of(10, 0), LocalTime.of(9, 0))
        assertEquals(Instant.parse("2026-09-24T01:00:00Z"), ReminderLogic.nextRepeatingReminder(Instant.parse("2026-09-23T23:00:00Z"), rule, emptyList()))
    }
    @Test fun dstUsesLocalDayBoundariesNotFixed24Hours() {
        val rule = RepeatRule("DAILY", ZoneId.of("America/New_York"), LocalTime.MIDNIGHT, LocalTime.of(9, 0))
        val period = ReminderLogic.calculatePeriod(Instant.parse("2026-03-08T16:00:00Z"), rule)
        assertEquals(23, Duration.between(period.start, period.end).toHours())
    }
    @Test fun equalQuietEndpointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReminderLogic.applyQuietHours(Instant.EPOCH, null, ZoneOffset.UTC, LocalTime.NOON, LocalTime.NOON) }
    }
}
