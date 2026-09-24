package jp.kenshopocket.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val NOTIFICATION_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_settings (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, preferExact INTEGER NOT NULL, quietEnabled INTEGER NOT NULL, quietStart TEXT NOT NULL, quietEnd TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_rules (campaignId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, threeDays INTEGER NOT NULL, previousDay INTEGER NOT NULL, sameDay INTEGER NOT NULL, twoHours INTEGER NOT NULL, localTime TEXT NOT NULL, repeatEnabled INTEGER NOT NULL, repeatTime TEXT NOT NULL, revision INTEGER NOT NULL, FOREIGN KEY(campaignId) REFERENCES campaigns(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_occurrences (logicalKey TEXT NOT NULL PRIMARY KEY, campaignId TEXT NOT NULL, purpose TEXT NOT NULL, ruleRevision INTEGER NOT NULL, plannedAt INTEGER NOT NULL, effectiveAt INTEGER NOT NULL, validUntil INTEGER NOT NULL, periodStart INTEGER, periodEnd INTEGER, state TEXT NOT NULL, reason TEXT, postedAt INTEGER, scheduleMode TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_occurrences_state_effectiveAt ON reminder_occurrences(state, effectiveAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_occurrences_campaignId ON reminder_occurrences(campaignId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS notification_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, occurrenceKey TEXT, occurredAt INTEGER NOT NULL, reason TEXT NOT NULL, scheduleMode TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_occurredAt ON notification_events(occurredAt)")
        // v1 never offered explicit confirmation of an inferred repeating rule.
        db.execSQL("UPDATE entry_rules SET confirmed=0 WHERE mode IN ('DAILY','WEEKLY') AND campaignId IN (SELECT id FROM campaigns WHERE note LIKE 'import:%')")
    }
}
