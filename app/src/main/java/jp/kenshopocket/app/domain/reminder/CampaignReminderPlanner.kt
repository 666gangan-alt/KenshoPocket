package jp.kenshopocket.app.domain.reminder

import java.time.*
import jp.kenshopocket.app.data.*

/** Deterministic seven-day projection. No OS calls, wall clock, or persistence. */
object CampaignReminderPlanner {
    fun repeatRule(rule: EntryRuleEntity, reminder: CampaignReminderRule) = RepeatRule(rule.mode, ZoneId.of(rule.zoneId), LocalTime.parse(rule.resetLocalTime), LocalTime.parse(reminder.repeatTime), rule.confirmed)

    fun cutoff(campaign: CampaignEntity, zone: ZoneId): Instant? {
        if (!campaign.deadlineConfirmed || campaign.deadlineDate == null) return null
        val date = LocalDate.parse(campaign.deadlineDate)
        return if (campaign.deadlineTime == null) date.plusDays(1).atStartOfDay(zone).toInstant()
        else date.atTime(LocalTime.parse(campaign.deadlineTime)).atZone(zone).toInstant()
    }

    fun applied(at: Instant, rule: EntryRuleEntity, entries: List<EntryRecordEntity>): Boolean {
        val active = entries.filter { it.voidedAt == null }
        if (rule.mode == "ONCE") return active.isNotEmpty()
        if (rule.mode !in setOf("DAILY", "WEEKLY")) return false
        val period = ReminderLogic.calculatePeriod(at, RepeatRule(rule.mode, ZoneId.of(rule.zoneId), LocalTime.parse(rule.resetLocalTime), LocalTime.NOON))
        return active.any { it.appliedAt >= period.start.toEpochMilli() && it.appliedAt < period.end.toEpochMilli() }
    }

    fun plan(now: Instant, campaign: CampaignEntity, entryRule: EntryRuleEntity, rule: CampaignReminderRule, settings: ReminderSettings, entries: List<EntryRecordEntity>): List<ReminderOccurrence> {
        if (!settings.enabled || !rule.enabled || campaign.lifecycle != "ACTIVE") return emptyList()
        val zone = ZoneId.of(entryRule.zoneId)
        val cutoff = cutoff(campaign, zone)
        if (cutoff != null && cutoff <= now) return emptyList()
        val horizon = now.plus(Duration.ofDays(7))
        val times = mutableMapOf<Instant, String>()
        val date = campaign.deadlineDate?.let(LocalDate::parse)
        if (date != null && campaign.deadlineConfirmed) {
            val offsets = listOfNotNull((-3L).takeIf { rule.threeDays }, (-1L).takeIf { rule.previousDay }, 0L.takeIf { rule.sameDay })
            ReminderLogic.planDeadlineReminders(now, DeadlineInput(if (campaign.deadlineTime == null) "DATE_ONLY" else "DATE_TIME", date, campaign.deadlineTime?.let(LocalTime::parse), zone, true), offsets, LocalTime.parse(rule.localTime), if (rule.twoHours) listOf(120) else emptyList()).plannedAt.forEach { times[it] = "APPLICATION" }
        }
        if (rule.repeatEnabled && entryRule.confirmed && entryRule.mode in setOf("DAILY", "WEEKLY")) {
            var cursor = now
            repeat(8) {
                val next = ReminderLogic.nextRepeatingReminder(cursor, repeatRule(entryRule, rule), entries.filter { it.voidedAt == null }.map { Instant.ofEpochMilli(it.appliedAt) }) ?: return@repeat
                if (next <= horizon && (cutoff == null || next < cutoff)) times.putIfAbsent(next, "REPEAT_ENTRY")
                cursor = next
            }
        }
        return times.filterKeys { it <= horizon }.mapNotNull { (planned, purpose) ->
            if (applied(planned, entryRule, entries)) return@mapNotNull null
            val period = if (entryRule.mode in setOf("DAILY", "WEEKLY")) ReminderLogic.calculatePeriod(planned, repeatRule(entryRule, rule)) else null
            val validUntil = minOf(cutoff ?: Instant.MAX, period?.end ?: planned.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())
            val quiet = if (settings.quietEnabled) ReminderLogic.applyQuietHours(planned, validUntil, zone, LocalTime.parse(settings.quietStart), LocalTime.parse(settings.quietEnd)) else QuietResult(planned)
            val key = "${campaign.id}:${campaign.revision}:${entryRule.id}:${rule.revision}:${planned.toEpochMilli()}"
            ReminderOccurrence(key, campaign.id, purpose, rule.revision, planned.toEpochMilli(), (quiet.effectiveAt ?: planned).toEpochMilli(), validUntil.toEpochMilli(), period?.start?.toEpochMilli(), period?.end?.toEpochMilli(), state = if (quiet.effectiveAt == null) "SKIPPED" else "PENDING", reason = quiet.reason, updatedAt = now.toEpochMilli())
        }.sortedBy { it.effectiveAt }
    }

    fun validateDispatch(now: Instant, occurrence: ReminderOccurrence, campaign: CampaignEntity?, entryRule: EntryRuleEntity?, rule: CampaignReminderRule?, settings: ReminderSettings, entries: List<EntryRecordEntity>): String? {
        if (!settings.enabled || campaign == null || campaign.lifecycle != "ACTIVE" || entryRule == null || rule == null || !rule.enabled || rule.revision != occurrence.ruleRevision) return "CANCELLED"
        if (now.toEpochMilli() >= occurrence.validUntil) return "SKIPPED_EXPIRED"
        val currentCutoff = cutoff(campaign, ZoneId.of(entryRule.zoneId))
        if (currentCutoff != null && now >= currentCutoff) return "SKIPPED_EXPIRED"
        if (occurrence.purpose == "APPLICATION" && !campaign.deadlineConfirmed) return "UNCONFIRMED_DEADLINE"
        if (occurrence.purpose == "REPEAT_ENTRY" && (!entryRule.confirmed || !rule.repeatEnabled)) return "UNCONFIRMED_RULE"
        if (applied(Instant.ofEpochMilli(occurrence.plannedAt), entryRule, entries)) return "SKIPPED_ALREADY_APPLIED"
        return null
    }
}
