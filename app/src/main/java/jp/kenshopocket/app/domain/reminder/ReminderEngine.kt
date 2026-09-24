package jp.kenshopocket.app.domain.reminder

import androidx.room.withTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import jp.kenshopocket.app.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map

data class NotificationAccess(val permission: Boolean, val channel: Boolean, val exact: Boolean, val batteryRestricted: Boolean = false) {
    val blockedReason: String? get() = if (!channel) "BLOCKED_CHANNEL" else if (!permission) "BLOCKED_PERMISSION" else null
}

interface ReminderGateway {
    fun access(): NotificationAccess
    fun schedule(at: Long?, preferExact: Boolean): String
    fun post(occurrence: ReminderOccurrence, campaign: CampaignEntity)
    fun cancel(campaignId: String)
    fun test()
}

/** One instance per process. DB state is authoritative; OS calls are retried with stable IDs. */
class ReminderEngine(private val db: AppDatabase, private val gateway: ReminderGateway, private val clock: Clock = Clock.systemUTC()) {
    private val mutex = Mutex()
    private val dao = db.reminderDao()
    private val campaigns = db.campaignDao()
    val next = dao.observeNext()
    val events = dao.observeEvents()
    val settings = dao.observeSettings()
    val titles = campaigns.observeCampaigns().map { values -> values.associate { it.id to it.title } }
    fun access() = gateway.access()

    suspend fun refresh(dispatchDue: Boolean = false) = mutex.withLock { refreshLocked(dispatchDue) }

    private suspend fun refreshLocked(dispatchDue: Boolean) {
        val now = clock.instant()
        val settings = dao.settings() ?: ReminderSettings().also { dao.saveSettings(it) }
        val access = gateway.access()
        val due = mutableListOf<ReminderOccurrence>()
        // Only already-persisted schedules may be dispatched late; new plans are strictly future.
        for (occurrence in dao.occurrences().filter { it.state in setOf("PENDING", "SCHEDULED", "POSTING", "POSTED", "BLOCKED_PERMISSION", "BLOCKED_CHANNEL") }) {
            val campaign = campaigns.campaign(occurrence.campaignId)
            val entryRule = campaigns.rule(occurrence.campaignId)
            val rule = dao.rule(occurrence.campaignId)
            val entries = campaigns.activeEntries(occurrence.campaignId)
            val invalid = CampaignReminderPlanner.validateDispatch(now, occurrence, campaign, entryRule, rule, settings, entries)
            if (invalid != null) {
                if (occurrence.state == "POSTED") gateway.cancel(occurrence.campaignId)
                complete(occurrence, "CANCELLED", invalid, now)
            } else if (occurrence.state != "POSTED" && occurrence.effectiveAt <= now.toEpochMilli()) {
                if (occurrence.state.startsWith("BLOCKED_")) complete(occurrence, "SKIPPED", "SKIPPED_PAST_ON_RECONCILE", now)
                // SCHEDULED means the OS already accepted this exact occurrence. A foreground
                // reconcile can race the alarm receiver at the boundary, so keep the persisted
                // schedule eligible for a late, idempotent dispatch just like POSTING.
                else if (dispatchDue || occurrence.state in setOf("SCHEDULED", "POSTING")) due += occurrence
                else complete(occurrence, "SKIPPED", "SKIPPED_PAST_ON_RECONCILE", now)
            }
        }
        // Cancel stale tags before posting the current period for the same campaign.
        for (occurrence in due) dispatch(occurrence, now)
        val desired = mutableMapOf<String, ReminderOccurrence>()
        for (campaign in campaigns.allCampaigns().filter { it.lifecycle == "ACTIVE" }) {
            val entryRule = campaigns.rule(campaign.id) ?: continue
            dao.ensureRule(CampaignReminderRule(campaign.id))
            val rule = dao.rule(campaign.id) ?: continue
            try {
                CampaignReminderPlanner.plan(now, campaign, entryRule, rule, settings, campaigns.activeEntries(campaign.id)).forEach { desired[it.logicalKey] = it }
            } catch (_: IllegalArgumentException) {
                dao.addEvent(NotificationEvent(occurrenceKey = null, occurredAt = now.toEpochMilli(), reason = "INVALID_RULE"))
            }
        }
        val existing = dao.occurrences().associateBy { it.logicalKey }
        // Keep persisted future deferrals even when their original planned time is past.
        existing.values.filter { (":snooze:" in it.logicalKey || (it.effectiveAt > it.plannedAt && it.plannedAt <= now.toEpochMilli())) && it.state in setOf("PENDING", "SCHEDULED", "BLOCKED_PERMISSION", "BLOCKED_CHANNEL") && it.effectiveAt > now.toEpochMilli() && settings.enabled }.forEach { desired[it.logicalKey] = it }
        db.withTransaction {
            for ((key, occurrence) in desired) {
                val old = existing[key]
                if (old?.state in setOf("POSTED", "POSTING")) continue
                val block = access.blockedReason
                val value = if (occurrence.state == "SKIPPED") occurrence else occurrence.copy(state = block ?: "PENDING", reason = block, scheduleMode = "NONE")
                dao.saveOccurrence(value)
                if (value.reason != null && old?.reason != value.reason) dao.addEvent(NotificationEvent(occurrenceKey = key, occurredAt = now.toEpochMilli(), reason = value.reason))
            }
            for (old in existing.values) if (old.state in setOf("PENDING", "SCHEDULED", "BLOCKED_PERMISSION", "BLOCKED_CHANNEL") && old.logicalKey !in desired) {
                dao.saveOccurrence(old.copy(state = "CANCELLED", reason = "REPLANNED", updatedAt = now.toEpochMilli()))
            }
        }
        val earliest = dao.occurrences().filter { it.state in setOf("PENDING", "SCHEDULED") && it.effectiveAt > now.toEpochMilli() }.minByOrNull { it.effectiveAt }
        try {
            val mode = gateway.schedule(earliest?.effectiveAt, settings.preferExact)
            if (earliest != null) {
                dao.saveOccurrence(earliest.copy(state = "SCHEDULED", scheduleMode = mode, updatedAt = now.toEpochMilli()))
                if (existing[earliest.logicalKey]?.let { it.state == "SCHEDULED" && it.scheduleMode == mode } != true) dao.addEvent(NotificationEvent(occurrenceKey = earliest.logicalKey, occurredAt = now.toEpochMilli(), reason = "SCHEDULED", scheduleMode = mode))
            }
        } catch (_: RuntimeException) {
            dao.addEvent(NotificationEvent(occurrenceKey = earliest?.logicalKey, occurredAt = now.toEpochMilli(), reason = "SCHEDULE_FAILED"))
            throw IllegalStateException("SCHEDULE_FAILED")
        }
        val retention = now.minus(Duration.ofDays(90)).toEpochMilli()
        dao.pruneEvents(retention)
        dao.pruneOccurrences(retention)
        // Propagate recoverable posting failure to WorkManager/receiver retry handling.
        if (dao.occurrences().any { it.state == "POSTING" }) throw IllegalStateException("POST_FAILED")
    }

