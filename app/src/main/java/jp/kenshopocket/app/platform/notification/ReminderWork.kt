package jp.kenshopocket.app.platform.notification

import android.content.*
import androidx.work.*
import java.util.concurrent.TimeUnit
import jp.kenshopocket.app.KenshoPocketApplication
import kotlinx.coroutines.*

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as KenshoPocketApplication).reminders.refresh()
        Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("reminder-reconcile", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build())
        }
        fun periodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder-daily", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS).build())
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(AndroidReminderGateway.ACTION_ALARM, AndroidReminderGateway.ACTION_SNOOZE)) {
            ReminderWorker.enqueue(context)
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as KenshoPocketApplication
        app.applicationScope.launch {
            try {
                withTimeout(8_000) {
                    if (intent.action == AndroidReminderGateway.ACTION_SNOOZE) intent.getStringExtra(AndroidReminderGateway.EXTRA_KEY)?.let { app.reminders.snooze(it) }
                    else app.reminders.refresh(dispatchDue = true)
                }
            } catch (_: Exception) {
                ReminderWorker.enqueue(context)
            } finally { pending.finish() }
        }
    }
}
