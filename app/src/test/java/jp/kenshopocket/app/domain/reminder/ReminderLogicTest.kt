package jp.kenshopocket.app.domain.reminder

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderLogicTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun instant(value: String) = Instant.parse(value)

    @Test fun deadlineDateOnlyPlansCalendarDays() {
        val result = ReminderLogic.planDeadlineReminders(instant("2026-09-23T02:32:00Z"), DeadlineInput("DATE_ONLY", LocalDate.parse("2026-09-27"), null, zone, true), listOf(-3,-1,0), LocalTime.parse("09:00"), emptyList())
        assertEquals(listOf("2026-09-24T00:00:00Z","2026-09-26T00:00:00Z","2026-09-27T00:00:00Z"), result.plannedAt.map(Instant::toString))
        assertEquals("TIME_UNKNOWN", result.warning)
    }

    @Test fun timedDeadlineSkipsSameDayReminderAfterCutoff() {
        val result = ReminderLogic.planDeadlineReminders(instant("2026-09-23T02:32:00Z"), DeadlineInput("DATE_TIME", LocalDate.parse("2026-09-27"), LocalTime.parse("07:00"), zone, true), listOf(-3,-1,0), LocalTime.parse("09:00"), listOf(120))
        assertEquals(listOf("2026-09-24T00:00:00Z","2026-09-26T00:00:00Z","2026-09-26T20:00:00Z"), result.plannedAt.map(Instant::toString))
    }

    @Test fun pastAndUnconfirmedDeadlinesDoNotPlan() {
        val past = ReminderLogic.planDeadlineReminders(instant("2026-09-23T02:32:00Z"), DeadlineInput("DATE_TIME", LocalDate.parse("2026-09-23"), LocalTime.parse("09:59"), zone, true), listOf(-3,-1,0), LocalTime.parse("09:00"), listOf(120))
        assertTrue(past.plannedAt.isEmpty())
        val unknown = ReminderLogic.planDeadlineReminders(Instant.EPOCH, DeadlineInput("DATE_ONLY", LocalDate.parse("2027-01-10"), null, zone, false), listOf(0), LocalTime.NOON, emptyList())
        assertEquals("UNCONFIRMED_DEADLINE", unknown.reason)
    }

    @Test fun dailyReminderMovesAfterApplication() {
        val rule = RepeatRule("DAILY", zone, LocalTime.MIDNIGHT, LocalTime.parse("09:00"))
        val now = instant("2026-09-22T23:00:00Z")
        assertEquals("2026-09-23T00:00:00Z", ReminderLogic.nextRepeatingReminder(now, rule, emptyList()).toString())
        assertEquals("2026-09-24T00:00:00Z", ReminderLogic.nextRepeatingReminder(now, rule, listOf(instant("2026-09-22T22:00:00Z"))).toString())
    }

    @Test fun dispatchSkipsExpiredAppliedBlockedAndReceived() {
        assertEquals("SKIPPED_EXPIRED", ReminderLogic.evaluateDispatch(instant("2026-09-27T00:15:00Z"), instant("2026-09-26T22:00:00Z"), "APPLICATION", permissionGranted = true))
        assertEquals("SKIPPED_ALREADY_APPLIED", ReminderLogic.evaluateDispatch(Instant.EPOCH, Instant.MAX, "REPEAT_ENTRY", appliedInPeriod = true, permissionGranted = true))
        assertEquals("BLOCKED_PERMISSION", ReminderLogic.evaluateDispatch(Instant.EPOCH, Instant.MAX, "APPLICATION", permissionGranted = false))
        assertEquals("SKIPPED_RECEIVED", ReminderLogic.evaluateDispatch(Instant.EPOCH, Instant.MAX, "RECEIVE", received = true, permissionGranted = true))
    }

    @Test fun quietHoursNeverDelayPastValidity() {
        val result = ReminderLogic.applyQuietHours(instant("2026-09-23T14:00:00Z"), instant("2026-09-23T15:00:00Z"), zone, LocalTime.parse("22:00"), LocalTime.parse("08:00"))
        assertNull(result.effectiveAt)
        assertEquals("SKIPPED_QUIET_WINDOW", result.reason)
    }

    @Test fun periodUsesConfiguredResetTime() {
        val period = ReminderLogic.calculatePeriod(instant("2026-09-23T21:30:00Z"), RepeatRule("DAILY", zone, LocalTime.parse("07:00"), LocalTime.NOON))
        assertEquals("2026-09-22T22:00:00Z", period.start.toString())
        assertEquals("2026-09-23T22:00:00Z", period.end.toString())
    }
}
