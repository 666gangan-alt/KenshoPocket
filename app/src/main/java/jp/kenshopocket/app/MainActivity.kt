package jp.kenshopocket.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import jp.kenshopocket.app.feature.notification.NotificationViewModel
import jp.kenshopocket.app.platform.notification.AndroidReminderGateway
import jp.kenshopocket.app.platform.notification.ReminderWorker

class MainActivity : ComponentActivity() {
    private val notifications: NotificationViewModel by viewModels { NotificationViewModel.Factory((application as KenshoPocketApplication).reminders) }
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as KenshoPocketApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptNotification(intent)
        setContent {
            KenshoPocketApp(viewModel, notifications) { campaignId, route ->
                val launch = viewModel.prepareLaunch(campaignId, route) ?: return@KenshoPocketApp
                runCatching { CustomTabsIntent.Builder().build().launchUrl(this, launch.urlSnapshot.toUri()) }
                    .onFailure { viewModel.resolveConfirmation("NOT_APPLIED") }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotification(intent)
    }

    private fun acceptNotification(intent: Intent) {
        if (intent.action == AndroidReminderGateway.ACTION_OPEN) {
            val key = intent.getStringExtra(AndroidReminderGateway.EXTRA_KEY) ?: return
            val action = intent.getStringExtra(AndroidReminderGateway.EXTRA_ACTION) ?: return
            if (key.length <= 1000 && action in setOf("OPEN", "DETAIL")) viewModel.notification(key, action)
        }
    }

    override fun onStop() {
        viewModel.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
        ReminderWorker.enqueue(this)
    }
}
