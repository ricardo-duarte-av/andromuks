package net.vrkknn.andromuks

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide sync / WebSocket coordination. The WebSocket service emits events; any [AppViewModel]
 * that is attached collects them. Attachment is keyed by [viewModelId]; primary role is tracked here.
 */
sealed class SyncEvent {
    data class OfflineModeChanged(val isOffline: Boolean) : SyncEvent()
    data class ActivityLog(val event: String, val networkType: String?) : SyncEvent()
    data object ClearTimelineCachesRequested : SyncEvent()

    /**
     * Parsed WebSocket JSON (one direction: NetworkUtils → all attached ViewModels).
     * [hint] carries edge cases that must run per-VM (e.g. resume sync_complete).
     */
    data class IncomingWebSocketMessage(
        val jsonString: String,
        val hint: IncomingWebSocketHint = IncomingWebSocketHint.NONE
    ) : SyncEvent()
}

/**
 * Per-ViewModel handling for messages that need extra steps before/after the common path.
 */
enum class IncomingWebSocketHint {
    NONE,
    /** First sync_complete after reconnect with last_received_event — call [AppViewModel.onInitComplete] before room sync. */
    SYNC_COMPLETE_AFTER_RESUME,
    /** init_complete — each VM runs [AppViewModel.onInitComplete] on Main. */
    INIT_COMPLETE
}

data class ViewModelRegistryInfo(
    val viewModelId: String,
    val isPrimary: Boolean
)

data class ViewModelEntry(
    val viewModel: AppViewModel,
    @Volatile var isPrimary: Boolean
)

object SyncRepository {

    private val registryLock = Any()

    private val attachedViewModels = ConcurrentHashMap<String, ViewModelEntry>()

    private val primaryViewModelIdRef = AtomicReference<String?>(null)

    private val _events = MutableSharedFlow<SyncEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow<WebSocketService.WebSocketState>(WebSocketService.WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketService.WebSocketState> = _connectionState.asStateFlow()

    private val _offlineMode = MutableStateFlow(false)
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    fun getPrimaryViewModelId(): String? = primaryViewModelIdRef.get()

    private fun setPrimaryViewModelId(id: String?) {
        primaryViewModelIdRef.set(id)
    }

    fun attachViewModel(viewModelId: String, viewModel: AppViewModel) {
        attachedViewModels[viewModelId] = ViewModelEntry(viewModel, isPrimary = false)
    }

    /**
     * Updates primary flags for an already-attached ViewModel. Must run after [attachViewModel].
     */
    fun registerViewModel(viewModelId: String, isPrimary: Boolean): Boolean {
        synchronized(registryLock) {
            val e = attachedViewModels[viewModelId] ?: run {
                android.util.Log.w("SyncRepository", "registerViewModel: no entry for $viewModelId (attachViewModel first)")
                return false
            }
            if (isPrimary) {
                for ((id, entry) in attachedViewModels) {
                    entry.isPrimary = (id == viewModelId)
                }
                setPrimaryViewModelId(viewModelId)
            } else {
                e.isPrimary = false
                if (getPrimaryViewModelId() == viewModelId) {
                    setPrimaryViewModelId(null)
                }
            }
            return true
        }
    }

    /**
     * Removes attachment; if this was primary, promotes another attached ViewModel when possible.
     */
    fun detachViewModel(viewModelId: String): Boolean {
        val wasPrimary: Boolean
        synchronized(registryLock) {
            val removed = attachedViewModels.remove(viewModelId) ?: return false
            wasPrimary = removed.isPrimary
            if (getPrimaryViewModelId() == viewModelId) {
                setPrimaryViewModelId(null)
            }
        }
        if (wasPrimary) {
            promoteNextPrimary("primary_destroyed")
        }
        return true
    }

    /**
     * Clears primary role for an attached ViewModel (e.g. primary Activity finishing without full detach yet).
     */
    fun clearPrimaryFor(viewModelId: String) {
        synchronized(registryLock) {
            attachedViewModels[viewModelId]?.isPrimary = false
            if (getPrimaryViewModelId() == viewModelId) {
                setPrimaryViewModelId(null)
            }
        }
    }

    /**
     * Picks a primary among attached ViewModels (prefers `AppViewModel_0*`). Notifies via [AppViewModel.onPromotedToPrimary].
     */
    /**
     * Clears primary state for a stale id (e.g. health check) then runs [promoteNextPrimary].
     */
    fun clearStalePrimaryAndPromote(staleId: String, reason: String) {
        synchronized(registryLock) {
            attachedViewModels[staleId]?.isPrimary = false
            if (getPrimaryViewModelId() == staleId) {
                setPrimaryViewModelId(null)
            }
        }
        promoteNextPrimary(reason)
    }

    fun promoteNextPrimary(reason: String) {
        val candidateId = synchronized(registryLock) {
            if (attachedViewModels.isEmpty()) {
                android.util.Log.w("SyncRepository", "promoteNextPrimary: no attached ViewModels ($reason)")
                setPrimaryViewModelId(null)
                null
            } else {
                val candidate = attachedViewModels.keys.firstOrNull { it.startsWith("AppViewModel_0") }
                    ?: attachedViewModels.keys.first()
                for ((id, e) in attachedViewModels) {
                    e.isPrimary = (id == candidate)
                }
                setPrimaryViewModelId(candidate)
                candidate
            }
        } ?: return
        try {
            android.util.Log.i("SyncRepository", "promoteNextPrimary: $candidateId ($reason)")
            getViewModel(candidateId)?.onPromotedToPrimary()
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "promoteNextPrimary: notify failed", e)
        }
    }