    private suspend fun dispatch(value: ReminderOccurrence, now: Instant) {
        // Persist before entering the posting transaction. A crash after notify() leaves a retryable marker.
        dao.saveOccurrence(value.copy(state = "POSTING", updatedAt = now.toEpochMilli()))
        db.withTransaction {
        val current = dao.occurrence(value.logicalKey) ?: return@withTransaction
        if (current.state !in setOf("PENDING", "SCHEDULED", "POSTING")) return@withTransaction
        val campaign = campaigns.campaign(current.campaignId)
        val settings = dao.settings() ?: ReminderSettings()
        val rule = campaigns.rule(current.campaignId)
        val invalid = CampaignReminderPlanner.validateDispatch(now, current, campaign, rule, dao.rule(current.campaignId), settings, campaigns.activeEntries(current.campaignId))
        val blocked = gateway.access().blockedReason
        when {
            invalid != null -> complete(current, "SKIPPED", invalid, now)
            blocked != null -> complete(current, blocked, blocked, now)
            campaign != null -> {
                if (settings.quietEnabled) {
                    val quiet = ReminderLogic.applyQuietHours(now, Instant.ofEpochMilli(current.validUntil), java.time.ZoneId.of(rule!!.zoneId), java.time.LocalTime.parse(settings.quietStart), java.time.LocalTime.parse(settings.quietEnd))
                    if (quiet.effectiveAt == null) { complete(current, "SKIPPED", "SKIPPED_QUIET_WINDOW", now); return@withTransaction }
                    if (quiet.effectiveAt > now) {
                        dao.saveOccurrence(current.copy(state = "PENDING", effectiveAt = quiet.effectiveAt.toEpochMilli(), reason = "DEFERRED_QUIET", updatedAt = now.toEpochMilli()))
                        dao.addEvent(NotificationEvent(occurrenceKey = current.logicalKey, occurredAt = now.toEpochMilli(), reason = "DEFERRED_QUIET"))
                        return@withTransaction
                    }
                }
                try {
                    gateway.post(current, campaign)
                    dao.saveOccurrence(current.copy(state = "POSTED", postedAt = now.toEpochMilli(), updatedAt = now.toEpochMilli()))
                    dao.addEvent(NotificationEvent(occurrenceKey = current.logicalKey, occurredAt = now.toEpochMilli(), reason = "POSTED", scheduleMode = current.scheduleMode))
                } catch (_: SecurityException) {
                    complete(current, "BLOCKED_PERMISSION", "BLOCKED_PERMISSION", now)
                } catch (_: RuntimeException) {
                    // Leave recoverable POSTING state; the worker retries without adding a second notification.
                    dao.addEvent(NotificationEvent(occurrenceKey = current.logicalKey, occurredAt = now.toEpochMilli(), reason = "POST_FAILED"))
                }
            }
        }
        }
    }

