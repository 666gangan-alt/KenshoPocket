package jp.kenshopocket.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CampaignEntity::class, CampaignUrlEntity::class, EntryRuleEntity::class, EntryRecordEntity::class, LaunchSessionEntity::class, DraftEntity::class, ReminderSettings::class, CampaignReminderRule::class, ReminderOccurrence::class, NotificationEvent::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun campaignDao(): CampaignDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "kensho-pocket.db",
        ).addMigrations(NOTIFICATION_MIGRATION).build()
    }
}
