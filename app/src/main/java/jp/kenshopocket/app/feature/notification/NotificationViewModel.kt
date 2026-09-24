package jp.kenshopocket.app.feature.notification

import androidx.lifecycle.*
import jp.kenshopocket.app.R
import jp.kenshopocket.app.data.*
import jp.kenshopocket.app.domain.reminder.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReminderEditor(val rule: CampaignReminderRule, val entry: EntryRuleEntity?)

class NotificationViewModel(private val engine: ReminderEngine) : ViewModel() {
    val settings = engine.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val next = engine.next.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val titles = engine.titles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val events = engine.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val access = MutableStateFlow(engine.access())
    val editor = MutableStateFlow<ReminderEditor?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try { withContext(Dispatchers.IO) { block() }; access.value = engine.access() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message.value = R.string.reminder_error }
        finally { busy.value = false }
    }
    fun refresh() = act { engine.refresh() }
    fun load(id: String) = viewModelScope.launch {
        try { editor.value = withContext(Dispatchers.IO) { ReminderEditor(engine.rule(id), engine.entryRule(id)) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message.value = R.string.reminder_error }
    }
    fun save(value: ReminderSettings) = act { engine.saveSettings(value); message.value = R.string.reminder_saved }
    fun save(value: CampaignReminderRule, confirmed: Boolean) = act { engine.saveRule(value, confirmed); editor.value = ReminderEditor(engine.rule(value.campaignId), engine.entryRule(value.campaignId)); message.value = R.string.reminder_saved }
    fun test() = act { message.value = if (engine.test()) R.string.reminder_test_sent else R.string.reminder_test_blocked }
    fun clear() = act { engine.clearEvents() }
    class Factory(private val engine: ReminderEngine) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return NotificationViewModel(engine) as T
        }
    }
}