    private suspend fun complete(value: ReminderOccurrence, state: String, reason: String, now: Instant) {
        dao.saveOccurrence(value.copy(state = state, reason = reason, updatedAt = now.toEpochMilli()))
        dao.addEvent(NotificationEvent(occurrenceKey = value.logicalKey, occurredAt = now.toEpochMilli(), reason = reason, scheduleMode = value.scheduleMode))
    }

    suspend fun saveSettings(value: ReminderSettings) = mutex.withLock {
        java.time.LocalTime.parse(value.quietStart)
        java.time.LocalTime.parse(value.quietEnd)
        require(!value.quietEnabled || value.quietStart != value.quietEnd)
        dao.saveSettings(value)
        refreshLocked(false)
    }

    suspend fun saveRule(value: CampaignReminderRule, confirmedRepeat: Boolean) = mutex.withLock {
        java.time.LocalTime.parse(value.localTime)
        java.time.LocalTime.parse(value.repeatTime)
        db.withTransaction {
            val previous = dao.rule(value.campaignId)
            dao.saveRule(value.copy(revision = (previous?.revision ?: 0) + 1))
            campaigns.rule(value.campaignId)?.let { campaigns.confirmRule(it.id, confirmedRepeat) }
        }
        refreshLocked(false)
    }

    suspend fun rule(id: String) = dao.rule(id) ?: CampaignReminderRule(id)
    suspend fun entryRule(id: String) = campaigns.rule(id)
    suspend fun clearEvents() = dao.clearEvents()

    suspend fun test(): Boolean = mutex.withLock {
        val blocked = gateway.access().blockedReason
        val reason = if (blocked != null) blocked else try { gateway.test(); "TEST_POSTED" } catch (_: RuntimeException) { "POST_FAILED" }
        dao.addEvent(NotificationEvent(occurrenceKey = null, occurredAt = clock.millis(), reason = reason))
        reason == "TEST_POSTED"
    }

    suspend fun snooze(key: String) = mutex.withLock {
        val original = dao.occurrence(key) ?: return@withLock
        if (original.state != "POSTED") return@withLock
        val now = clock.instant()
        val target = now.plusSeconds(3600).toEpochMilli()
        if (target >= original.validUntil) {
            dao.addEvent(NotificationEvent(occurrenceKey = key, occurredAt = clock.millis(), reason = "SNOOZE_EXCEEDS_DEADLINE"))
            return@withLock
        }
        val snoozed = original.copy(logicalKey = "$key:snooze:$target", effectiveAt = target, state = "PENDING", postedAt = null, updatedAt = clock.millis())
        db.withTransaction {
            dao.saveOccurrence(snoozed)
            dao.saveOccurrence(original.copy(state = "CANCELLED", reason = "SNOOZED"))
            dao.addEvent(NotificationEvent(occurrenceKey = key, occurredAt = now.toEpochMilli(), reason = "SNOOZED"))
        }
        gateway.cancel(original.campaignId)
        refreshLocked(false)
    }
}
