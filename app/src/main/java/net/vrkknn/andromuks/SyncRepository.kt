package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
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
     * Primary (or sole) processor finished applying a [sync_complete] to shared stores ([RoomListCache], etc.).
     * Non-primary [AppViewModel] instances should refresh local state from singletons.
     */
    data class RoomListSingletonReplicated(val processorId: String) : SyncEvent()

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
    val viewModel: WeakReference<AppViewModel>,
    @Volatile var isPrimary: Boolean
) {
    fun get(): AppViewModel? = viewModel.get()
}

object SyncRepository {

    private const val TAG = "SyncRepository"

    private val registryLock = Any()

    private val attachedViewModels = ConcurrentHashMap<String, ViewModelEntry>()

    private val primaryViewModelIdRef = AtomicReference<String?>(null)

    /**
     * Single ordered consumer for [sync_complete]: one ingest/parse/apply per server message (not per attached VM).
     */
    private data class PendingSyncComplete(val jsonString: String, val hint: IncomingWebSocketHint)

    private val syncCompleteChannel = Channel<PendingSyncComplete>(Channel.UNLIMITED)
    private val syncPipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        syncPipelineScope.launch {
            for (msg in syncCompleteChannel) {
                try {
                    processSyncCompletePipeline(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "sync_complete pipeline failed", e)
                }
            }
        }
    }

    /**
     * Enqueue a single [sync_complete] for processing on the canonical [AppViewModel] (primary if set, else first attached).
     * Does not fan out [SyncEvent.IncomingWebSocketMessage] for this command.
     */
    fun enqueueSyncComplete(jsonString: String, hint: IncomingWebSocketHint) {
        val result = syncCompleteChannel.trySend(PendingSyncComplete(jsonString, hint))
        if (!result.isSuccess) {
            Log.e(TAG, "enqueueSyncComplete: channel closed or failed")
        }
    }

    private suspend fun processSyncCompletePipeline(msg: PendingSyncComplete) {
        val json = JSONObject(msg.jsonString)
        if (msg.hint == IncomingWebSocketHint.SYNC_COMPLETE_AFTER_RESUME) {
            withContext(Dispatchers.Main) {
                getAttachedViewModels().forEach { vm ->
                    vm.onInitComplete()
                }
            }
        }
        val target = resolveSyncProcessingViewModel()
        if (target == null) {
            Log.w(TAG, "sync_complete: no AppViewModel to process (dropped)")
            return
        }
        val job = target.viewModelScope.launch {
            target.applySyncCompleteFromRepository(json)
        }
        job.join()
    }

    /**
     * Prefer primary; otherwise first attached instance (e.g. bubble-only process).
     */
    private fun resolveSyncProcessingViewModel(): AppViewModel? {
        getPrimaryViewModelId()?.let { id ->
            getViewModel(id)?.let { return it }
        }
        return getAttachedViewModels().firstOrNull()
    }

    private val _events = MutableSharedFlow<SyncEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _offlineMode = MutableStateFlow(false)
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    fun getPrimaryViewModelId(): String? = primaryViewModelIdRef.get()

    private fun setPrimaryViewModelId(id: String?) {
        primaryViewModelIdRef.set(id)
    }

    /**
     * (Re)attaches the weak ref for this id. [isPrimary] must stay in sync with [getPrimaryViewModelId]:
     * [registerViewModel] sets primary, then [registerReceiveCallback] calls this — if we always used
     * `isPrimary = false` here, we'd wipe the primary flag and trigger endless "dead primary" promotion.
     */
    fun attachViewModel(viewModelId: String, viewModel: AppViewModel) {
        synchronized(registryLock) {
            val isPrimary = (getPrimaryViewModelId() == viewModelId)
            attachedViewModels[viewModelId] = ViewModelEntry(WeakReference(viewModel), isPrimary = isPrimary)
        }
    }

    /**
     * Removes registry entries whose [AppViewModel] was GC'd; promotes primary if needed.
     */
    private fun pruneStaleViewModelEntries(reason: String) {
        val primaryRemoved: Boolean
        synchronized(registryLock) {
            val stale = attachedViewModels.filter { it.value.get() == null }.keys.toList()
            primaryRemoved = stale.any { it == getPrimaryViewModelId() }
            for (id in stale) {
                attachedViewModels.remove(id)
            }
            if (primaryRemoved) {
                setPrimaryViewModelId(null)
            }
        }
        if (primaryRemoved) {
            promoteNextPrimary("stale_weak_ref:$reason")
        }
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
                    if (entry.get() != null) {
                        entry.isPrimary = (id == viewModelId)
                    }
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
                val aliveIds = attachedViewModels.filter { it.value.get() != null }.keys.toList()
                if (aliveIds.isEmpty()) {
                    setPrimaryViewModelId(null)
                    null
                } else {
                    val candidate = aliveIds.firstOrNull { it.startsWith("AppViewModel_0") } ?: aliveIds.first()
                    for ((id, e) in attachedViewModels) {
                        if (e.get() != null) {
                            e.isPrimary = (id == candidate)
                        }
                    }
                    setPrimaryViewModelId(candidate)
                    candidate
                }
            }
        } ?: return
        try {
            android.util.Log.i("SyncRepository", "promoteNextPrimary: $candidateId ($reason)")
            val vmToNotify = synchronized(registryLock) { attachedViewModels[candidateId]?.get() }
            vmToNotify?.onPromotedToPrimary()
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "promoteNextPrimary: notify failed", e)
        }
    }

    fun isViewModelAttached(viewModelId: String): Boolean =
        attachedViewModels[viewModelId]?.get() != null

    fun getViewModel(viewModelId: String): AppViewModel? {
        pruneStaleViewModelEntries("getViewModel")
        return attachedViewModels[viewModelId]?.get()
    }

    fun getAttachedViewModels(): List<AppViewModel> {
        pruneStaleViewModelEntries("getAttachedViewModels")
        return attachedViewModels.values.mapNotNull { it.get() }
    }

    fun getRegisteredViewModelIds(): List<String> = attachedViewModels.keys.toList().sorted()

    fun getRegisteredViewModelInfos(): List<ViewModelRegistryInfo> =
        attachedViewModels.mapNotNull { (id, e) ->
            if (e.get() != null) ViewModelRegistryInfo(id, e.isPrimary) else null
        }

    fun isViewModelRegistered(viewModelId: String): Boolean =
        attachedViewModels[viewModelId]?.get() != null

    /**
     * True when primary id is set, the entry exists, and it is still marked primary.
     */
    fun isPrimaryEntryAlive(): Boolean {
        val id = getPrimaryViewModelId() ?: return false
        val e = attachedViewModels[id] ?: return false
        return e.isPrimary && e.get() != null
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

    fun updateConnectionState(state: ConnectionState) {
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
