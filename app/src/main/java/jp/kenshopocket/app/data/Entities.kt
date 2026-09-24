package jp.kenshopocket.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "campaigns", indices = [Index("lifecycle"), Index("deadlineDate"), Index("updatedAt")])
data class CampaignEntity(
    @PrimaryKey val id: String,
    val title: String,
    val deadlineDate: String?,
    val deadlineTime: String?,
    val deadlineConfirmed: Boolean,
    val entryMode: String,
    val lifecycle: String = "ACTIVE",
    val verificationStatus: String = "USER_REVIEWED",
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

@Entity(
    tableName = "campaign_urls",
    foreignKeys = [ForeignKey(entity = CampaignEntity::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId"), Index("dedupeKey")],
)
data class CampaignUrlEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val originalUrl: String,
    val launchUrl: String,
    val dedupeKey: String,
    val reviewRequired: Boolean = false,
    val role: String = "APPLY",
    val createdAt: Long,
)

@Entity(
    tableName = "entry_rules",
    foreignKeys = [ForeignKey(entity = CampaignEntity::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId")],
)
data class EntryRuleEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val mode: String,
    val zoneId: String = "Asia/Tokyo",
    val resetLocalTime: String = "00:00",
    val confirmed: Boolean = true,
    val createdAt: Long,
)

@Entity(
    tableName = "launch_sessions",
    foreignKeys = [ForeignKey(entity = CampaignEntity::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId"), Index("state")],
)
data class LaunchSessionEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val urlSnapshot: String,
    val launchedAt: Long,
    val state: String,
    val originRoute: String,
    val confirmedEntryId: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "entry_records",
    foreignKeys = [ForeignKey(entity = CampaignEntity::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId"), Index(value = ["clientMutationId"], unique = true), Index(value = ["activeDedupeKey"], unique = true)],
)
data class EntryRecordEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val entryRuleId: String,
    val appliedAt: Long,
    val periodKey: String?,
    val activeDedupeKey: String?,
    val clientMutationId: String,
    val launchSessionId: String?,
    val result: String = "PENDING",
    val campaignTitleSnapshot: String,
    val urlSnapshot: String?,
    val createdAt: Long,
    val voidedAt: Long? = null,
)

@Entity(tableName = "drafts", indices = [Index(value = ["draftKind", "targetId"])])
data class DraftEntity(
    @PrimaryKey val id: String,
    val draftKind: String,
    val targetId: String?,
    val payloadJson: String,
    val updatedAt: Long,
)

data class CampaignCard(
    val campaign: CampaignEntity,
    val url: CampaignUrlEntity?,
    val rule: EntryRuleEntity,
    val entryCount: Int,
    val pendingConfirmation: Boolean,
)
