package jp.kenshopocket.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import jp.kenshopocket.app.data.CampaignCard
import jp.kenshopocket.app.data.CampaignRepository
import jp.kenshopocket.app.data.LaunchSessionEntity
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditDraft(val id: String, val title: String, val url: String, val deadline: String)

class MainViewModel(
    private val repository: CampaignRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val cards: StateFlow<List<CampaignCard>> = repository.observeCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val confirmation = MutableStateFlow<LaunchSessionEntity?>(null)
    val message = MutableStateFlow<String?>(null)
    val editDraft = MutableStateFlow<EditDraft?>(null)
    val notificationRequest = savedState.getStateFlow<String?>("notificationKey", null)
    fun notification(key: String, action: String) {
        savedState["notificationKey"] = key
        savedState["notificationAction"] = action
    }
    suspend fun consumeNotification(): Pair<String, String>? {
        val key = savedState.get<String>("notificationKey") ?: return null
        val action = savedState.get<String>("notificationAction") ?: "DETAIL"
        val id = repository.notificationCampaign(key)
        savedState["notificationKey"] = null
        if (id == null) { message.value = "この項目は削除されています"; return null }
        return id to action
    }
    private var externalPageObserved = false

    fun addCampaign(title: String, url: String, deadline: String?, onDone: () -> Unit) = viewModelScope.launch {
        try {
            repository.addCampaign(title, url, deadline)
            savedState.get<String>("editDraftId")?.let { repository.deleteDraft(it) }
            savedState["editDraftId"] = null
            editDraft.value = null
            onDone()
        } catch (error: RuntimeException) {
            message.value = error.message
        }
    }

    fun loadEditDraft() = viewModelScope.launch {
        if (editDraft.value != null) return@launch
        repository.drafts().asSequence().mapNotNull { stored ->
            runCatching {
                val json = JSONObject(stored.payloadJson)
                if (json.optString("kind") != "MANUAL_CAMPAIGN") return@runCatching null
                EditDraft(stored.id, json.optString("title"), json.optString("url"), json.optString("deadline"))
            }.getOrNull()
        }.firstOrNull()?.let {
            savedState["editDraftId"] = it.id
            editDraft.value = it
        }
    }

    fun saveEditDraft(title: String, url: String, deadline: String) = viewModelScope.launch {
        val id = savedState.get<String>("editDraftId") ?: UUID.randomUUID().toString().also { savedState["editDraftId"] = it }
        val draft = EditDraft(id, title, url, deadline)
        repository.saveDraft(id, JSONObject().put("kind", "MANUAL_CAMPAIGN").put("title", title).put("url", url).put("deadline", deadline).toString())
        editDraft.value = draft
    }

    suspend fun prepareLaunch(campaignId: String, originRoute: String): LaunchSessionEntity? = runCatching {
        repository.prepareLaunch(campaignId, originRoute).also {
            savedState["pendingLaunchId"] = it.id
            savedState["pendingLaunchCampaignId"] = it.campaignId
        }
    }.onFailure { message.value = it.message }.getOrNull()

    fun onActivityStopped() {
        if (savedState.get<String>("pendingLaunchId") != null) externalPageObserved = true
    }

    fun onActivityResumed() {
        if (!externalPageObserved && confirmation.value != null) return
        viewModelScope.launch {
            val launchId = savedState.get<String>("pendingLaunchId") ?: repository.pendingLaunches()
                .filter { it.state in setOf("LAUNCHED", "NEEDS_CONFIRMATION") }
                .lastOrNull()?.also {
                savedState["pendingLaunchId"] = it.id
                savedState["pendingLaunchCampaignId"] = it.campaignId
            }?.id ?: return@launch
            val launch = repository.launch(launchId)
            if (launch != null && launch.state in setOf("LAUNCHED", "NEEDS_CONFIRMATION", "REVIEW_LATER")) {
                confirmation.value = launch.copy(state = "NEEDS_CONFIRMATION")
            } else if (launch == null || launch.confirmedEntryId != null || launch.state in setOf("NOT_APPLIED", "APPLIED")) {
                clearPendingLaunch()
            }
        }
        externalPageObserved = false
    }

    fun failPendingLaunch() = viewModelScope.launch {
        runCatching { savedState.get<String>("pendingLaunchId")?.let { repository.markNotApplied(it) } }
        clearPendingLaunch()
        message.value = "応募ページを開けませんでした。URLを確認してください。"
    }

    private fun clearPendingLaunch() {
        confirmation.value = null
        savedState["pendingLaunchId"] = null
        savedState["pendingLaunchCampaignId"] = null
    }

    fun resolveConfirmation(action: String) = viewModelScope.launch {
        val launch = confirmation.value ?: return@launch
        runCatching {
            when (action) {
                "APPLIED" -> repository.confirmApplied(launch.id)
                "NOT_APPLIED" -> repository.markNotApplied(launch.id)
                else -> repository.markLaunchForReview(launch.id)
            }
        }.onFailure { message.value = it.message }
        clearPendingLaunch()
    }

    fun clearMessage() { message.value = null }

    class Factory(private val repository: CampaignRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, extras.createSavedStateHandle()) as T
        }
    }
}
