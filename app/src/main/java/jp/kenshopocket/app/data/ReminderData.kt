package jp.kenshopocket.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminder_settings")
data class ReminderSettings(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = true,
    val preferExact: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietStart: String = "22:00",
    val quietEnd: String = "08:00",
)

@Entity(tableName = "reminder_rules", foreignKeys = [ForeignKey(entity = CampaignEntity::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE)])
data class CampaignReminderRule(
    @PrimaryKey val campaignId: String,
    val enabled: Boolean = true,
    val threeDays: Boolean = true,
    val previousDay: Boolean = true,
    val sameDay: Boolean = true,
    val twoHours: Boolean = true,
    val localTime: String = "09:00",
    val repeatEnabled: Boolean = true,
    val repeatTime: String = "09:00",
    val revision: Long = 1,
)

@Entity(tableName = "reminder_occurrences", indices = [Index(value = ["state", "effectiveAt"]), Index("campaignId")])
data class ReminderOccurrence(
    @PrimaryKey val logicalKey: String,
    val campaignId: String,
    val purpose: String,
    val ruleRevision: Long,
    val plannedAt: Long,
    val effectiveAt: Long,
    val validUntil: Long,
    val periodStart: Long?,
    val periodEnd: Long?,
    val state: String = "PENDING",
    val reason: String? = null,
    val postedAt: Long? = null,
    val scheduleMode: String = "NONE",
    val updatedAt: Long,
)

@Entity(tableName = "notification_events", indices = [Index("occurredAt")])
data class NotificationEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceKey: String?,
    val occurredAt: Long,
    val reason: String,
    val scheduleMode: String = "NONE",
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_settings WHERE id=1") suspend fun settings(): ReminderSettings?
    @Query("SELECT * FROM reminder_settings WHERE id=1") fun observeSettings(): Flow<ReminderSettings?>
    @Upsert suspend fun saveSettings(value: ReminderSettings)
    @Query("SELECT * FROM reminder_rules WHERE campaignId=:id") suspend fun rule(id: String): CampaignReminderRule?
    @Upsert suspend fun saveRule(value: CampaignReminderRule)
    @Query("SELECT * FROM reminder_rules") suspend fun rules(): List<CampaignReminderRule>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureRule(value: CampaignReminderRule): Long
    @Query("SELECT * FROM reminder_occurrences") suspend fun occurrences(): List<ReminderOccurrence>
    @Query("SELECT * FROM reminder_occurrences WHERE logicalKey=:key") suspend fun occurrence(key: String): ReminderOccurrence?
    @Upsert suspend fun saveOccurrence(value: ReminderOccurrence)
    @Query("SELECT * FROM reminder_occurrences WHERE state IN ('PENDING','SCHEDULED','POSTING') ORDER BY effectiveAt LIMIT 30") fun observeNext(): Flow<List<ReminderOccurrence>>
    @Query("SELECT * FROM notification_events ORDER BY occurredAt DESC, id DESC LIMIT 40") fun observeEvents(): Flow<List<NotificationEvent>>
    @Insert suspend fun addEvent(value: NotificationEvent)
    @Query("DELETE FROM notification_events WHERE occurredAt < :before") suspend fun pruneEvents(before: Long)
    @Query("DELETE FROM reminder_occurrences WHERE updatedAt < :before AND state NOT IN ('PENDING','SCHEDULED','POSTING')") suspend fun pruneOccurrences(before: Long)
    @Query("DELETE FROM notification_events") suspend fun clearEvents()
}
