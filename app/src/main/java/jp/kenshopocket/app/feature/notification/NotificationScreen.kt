package jp.kenshopocket.app.feature.notification

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import jp.kenshopocket.app.R
import jp.kenshopocket.app.data.*
import jp.kenshopocket.app.platform.notification.AndroidReminderGateway

@Composable
fun NotificationScreen(model: NotificationViewModel, campaignId: String? = null, back: (() -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings by model.settings.collectAsState()
    val editor by model.editor.collectAsState()
    val access by model.access.collectAsState()
    val next by model.next.collectAsState()
    val titles by model.titles.collectAsState()
    val events by model.events.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(campaignId) { if (campaignId == null) model.refresh() else model.load(campaignId) }
    var clear by remember { mutableStateOf(false) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (_: RuntimeException) { model.message.value = R.string.reminder_error }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (back != null) TextButton(onClick = back) { Text(stringResource(R.string.reminder_back)) }
            Text(stringResource(R.string.reminder_title), style = MaterialTheme.typography.headlineSmall)
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
        }
        if (campaignId != null) {
            item {
                if (editor?.rule?.campaignId == campaignId) RuleEditor(editor!!, busy, model::save)
                else Text(stringResource(R.string.reminder_loading))
            }
        } else {
            item { settings?.let { SettingsEditor(it, busy, model::save) } }
        }
        item {
            Text(stringResource(R.string.reminder_permission_reason))
            Text(stringResource(R.string.reminder_permission, stringResource(if (access.permission) R.string.reminder_on else R.string.reminder_off)))
            Text(stringResource(R.string.reminder_channel_status, stringResource(if (access.channel) R.string.reminder_on else R.string.reminder_off)))
            Text(stringResource(R.string.reminder_exact_status, stringResource(if (access.exact) R.string.reminder_on else R.string.reminder_off)))
            Text(stringResource(R.string.reminder_battery, stringResource(if (access.batteryRestricted) R.string.reminder_on else R.string.reminder_off)))
            if (Build.VERSION.SDK_INT >= 33 && !access.permission) Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.reminder_request)) }
            TextButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(stringResource(R.string.reminder_system_settings)) }
            TextButton(onClick = { open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, AndroidReminderGateway.CHANNEL)) }) { Text(stringResource(R.string.reminder_channel_settings)) }
            if (Build.VERSION.SDK_INT >= 31 && settings?.preferExact == true) TextButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.reminder_exact_settings)) }
            Text(stringResource(R.string.reminder_limits))
            Text(stringResource(R.string.reminder_snooze_limit))
            Button(onClick = { model.test() }, enabled = !busy) { Text(stringResource(R.string.reminder_test)) }
            OutlinedButton(onClick = { model.refresh() }, enabled = !busy) { Text(stringResource(R.string.reminder_refresh)) }
        }
        item { Text(stringResource(R.string.reminder_next), style = MaterialTheme.typography.titleMedium) }
        val visible = next.filter { campaignId == null || it.campaignId == campaignId }
        if (visible.isEmpty()) item { Text(stringResource(R.string.reminder_no_next)) }
        items(visible, key = { it.logicalKey }) { value ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(titles[value.campaignId] ?: stringResource(R.string.reminder_missing))
                Text(timeText(value.effectiveAt))
                Text(stringResource(reasonResource(value.state)))
                Text(stringResource(when (value.scheduleMode) { "EXACT" -> R.string.reminder_exact; "INEXACT_FALLBACK" -> R.string.reminder_fallback; else -> R.string.reminder_standard }))
            } }
        }
        item { Text(stringResource(R.string.reminder_history), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.reminder_history_explanation)) }
        items(events, key = { it.id }) { value -> Text("${timeText(value.occurredAt)}　${stringResource(reasonResource(value.reason))}") }
        item { TextButton(onClick = { clear = true }, enabled = !busy) { Text(stringResource(R.string.reminder_clear)) } }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, text = { Text(stringResource(R.string.reminder_clear_confirm)) }, confirmButton = { TextButton(onClick = { clear = false; model.clear() }) { Text(stringResource(R.string.reminder_clear)) } }, dismissButton = { TextButton(onClick = { clear = false }) { Text(stringResource(R.string.reminder_cancel)) } })
}

@Composable private fun SettingsEditor(value: ReminderSettings, busy: Boolean, save: (ReminderSettings) -> Unit) {
    var form by rememberSaveable(value, stateSaver = settingsSaver) { mutableStateOf(value) }
    Toggle(R.string.reminder_enable, form.enabled) { form = form.copy(enabled = it) }
    Toggle(R.string.reminder_prefer_exact, form.preferExact) { form = form.copy(preferExact = it) }
    Toggle(R.string.reminder_quiet, form.quietEnabled) { form = form.copy(quietEnabled = it) }
    if (form.quietEnabled) {
        TimeField(R.string.reminder_quiet_start, form.quietStart) { form = form.copy(quietStart = it) }
        TimeField(R.string.reminder_quiet_end, form.quietEnd) { form = form.copy(quietEnd = it) }
    }
    Button(onClick = { save(form) }, enabled = !busy) { Text(stringResource(R.string.reminder_save)) }
}

