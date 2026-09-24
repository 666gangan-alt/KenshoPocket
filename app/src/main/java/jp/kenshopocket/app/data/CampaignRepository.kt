package jp.kenshopocket.app.data

import androidx.room.withTransaction
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import jp.kenshopocket.app.domain.importer.ImportCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import jp.kenshopocket.app.domain.reminder.CampaignReminderPlanner
import jp.kenshopocket.app.domain.reminder.ReminderLogic
import jp.kenshopocket.app.domain.reminder.RepeatRule
import java.time.LocalTime

class CampaignRepository(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC(), private val onChanged: () -> Unit = {}) {
    private val dao = db.campaignDao()
    suspend fun notificationCampaign(key: String): String? = db.reminderDao().occurrence(key)?.campaignId

    fun observeCards(): Flow<List<CampaignCard>> = combine(dao.observeCampaigns(), dao.observeEntries(), dao.observeLaunches()) { campaigns, _, _ ->
        campaigns.mapNotNull { campaign ->
            val rule = dao.rule(campaign.id) ?: return@mapNotNull null
            CampaignCard(campaign, dao.url(campaign.id), rule, dao.entryCount(campaign.id), dao.hasPending(campaign.id))
        }
    }

    suspend fun card(id: String): CampaignCard? {
        val campaign = dao.campaign(id) ?: return null
        val rule = dao.rule(id) ?: return null
        return CampaignCard(campaign, dao.url(id), rule, dao.entryCount(id), dao.hasPending(id))
    }

