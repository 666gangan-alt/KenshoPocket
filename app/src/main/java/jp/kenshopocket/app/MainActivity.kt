package jp.kenshopocket.app

import android.os.Bundle
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.widget.Toast
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
                try {
                    CustomTabsIntent.Builder().build().launchUrl(this, launch.urlSnapshot.toUri())
                } catch (_: RuntimeException) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, launch.urlSnapshot.toUri()))
                    } catch (_: RuntimeException) {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("応募URL", launch.urlSnapshot))
                        viewModel.failPendingLaunch()
                        Toast.makeText(this, "応募URLをコピーしました。対応するブラウザで開いてください。", Toast.LENGTH_LONG).show()
                    }
                }
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
