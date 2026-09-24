package jp.kenshopocket.app.domain.reminder

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.*
import jp.kenshopocket.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ReminderIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var clock: MutableClock
    private lateinit var gateway: FakeGateway
    private lateinit var engine: ReminderEngine
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        clock = MutableClock(Instant.parse("2026-09-23T23:50:00Z"))
        gateway = FakeGateway()
        engine = ReminderEngine(db, gateway, clock)
    }
    @After fun close() { db.close() }

    private suspend fun campaign(id: String = "c1", confirmed: Boolean = true, mode: String = "ONCE", ruleConfirmed: Boolean = true) {
        db.campaignDao().insertCampaign(CampaignEntity(id, "Test", "2026-09-27", null, confirmed, mode, createdAt = 1, updatedAt = 1))
        db.campaignDao().insertRule(EntryRuleEntity("r$id", id, mode, confirmed = ruleConfirmed, createdAt = 1))
    }
    private suspend fun applied(id: String = "c1", at: Long = clock.millis()) {
        db.campaignDao().insertEntry(EntryRecordEntity("e$id", id, "r$id", at, "ONCE", "$id:ONCE", "mutation$id", null, campaignTitleSnapshot = "Test", urlSnapshot = null, createdAt = at))
    }

    @Test fun idempotentRebuildSchedulesOnlyEarliestAndDoesNotPostEarly() = runBlocking {
        campaign(); campaign("c2")
        engine.refresh(); val count = db.reminderDao().occurrences().size
        assertEquals(6, count)
        assertEquals(Instant.parse("2026-09-24T00:00:00Z").toEpochMilli(), gateway.next)
        engine.refresh(); assertEquals(count, db.reminderDao().occurrences().size)
        assertEquals(0, gateway.posts.size)
        assertEquals(1, db.reminderDao().occurrences().count { it.state == "SCHEDULED" })
        clock.at = Instant.parse("2026-09-24T00:00:05Z")
        engine.refresh(true); engine.refresh(true)
        assertEquals(setOf("c1", "c2"), gateway.posts.keys)
        assertEquals(2, gateway.postCalls)
    }

    @Test fun unconfirmedDeadlineAndInferredRepeatingRuleNeverSchedule() = runBlocking {
        campaign(confirmed = false, mode = "DAILY", ruleConfirmed = false)
        engine.refresh()
        assertTrue(db.reminderDao().occurrences().isEmpty())
        assertNull(gateway.next)
    }

    @Test fun applicationAfterSchedulingSuppressesDispatchWithoutChangingResult() = runBlocking {
        campaign(); engine.refresh(); applied()
        clock.at = Instant.parse("2026-09-24T00:00:05Z")
        engine.refresh(true)
        assertTrue(gateway.posts.isEmpty())
        assertNull(gateway.next)
        assertEquals("PENDING", db.campaignDao().activeEntries("c1").single().result)
    }

    @Test fun revokedPermissionAndChannelAreLoggedAndRecoveryDoesNotReplayPast() = runBlocking {
        campaign(); engine.refresh()
        gateway.status = NotificationAccess(false, true, false)
        clock.at = Instant.parse("2026-09-24T00:00:05Z")
        engine.refresh(true)
        assertTrue(gateway.posts.isEmpty())
        assertTrue(db.reminderDao().observeEvents().first().any { it.reason == "BLOCKED_PERMISSION" })
        gateway.status = NotificationAccess(true, false, false); engine.refresh()
        assertTrue(db.reminderDao().observeEvents().first().any { it.reason == "BLOCKED_CHANNEL" })
        gateway.status = NotificationAccess(true, true, false); engine.refresh()
        assertTrue(gateway.posts.isEmpty())
        assertEquals(Instant.parse("2026-09-26T00:00:00Z").toEpochMilli(), gateway.next)
    }

    @Test fun expirationAndRestartRebuildDoNotSendYesterdayNotifications() = runBlocking {
        campaign(); engine.refresh()
        clock.at = Instant.parse("2026-09-28T00:00:00Z")
        engine.refresh(true)
        assertTrue(gateway.posts.isEmpty()); assertNull(gateway.next)
        assertTrue(db.reminderDao().observeEvents().first().any { it.reason == "SKIPPED_EXPIRED" })
    }

    @Test fun failureAfterPostRetriesSameTagAndKeepsPersistentPostingMarker() = runBlocking {
        campaign(); engine.refresh(); gateway.failAfterPosting = true
        clock.at = Instant.parse("2026-09-24T00:00:05Z")
        try { engine.refresh(true); fail("Posting failure must trigger retry") } catch (_: IllegalStateException) { }
        assertTrue(db.reminderDao().occurrences().any { it.state == "POSTING" })
        gateway.failAfterPosting = false
        ReminderEngine(db, gateway, clock).refresh()
        assertEquals(1, gateway.posts.size)
        assertEquals(2, gateway.postCalls)
        assertEquals(1, db.reminderDao().occurrences().count { it.state == "POSTED" })
    }

    @Test fun snoozeIsIdempotentAndBoundedByValidity() = runBlocking {
        campaign(); engine.refresh()
        clock.at = Instant.parse("2026-09-24T00:00:05Z"); engine.refresh(true)
        val original = db.reminderDao().occurrences().single { it.state == "POSTED" }
        engine.snooze(original.logicalKey); engine.snooze(original.logicalKey)
        assertEquals(1, db.reminderDao().occurrences().count { ":snooze:" in it.logicalKey })
        val next = db.reminderDao().occurrences().single { ":snooze:" in it.logicalKey }
        db.reminderDao().saveOccurrence(next.copy(state = "POSTED", validUntil = clock.millis() + 30_000))
        engine.snooze(next.logicalKey)
        assertTrue(db.reminderDao().observeEvents().first().any { it.reason == "SNOOZE_EXCEEDS_DEADLINE" })
    }

    @Test fun nextDayRepeatAndSameTimeDeadlineAreMerged() = runBlocking {
        campaign(mode = "DAILY"); applied()
        engine.refresh()
        val all = db.reminderDao().occurrences()
        assertFalse(all.any { it.plannedAt == Instant.parse("2026-09-24T00:00:00Z").toEpochMilli() })
        assertEquals(all.map { it.plannedAt }.distinct().size, all.size)
        assertEquals(Instant.parse("2026-09-25T00:00:00Z").toEpochMilli(), gateway.next)
    }

    @Test fun disablingNotificationsCancelsSingleOsAlarm() = runBlocking {
        campaign(); engine.refresh()
        engine.saveSettings(ReminderSettings(enabled = false))
        assertNull(gateway.next)
        assertFalse(db.reminderDao().occurrences().any { it.state == "SCHEDULED" })
    }

    @Test fun quietDeferralSurvivesRestartAfterOriginalTime() = runBlocking {
        campaign()
        engine.saveSettings(ReminderSettings(quietEnabled = true, quietStart = "08:00", quietEnd = "10:00"))
        clock.at = Instant.parse("2026-09-24T00:30:00Z")
        ReminderEngine(db, gateway, clock).refresh()
        assertEquals(Instant.parse("2026-09-24T01:00:00Z").toEpochMilli(), gateway.next)
        clock.at = Instant.parse("2026-09-24T01:00:01Z"); engine.refresh(true)
        assertEquals(1, gateway.postCalls)
    }

    @Test fun alarmArrivingDuringQuietWindowDefersAndRechecksBeforePosting() = runBlocking {
        campaign(); engine.refresh()
        // Simulate a setting changed after the OS alarm was registered.
        db.reminderDao().saveSettings(ReminderSettings(quietEnabled = true, quietStart = "08:00", quietEnd = "10:00"))
        clock.at = Instant.parse("2026-09-24T00:00:05Z"); engine.refresh(true)
        assertEquals(0, gateway.postCalls)
        assertEquals(Instant.parse("2026-09-24T01:00:00Z").toEpochMilli(), gateway.next)
        applied()
        clock.at = Instant.parse("2026-09-24T01:00:01Z"); engine.refresh(true)
        assertEquals(0, gateway.postCalls)
    }

    @Test fun blockedSnoozeIsCancelledWhenApplicationIsRecorded() = runBlocking {
        campaign(); engine.refresh()
        clock.at = Instant.parse("2026-09-24T00:00:05Z"); engine.refresh(true)
        val original = db.reminderDao().occurrences().single { it.state == "POSTED" }
        engine.snooze(original.logicalKey)
        gateway.status = NotificationAccess(false, true, false); engine.refresh()
        applied(); gateway.status = NotificationAccess(true, true, false); engine.refresh()
        assertNull(gateway.next)
        assertFalse(db.reminderDao().occurrences().any { it.state == "SCHEDULED" })
    }

    @Test fun confirmingInferredCycleExplicitlyEnablesRepeatNotifications() = runBlocking {
        campaign(confirmed = false, mode = "WEEKLY", ruleConfirmed = false)
        engine.refresh(); assertNull(gateway.next)
        engine.saveRule(engine.rule("c1"), true)
        assertEquals(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli(), gateway.next)
        engine.saveRule(engine.rule("c1"), false)
        assertNull(gateway.next)
    }

    @Test fun repositoryConfirmationIsIdempotentAcrossLaunchesAndPreservesPendingResult() = runBlocking {
        campaign(mode = "DAILY")
        db.campaignDao().insertUrl(CampaignUrlEntity("u", "c1", "https://example.invalid/apply", "https://example.invalid/apply", "u", createdAt = 1))
        var changes = 0
        val repository = CampaignRepository(db, clock) { changes++ }
        val launch = repository.prepareLaunch("c1", "detail/c1")
        val first = repository.confirmApplied(launch.id, "m1")
        assertEquals(first.id, repository.confirmApplied(launch.id, "m1").id)
        val another = repository.prepareLaunch("c1", "detail/c1")
        assertEquals(first.id, repository.confirmApplied(another.id, "m2").id)
        assertEquals(1, db.campaignDao().activeEntries("c1").size)
        assertEquals("PENDING", first.result)
        assertTrue(changes > 0)
        clock.at = clock.at.plusSeconds(86400)
        val tomorrow = repository.prepareLaunch("c1", "detail/c1")
        repository.confirmApplied(tomorrow.id, "m3")
        assertEquals(2, db.campaignDao().activeEntries("c1").size)
    }

    @Test fun v1MigrationRetainsCampaignUrlDraftAndEntryAndValidatesRoomSchema() = runBlocking {
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val source = javaClass.classLoader!!.getResourceAsStream("jp.kenshopocket.app.data.AppDatabase/1.json")!!.bufferedReader().use { it.readText() }
        val schema = JSONObject(source).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.getJSONArray("indices")
            for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        old.execSQL("INSERT INTO campaigns VALUES ('old','Legacy','2026-09-27',NULL,1,'DAILY','ACTIVE','NEEDS_REVIEW','import:legacy',1,1,1)")
        old.execSQL("INSERT INTO entry_rules VALUES ('rule','old','DAILY','Asia/Tokyo','00:00',1,1)")
        old.execSQL("INSERT INTO campaign_urls VALUES ('url','old','https://example.invalid','https://example.invalid','key',0,'APPLY',1)")
        old.execSQL("INSERT INTO drafts VALUES ('draft','CAMPAIGN_NEW',NULL,'saved input',1)")
        old.execSQL("INSERT INTO entry_records VALUES ('entry','old','rule',1,'ONCE','unique','mutation',NULL,'PENDING','Legacy',NULL,1,NULL)")
        old.version = 1; old.close()
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(NOTIFICATION_MIGRATION).allowMainThreadQueries().build()
        try {
            assertEquals("Legacy", upgraded.campaignDao().campaign("old")!!.title)
            assertEquals("https://example.invalid", upgraded.campaignDao().url("old")!!.launchUrl)
            assertEquals("saved input", upgraded.campaignDao().latestNewDraft()!!.payloadJson)
            assertEquals("PENDING", upgraded.campaignDao().activeEntries("old").single().result)
            assertFalse(upgraded.campaignDao().rule("old")!!.confirmed)
            upgraded.reminderDao().saveSettings(ReminderSettings())
            assertNotNull(upgraded.reminderDao().settings())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}

class MutableClock(var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
}
class FakeGateway : ReminderGateway {
    var status = NotificationAccess(true, true, false)
    var next: Long? = null
    val posts = mutableMapOf<String, String>()
    var postCalls = 0
    var failAfterPosting = false
    override fun access() = status
    override fun schedule(at: Long?, preferExact: Boolean): String { next = at; return if (preferExact && status.exact) "EXACT" else "INEXACT" }
    override fun post(occurrence: ReminderOccurrence, campaign: CampaignEntity) {
        posts[campaign.id] = occurrence.logicalKey; postCalls++
        if (failAfterPosting) throw IllegalStateException("Injected post failure")
    }
    override fun cancel(campaignId: String) { posts.remove(campaignId) }
    override fun test() = Unit
}