    fun isViewModelAttached(viewModelId: String): Boolean = attachedViewModels.containsKey(viewModelId)

    fun getViewModel(viewModelId: String): AppViewModel? = attachedViewModels[viewModelId]?.viewModel

    fun getAttachedViewModels(): List<AppViewModel> = attachedViewModels.values.map { it.viewModel }

    fun getRegisteredViewModelIds(): List<String> = attachedViewModels.keys.toList().sorted()

    fun getRegisteredViewModelInfos(): List<ViewModelRegistryInfo> =
        attachedViewModels.map { ViewModelRegistryInfo(it.key, it.value.isPrimary) }

    fun isViewModelRegistered(viewModelId: String): Boolean = attachedViewModels.containsKey(viewModelId)

    /**
     * True when primary id is set, the entry exists, and it is still marked primary.
     */
    fun isPrimaryEntryAlive(): Boolean {
        val id = getPrimaryViewModelId() ?: return false
        val e = attachedViewModels[id] ?: return false
        return e.isPrimary
    }

    /**
     * Whether logged-in credentials exist (used where we previously required a "primary" reconnection callback).
     */
    fun hasCredentials(context: Context): Boolean {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val h = prefs.getString("homeserver_url", "") ?: ""
        val t = prefs.getString("gomuks_auth_token", "") ?: ""
        return h.isNotEmpty() && t.isNotEmpty()
    }

    fun emitEvent(event: SyncEvent) {
        if (!_events.tryEmit(event)) {
            android.util.Log.w("SyncRepository", "SyncEvent dropped (buffer full): ${event::class.simpleName}")
        }
    }

    fun emitIncomingWebSocketMessage(jsonString: String, hint: IncomingWebSocketHint = IncomingWebSocketHint.NONE) {
        emitEvent(SyncEvent.IncomingWebSocketMessage(jsonString, hint))
    }

    fun updateConnectionState(state: WebSocketService.WebSocketState) {
        _connectionState.value = state
    }

    fun setOfflineModeFlag(offline: Boolean) {
        _offlineMode.value = offline
    }

    fun emitOfflineModeChanged(isOffline: Boolean) {
        setOfflineModeFlag(isOffline)
        emitEvent(SyncEvent.OfflineModeChanged(isOffline))
    }

    fun emitActivityLog(event: String, networkType: String?) {
        emitEvent(SyncEvent.ActivityLog(event, networkType))
    }

    fun requestClearTimelineCaches() {
        emitEvent(SyncEvent.ClearTimelineCachesRequested)
    }
}