@Composable private fun RuleEditor(value: ReminderEditor, busy: Boolean, save: (CampaignReminderRule, Boolean) -> Unit) {
    var form by rememberSaveable(value, stateSaver = ruleSaver) { mutableStateOf(value.rule) }
    var confirmed by rememberSaveable(value) { mutableStateOf(value.entry?.confirmed == true) }
    Toggle(R.string.reminder_rule, form.enabled) { form = form.copy(enabled = it) }
    Toggle(R.string.reminder_three_days, form.threeDays) { form = form.copy(threeDays = it) }
    Toggle(R.string.reminder_previous_day, form.previousDay) { form = form.copy(previousDay = it) }
    Toggle(R.string.reminder_same_day, form.sameDay) { form = form.copy(sameDay = it) }
    Toggle(R.string.reminder_two_hours, form.twoHours) { form = form.copy(twoHours = it) }
    TimeField(R.string.reminder_local_time, form.localTime) { form = form.copy(localTime = it) }
    if (value.entry?.mode in setOf("DAILY", "WEEKLY")) {
        val entry = value.entry!!
        Text(stringResource(R.string.reminder_rule_description, stringResource(if (entry.mode == "DAILY") R.string.reminder_daily else R.string.reminder_weekly), entry.zoneId, entry.resetLocalTime))
        Toggle(R.string.reminder_repeat_enable, form.repeatEnabled) { form = form.copy(repeatEnabled = it) }
        Toggle(R.string.reminder_confirm_rule, confirmed) { confirmed = it }
        TimeField(R.string.reminder_repeat_time, form.repeatTime) { form = form.copy(repeatTime = it) }
    }
    Button(onClick = { save(form, confirmed) }, enabled = !busy) { Text(stringResource(R.string.reminder_save)) }
}

@Composable private fun Toggle(label: Int, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(checked, change)
    }
}
@Composable private fun TimeField(label: Int, value: String, change: (String) -> Unit) = OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true)
private fun timeText(at: Long) = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm z").format(Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Tokyo")))
private fun reasonResource(reason: String): Int = when (reason) {
    "PENDING" -> R.string.reminder_pending
    "SCHEDULED" -> R.string.reminder_scheduled
    "POSTED", "TEST_POSTED" -> R.string.reminder_posted
    "BLOCKED_PERMISSION" -> R.string.reminder_blocked_permission
    "BLOCKED_CHANNEL" -> R.string.reminder_blocked_channel
    "SKIPPED_EXPIRED" -> R.string.reminder_skipped_expired
    "SKIPPED_ALREADY_APPLIED" -> R.string.reminder_skipped_applied
    "SKIPPED_QUIET_WINDOW" -> R.string.reminder_skipped_quiet
    "SKIPPED_PAST_ON_RECONCILE" -> R.string.reminder_past
    "CANCELLED", "REPLANNED" -> R.string.reminder_cancelled
    "SNOOZED" -> R.string.reminder_snoozed
    "DEFERRED_QUIET" -> R.string.reminder_deferred_quiet
    "POSTING" -> R.string.reminder_posting
    "SNOOZE_EXCEEDS_DEADLINE" -> R.string.reminder_snooze_failed
    "UNCONFIRMED_DEADLINE", "UNCONFIRMED_RULE" -> R.string.reminder_unconfirmed
    else -> R.string.reminder_failed
}

private val settingsSaver = listSaver<ReminderSettings, Any>(
    save = { listOf(it.id, it.enabled, it.preferExact, it.quietEnabled, it.quietStart, it.quietEnd) },
    restore = { ReminderSettings(it[0] as Int, it[1] as Boolean, it[2] as Boolean, it[3] as Boolean, it[4] as String, it[5] as String) },
)
private val ruleSaver = listSaver<CampaignReminderRule, Any>(
    save = { listOf(it.campaignId, it.enabled, it.threeDays, it.previousDay, it.sameDay, it.twoHours, it.localTime, it.repeatEnabled, it.repeatTime, it.revision) },
    restore = { CampaignReminderRule(it[0] as String, it[1] as Boolean, it[2] as Boolean, it[3] as Boolean, it[4] as Boolean, it[5] as Boolean, it[6] as String, it[7] as Boolean, it[8] as String, it[9] as Long) },
)
