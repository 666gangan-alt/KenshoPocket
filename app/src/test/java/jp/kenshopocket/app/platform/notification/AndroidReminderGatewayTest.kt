package jp.kenshopocket.app.platform.notification

import android.app.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.kenshopocket.app.MainActivity
import jp.kenshopocket.app.data.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AndroidReminderGatewayTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun schedulesOneImmutableExplicitAlarmAndCancelsIt() {
        val gateway = AndroidReminderGateway(context)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals("INEXACT", gateway.schedule(10_000, false))
        gateway.schedule(20_000, false)
        assertEquals(1, alarms.scheduledAlarms.size)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(20_000L, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        val pending = shadowOf(alarm.operation)
        assertTrue(pending.isImmutable)
        assertTrue(pending.isBroadcast)
        assertEquals(ReminderReceiver::class.java.name, pending.savedIntent.component!!.className)
        gateway.schedule(null, false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun postUsesStableTagPrivateContentAndActivityActionsWithoutUrlExtras() {
        val gateway = AndroidReminderGateway(context)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val campaign = CampaignEntity("c", "Sample", "2026-09-27", null, true, "ONCE", createdAt = 1, updatedAt = 1)
        val occurrence = ReminderOccurrence("c:1", "c", "APPLICATION", 1, 1, 1, 10000, null, null, updatedAt = 1)
        gateway.post(occurrence, campaign); gateway.post(occurrence, campaign)
        assertEquals(1, manager.size())
        val notification = manager.getNotification("c", 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        val detail = shadowOf(notification.contentIntent)
        assertTrue(detail.isActivity); assertTrue(detail.isImmutable)
        assertEquals(MainActivity::class.java.name, detail.savedIntent.component!!.className)
        assertEquals("DETAIL", detail.savedIntent.getStringExtra(AndroidReminderGateway.EXTRA_ACTION))
        val open = shadowOf(notification.actions[0].actionIntent)
        assertTrue(open.isActivity); assertTrue(open.isImmutable)
        assertEquals("OPEN", open.savedIntent.getStringExtra(AndroidReminderGateway.EXTRA_ACTION))
        assertEquals(setOf(AndroidReminderGateway.EXTRA_KEY, AndroidReminderGateway.EXTRA_ACTION), open.savedIntent.extras!!.keySet())
        assertNotEquals(open.savedIntent.data, detail.savedIntent.data)
        val snooze = shadowOf(notification.actions[1].actionIntent)
        assertTrue(snooze.isBroadcast); assertTrue(snooze.isImmutable)
        gateway.cancel("c"); assertEquals(0, manager.size())
    }
}