    suspend fun addCampaign(title: String, urlText: String, deadline: String?) {
        require(title.trim().isNotEmpty())
        require(title.length <= 200)
        val launchUrl = urlText.trim().takeIf(String::isNotEmpty)?.also(::requireSafeHttpsUrl)
        val verifiedDeadline = deadline?.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it).toString() }.getOrElse { throw IllegalArgumentException("締切日は YYYY-MM-DD 形式で入力してください") }
        }
        val now = clock.millis()
        val campaignId = UUID.randomUUID().toString()
        val ruleId = UUID.randomUUID().toString()
        val campaign = CampaignEntity(campaignId, title.trim(), verifiedDeadline, null, verifiedDeadline != null, "ONCE", createdAt = now, updatedAt = now)
        val url = launchUrl?.let { CampaignUrlEntity(UUID.randomUUID().toString(), campaignId, it, it, it.lowercase(), createdAt = now) }
        val rule = EntryRuleEntity(ruleId, campaignId, "ONCE", createdAt = now)
        db.withTransaction { dao.insertNewCampaign(campaign, url, rule) }
        onChanged()
    }

    suspend fun commitImport(candidates: List<ImportCandidate>, confirmedDeadlineIndexes: Set<Int>) {
        require(candidates.isNotEmpty()) { "保存する候補を選んでください" }
        val operationId = UUID.randomUUID().toString()
        db.withTransaction {
            candidates.forEachIndexed { index, candidate ->
                require(candidate.title.isNotBlank() && candidate.title.length <= 200)
                val now = clock.millis()
                val campaignId = UUID.randomUUID().toString()
                val ruleId = UUID.randomUUID().toString()
                val confirmed = candidate.dateCandidate != null && index in confirmedDeadlineIndexes
                val campaign = CampaignEntity(
                    id = campaignId,
                    title = candidate.title.trim(),
                    deadlineDate = candidate.dateCandidate?.toString(),
                    deadlineTime = candidate.timeCandidate?.toString(),
                    deadlineConfirmed = confirmed,
                    entryMode = candidate.entryModeCandidate,
                    verificationStatus = if (candidate.warnings.isEmpty() && confirmed) "USER_REVIEWED" else "NEEDS_REVIEW",
                    note = "import:$operationId",
                    createdAt = now,
                    updatedAt = now,
                )
                dao.insertCampaign(campaign)
                dao.insertRule(EntryRuleEntity(ruleId, campaignId, candidate.entryModeCandidate, confirmed = false, createdAt = now))
                candidate.relatedUrls.distinct().forEachIndexed { urlIndex, value ->
                    val isPrimary = value == candidate.launchUrlCandidate
                    dao.insertUrl(CampaignUrlEntity(
                        id = UUID.randomUUID().toString(),
                        campaignId = campaignId,
                        originalUrl = value,
                        launchUrl = value,
                        dedupeKey = value.lowercase(),
                        // HTTP is retained as a candidate for user editing, but it must never
                        // appear launchable. Keep this guard here as well as in the parser so
                        // callers constructing ImportCandidate directly get the same safety.
                        reviewRequired = candidate.urlReviewRequired || value.startsWith("http://", ignoreCase = true),
                        role = if (isPrimary) "APPLY" else "OTHER",
                        createdAt = now + urlIndex,
                    ))
                }
            }
        }
        onChanged()
    }

    suspend fun prepareLaunch(campaignId: String, originRoute: String): LaunchSessionEntity {
        val url = dao.url(campaignId) ?: error("URLが登録されていません")
        require(!url.reviewRequired) { "応募URLの確認が必要です" }
        requireSafeHttpsUrl(url.launchUrl)
        val now = clock.millis()
        val session = LaunchSessionEntity(UUID.randomUUID().toString(), campaignId, url.launchUrl, now, "LAUNCHED", originRoute, updatedAt = now)
        dao.insertLaunch(session)
        return session
    }

    suspend fun markLaunchForReview(id: String) = dao.updateLaunch(id, "REVIEW_LATER", null, clock.millis())
    suspend fun markNotApplied(id: String) = dao.updateLaunch(id, "NOT_APPLIED", null, clock.millis())

    suspend fun confirmApplied(launchId: String, mutationId: String = UUID.randomUUID().toString()): EntryRecordEntity = db.withTransaction {
        dao.entryByMutation(mutationId)?.let { return@withTransaction it }
        val launch = dao.launch(launchId) ?: error("確認待ちの起動履歴がありません")
        launch.confirmedEntryId?.let { id -> dao.entry(id)?.let { return@withTransaction it } }
        require(launch.state in setOf("LAUNCHED", "NEEDS_CONFIRMATION", "REVIEW_LATER"))
        val campaign = dao.campaign(launch.campaignId) ?: error("懸賞がありません")
        val rule = dao.rule(campaign.id) ?: error("応募周期がありません")
        val now = clock.millis()
        val existing = dao.activeEntries(campaign.id)
        if (CampaignReminderPlanner.applied(Instant.ofEpochMilli(now), rule, existing)) {
            val found = if (rule.mode == "ONCE") existing.first() else {
                val bounds = ReminderLogic.calculatePeriod(Instant.ofEpochMilli(now), RepeatRule(rule.mode, ZoneId.of(rule.zoneId), LocalTime.parse(rule.resetLocalTime), LocalTime.NOON))
                existing.first { it.appliedAt >= bounds.start.toEpochMilli() && it.appliedAt < bounds.end.toEpochMilli() }
            }
            dao.updateLaunch(launch.id, "CONFIRMED", found.id, now)
            return@withTransaction found
        }
        val period = periodKey(rule, Instant.ofEpochMilli(now))
        val dedupe = if (rule.mode == "MANUAL") null else "${campaign.id}:${rule.id}:$period"
        val entry = EntryRecordEntity(UUID.randomUUID().toString(), campaign.id, rule.id, now, period, dedupe, mutationId, launch.id, campaignTitleSnapshot = campaign.title, urlSnapshot = launch.urlSnapshot, createdAt = now)
        val inserted = dao.insertEntry(entry)
        require(inserted != -1L) { "この期間は応募済みです" }
        dao.updateLaunch(launch.id, "CONFIRMED", entry.id, now)
        entry
    }.also { onChanged() }

    suspend fun saveDraft(id: String, payload: String) = dao.saveDraft(DraftEntity(id, "CAMPAIGN_NEW", null, payload, clock.millis()))
    suspend fun latestDraft() = dao.latestNewDraft()
    suspend fun deleteDraft(id: String) = dao.deleteDraft(id)

    private fun periodKey(rule: EntryRuleEntity, at: Instant): String = when (rule.mode) {
        "ONCE" -> "ONCE"
        "DAILY", "WEEKLY" -> ReminderLogic.calculatePeriod(at, RepeatRule(rule.mode, ZoneId.of(rule.zoneId), LocalTime.parse(rule.resetLocalTime), LocalTime.NOON)).start.toString()
        else -> at.toEpochMilli().toString()
    }

    companion object {
        fun requireSafeHttpsUrl(value: String) {
            require(value.length <= 8192)
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("URLの形式が正しくありません") }
            require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPSのURLを入力してください" }
            require(uri.host != null && uri.userInfo == null) { "安全に開けないURLです" }
        }
    }
}
