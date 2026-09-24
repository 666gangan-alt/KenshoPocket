package jp.kenshopocket.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import jp.kenshopocket.app.data.CampaignCard
import jp.kenshopocket.app.data.CampaignRepository
import jp.kenshopocket.app.data.LaunchSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: CampaignRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val cards: StateFlow<List<CampaignCard>> = repository.observeCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val confirmation = MutableStateFlow<LaunchSessionEntity?>(null)
    val message = MutableStateFlow<String?>(null)
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
        runCatching { repository.addCampaign(title, url, deadline) }
            .onSuccess { onDone() }
            .onFailure { message.value = it.message }
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
        if (!externalPageObserved) return
        val launchId = savedState.get<String>("pendingLaunchId") ?: return
        val campaignId = savedState.get<String>("pendingLaunchCampaignId") ?: return
        viewModelScope.launch {
            repository.card(campaignId)?.url?.let { url ->
                confirmation.value = LaunchSessionEntity(launchId, campaignId, url.launchUrl, 0, "NEEDS_CONFIRMATION", "list", updatedAt = 0)
            }
        }
        externalPageObserved = false
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
        confirmation.value = null
        savedState["pendingLaunchId"] = null
        savedState["pendingLaunchCampaignId"] = null
    }

    fun clearMessage() { message.value = null }

    class Factory(private val repository: CampaignRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, extras.createSavedStateHandle()) as T
        }
    }
}
