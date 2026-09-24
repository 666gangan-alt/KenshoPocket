package jp.kenshopocket.app.platform.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.kenshopocket.app.MainActivity
import jp.kenshopocket.app.R
import jp.kenshopocket.app.data.*
import jp.kenshopocket.app.domain.reminder.*

class AndroidReminderGateway(private val context: Context) : ReminderGateway {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun access(): NotificationAccess {
        val runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return NotificationAccess(runtime && NotificationManagerCompat.from(context).areNotificationsEnabled(), manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE, Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(), !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun schedule(at: Long?, preferExact: Boolean): String {
        val pending = alarmIntent()
        if (at == null) { alarms.cancel(pending); return "NONE" }
        if (preferExact && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return "EXACT"
            } catch (_: SecurityException) { /* Permission revoked between check and scheduling. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return if (preferExact) "INEXACT_FALLBACK" else "INEXACT"
    }

    private fun activityIntent(key: String, action: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .setData(Uri.Builder().scheme("kenshopocket").authority("notification").appendPath(key).appendPath(action).build())
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun post(occurrence: ReminderOccurrence, campaign: CampaignEntity) {
        requireNotificationPermission()
        val text = if (occurrence.purpose == "REPEAT_ENTRY") context.getString(R.string.reminder_repeat_body)
        else context.getString(R.string.reminder_deadline_body, campaign.deadlineDate.orEmpty(), campaign.deadlineTime ?: context.getString(R.string.reminder_time_unknown))
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.reminder_private)).build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(campaign.title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setAutoCancel(true).setOnlyAlertOnce(true).setGroup("kensho-reminders")
            .setContentIntent(activityIntent(occurrence.logicalKey, "DETAIL"))
            .addAction(0, context.getString(R.string.open_application), activityIntent(occurrence.logicalKey, "OPEN"))
        val snooze = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE)
            .setData(Uri.Builder().scheme("kenshopocket").authority("snooze").appendPath(occurrence.logicalKey).build()).putExtra(EXTRA_KEY, occurrence.logicalKey)
        builder.addAction(0, context.getString(R.string.reminder_snooze), PendingIntent.getBroadcast(context, 0, snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // Stable tag replaces a retry or same-campaign batch instead of duplicating it.
        manager.notify(occurrence.campaignId, 0, builder.build())
    }

    override fun cancel(campaignId: String) = manager.cancel(campaignId, 0)

    override fun test() {
        requireNotificationPermission()
        manager.notify("kensho-test", 0, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_test)).setContentText(context.getString(R.string.reminder_test_body))
            .setContentIntent(PendingIntent.getActivity(context, 100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }

    private fun requireNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("NOTIFICATION_PERMISSION")
    }

    companion object {
        const val CHANNEL = "application-reminders"
        const val ACTION_ALARM = "jp.kenshopocket.app.REMINDER_ALARM"
        const val ACTION_SNOOZE = "jp.kenshopocket.app.REMINDER_SNOOZE"
        const val ACTION_OPEN = "jp.kenshopocket.app.REMINDER_OPEN"
        const val EXTRA_KEY = "reminderKey"
        const val EXTRA_ACTION = "reminderAction"
    }
}
