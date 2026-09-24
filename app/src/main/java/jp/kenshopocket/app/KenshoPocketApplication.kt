package jp.kenshopocket.app

import android.app.Application
import jp.kenshopocket.app.data.AppDatabase
import jp.kenshopocket.app.data.CampaignRepository
import jp.kenshopocket.app.data.NotificationEvent
import jp.kenshopocket.app.domain.reminder.ReminderEngine
import jp.kenshopocket.app.platform.notification.AndroidReminderGateway
import jp.kenshopocket.app.platform.notification.ReminderWorker
import kotlinx.coroutines.*

class KenshoPocketApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val reminders by lazy { ReminderEngine(database, AndroidReminderGateway(this)) }
    val repository by lazy { CampaignRepository(database, onChanged = {
        applicationScope.launch {
            try { ReminderWorker.enqueue(this@KenshoPocketApplication) }
            catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) {
                // The data save already committed. Keep it successful and report scheduling failure separately.
                database.reminderDao().addEvent(NotificationEvent(occurrenceKey = null, occurredAt = System.currentTimeMillis(), reason = "SCHEDULE_FAILED"))
            }
        }
    }) }
    override fun onCreate() {
        super.onCreate()
        ReminderWorker.periodic(this)
    }
}
