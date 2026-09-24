package jp.kenshopocket.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaigns") suspend fun allCampaigns(): List<CampaignEntity>
    @Query("SELECT * FROM entry_records WHERE campaignId=:id AND voidedAt IS NULL") suspend fun activeEntries(id: String): List<EntryRecordEntity>
    @Query("UPDATE entry_rules SET confirmed=:confirmed WHERE id=:id") suspend fun confirmRule(id: String, confirmed: Boolean)
    @Query("SELECT * FROM entry_records WHERE clientMutationId=:id") suspend fun entryByMutation(id: String): EntryRecordEntity?
    @Query("SELECT * FROM entry_records WHERE id=:id") suspend fun entry(id: String): EntryRecordEntity?
    @Query("SELECT * FROM launch_sessions WHERE id=:id") suspend fun launch(id: String): LaunchSessionEntity?
    @Query("SELECT * FROM entry_records") fun observeEntries(): Flow<List<EntryRecordEntity>>
    @Query("SELECT * FROM launch_sessions") fun observeLaunches(): Flow<List<LaunchSessionEntity>>
    @Query("SELECT * FROM campaigns WHERE lifecycle = 'ACTIVE' ORDER BY CASE WHEN deadlineDate IS NULL THEN 1 ELSE 0 END, deadlineDate, updatedAt DESC")
    fun observeCampaigns(): Flow<List<CampaignEntity>>

    @Query("SELECT * FROM campaigns WHERE id = :id") suspend fun campaign(id: String): CampaignEntity?
    @Query("SELECT * FROM campaign_urls WHERE campaignId = :id AND role = 'APPLY' ORDER BY createdAt LIMIT 1") suspend fun url(id: String): CampaignUrlEntity?
    @Query("SELECT * FROM entry_rules WHERE campaignId = :id ORDER BY createdAt DESC LIMIT 1") suspend fun rule(id: String): EntryRuleEntity?
    @Query("SELECT COUNT(*) FROM entry_records WHERE campaignId = :id AND voidedAt IS NULL") suspend fun entryCount(id: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM launch_sessions WHERE campaignId = :id AND state = 'REVIEW_LATER')") suspend fun hasPending(id: String): Boolean
    @Query("SELECT * FROM launch_sessions WHERE state IN ('LAUNCHED','NEEDS_CONFIRMATION','REVIEW_LATER') ORDER BY launchedAt") suspend fun pendingLaunches(): List<LaunchSessionEntity>
    @Insert suspend fun insertCampaign(value: CampaignEntity)
    @Insert suspend fun insertUrl(value: CampaignUrlEntity)
    @Insert suspend fun insertRule(value: EntryRuleEntity)
    @Insert suspend fun insertLaunch(value: LaunchSessionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEntry(value: EntryRecordEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(value: DraftEntity)
    @Query("UPDATE launch_sessions SET state = :state, confirmedEntryId = :entryId, updatedAt = :now WHERE id = :id") suspend fun updateLaunch(id: String, state: String, entryId: String?, now: Long)
    @Query("SELECT * FROM drafts WHERE draftKind = 'CAMPAIGN_NEW' ORDER BY updatedAt DESC LIMIT 1") suspend fun latestNewDraft(): DraftEntity?
    @Query("DELETE FROM drafts WHERE id = :id") suspend fun deleteDraft(id: String)

    @Transaction
    suspend fun insertNewCampaign(campaign: CampaignEntity, url: CampaignUrlEntity?, rule: EntryRuleEntity) {
        insertCampaign(campaign)
        insertRule(rule)
        if (url != null) insertUrl(url)
    }
}
