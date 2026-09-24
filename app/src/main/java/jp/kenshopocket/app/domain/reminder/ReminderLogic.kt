package jp.kenshopocket.app.domain.reminder

import java.time.*

data class DeadlineInput(val precision: String, val date: LocalDate?, val time: LocalTime?, val zoneId: ZoneId, val confirmed: Boolean)
data class DeadlinePlan(val plannedAt: List<Instant>, val warning: String? = null, val reason: String? = null)
data class RepeatRule(val mode: String, val zoneId: ZoneId, val resetTime: LocalTime, val remindTime: LocalTime, val confirmed: Boolean = true, val weekStartsOn: DayOfWeek = DayOfWeek.MONDAY)
data class Period(val start: Instant, val end: Instant)
data class QuietResult(val effectiveAt: Instant?, val reason: String? = null)

object ReminderLogic {
    fun planDeadlineReminders(now: Instant, deadline: DeadlineInput, dayOffsets: List<Long>, localTime: LocalTime, minutesBefore: List<Long>): DeadlinePlan {
        if (!deadline.confirmed) return DeadlinePlan(emptyList(), reason = "UNCONFIRMED_DEADLINE")
        val date = deadline.date ?: return DeadlinePlan(emptyList(), reason = "DEADLINE_MISSING")
        val cutoff = if (deadline.precision == "DATE_TIME" && deadline.time != null) date.atTime(deadline.time).atZone(deadline.zoneId).toInstant()
        else date.plusDays(1).atStartOfDay(deadline.zoneId).toInstant()
        val values = linkedSetOf<Instant>()
        dayOffsets.forEach { offset ->
            val candidate = date.plusDays(offset).atTime(localTime).atZone(deadline.zoneId).toInstant()
            if (candidate.isAfter(now) && candidate.isBefore(cutoff)) values += candidate
        }
        if (deadline.precision == "DATE_TIME") minutesBefore.forEach { minutes ->
            val candidate = cutoff.minusSeconds(minutes * 60)
            if (candidate.isAfter(now) && candidate.isBefore(cutoff)) values += candidate
        }
        return DeadlinePlan(values.sorted(), warning = if (deadline.precision == "DATE_ONLY") "TIME_UNKNOWN" else null)
    }

    fun calculatePeriod(at: Instant, rule: RepeatRule): Period {
        val local = at.atZone(rule.zoneId)
        var boundaryDate = local.toLocalDate()
        if (rule.mode == "WEEKLY") boundaryDate = boundaryDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(rule.weekStartsOn))
        if (boundaryDate.atTime(rule.resetTime).atZone(rule.zoneId).toInstant().isAfter(at)) boundaryDate = boundaryDate.minusDays(if (rule.mode == "WEEKLY") 7 else 1)
        val start = boundaryDate.atTime(rule.resetTime).atZone(rule.zoneId).toInstant()
        val end = boundaryDate.plusDays(if (rule.mode == "WEEKLY") 7 else 1).atTime(rule.resetTime).atZone(rule.zoneId).toInstant()
        return Period(start, end)
    }

    fun nextRepeatingReminder(now: Instant, rule: RepeatRule, activeApplications: List<Instant>): Instant? {
        if (!rule.confirmed || rule.mode !in setOf("DAILY", "WEEKLY")) return null
        var period = calculatePeriod(now, rule)
        repeat(400) {
            val candidate = maxOf(period.start, period.start.atZone(rule.zoneId).toLocalDate().atTime(rule.remindTime).atZone(rule.zoneId).toInstant())
            val applied = activeApplications.any { !it.isBefore(period.start) && it.isBefore(period.end) }
            if (candidate.isAfter(now) && candidate.isBefore(period.end) && !applied) return candidate
            period = calculatePeriod(period.end, rule)
        }
        return null
    }

    fun evaluateDispatch(now: Instant, validUntil: Instant?, purpose: String, appliedInPeriod: Boolean = false, received: Boolean = false, permissionGranted: Boolean): String = when {
        !permissionGranted -> "BLOCKED_PERMISSION"
        validUntil != null && !now.isBefore(validUntil) -> "SKIPPED_EXPIRED"
        purpose == "REPEAT_ENTRY" && appliedInPeriod -> "SKIPPED_ALREADY_APPLIED"
        purpose == "RECEIVE" && received -> "SKIPPED_RECEIVED"
        else -> "POST"
    }

    fun applyQuietHours(plannedAt: Instant, validUntil: Instant?, zoneId: ZoneId, quietStart: LocalTime, quietEnd: LocalTime): QuietResult {
        require(quietStart != quietEnd) { "Quiet window must have different start and end" }
        val local = plannedAt.atZone(zoneId)
        val inQuiet = if (quietStart < quietEnd) !local.toLocalTime().isBefore(quietStart) && local.toLocalTime().isBefore(quietEnd)
        else !local.toLocalTime().isBefore(quietStart) || local.toLocalTime().isBefore(quietEnd)
        if (!inQuiet) return QuietResult(plannedAt)
        val endDate = if (quietStart < quietEnd || local.toLocalTime().isBefore(quietEnd)) local.toLocalDate() else local.toLocalDate().plusDays(1)
        val effective = endDate.atTime(quietEnd).atZone(zoneId).toInstant()
        return if (validUntil != null && !effective.isBefore(validUntil)) QuietResult(null, "SKIPPED_QUIET_WINDOW") else QuietResult(effective)
    }
}
