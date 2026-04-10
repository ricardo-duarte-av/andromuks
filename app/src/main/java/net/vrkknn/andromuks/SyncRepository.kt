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
import java.util.concurrent.ConcurrentLinkedQueue
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
    INIT_COMPLETE,
    /**
     * Sentinel enqueued by [SyncRepository.enqueueDrainSentinel]. Not a real message — when the
     * pipeline processes it, all previously-queued sync_completes have been dispatched, so the
     * registered drain callback is invoked and the sentinel is discarded.
     */
    DRAIN_SENTINEL,
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

    // Buffer for sync_complete messages that arrive when no VM is attached yet (e.g. service
    // auto-started without UI). Bounded to prevent unbounded memory use. Epoch-tracked so messages
    // from a previous connection cycle are never replayed after a disconnect.
    private val noVmBufferLock = Any()
    private val noVmBuffer = ArrayDeque<PendingSyncComplete>()
    private const val MAX_NO_VM_BUFFER = 500
    @Volatile private var noVmBufferEpoch = 0

    // Callbacks for DRAIN_SENTINEL messages, in FIFO order matching the sentinel enqueue order.
    private val drainSentinelCallbacks = ConcurrentLinkedQueue<() -> Unit>()

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

    /**
     * Discard any buffered no-VM sync_completes and advance the epoch so they are never replayed.
     * Called by [WebSocketService] on [ConnectionState.Disconnected] so that messages from a stale
     * connection are not replayed against a new session's VM.
     */
    fun clearSyncBuffer() {
        synchronized(noVmBufferLock) {
            if (noVmBuffer.isNotEmpty()) {
                Log.i(TAG, "clearSyncBuffer: discarding ${noVmBuffer.size} buffered message(s) (epoch $noVmBufferEpoch → ${noVmBufferEpoch + 1})")
                noVmBuffer.clear()
            }
            noVmBufferEpoch++
        }
    }

    /**
     * Re-enqueue messages that were buffered while no VM was attached, so they are processed by
     * the VM that just attached.  Must be called after the VM has set [AppViewModel.initialSyncPhase]
     * to true (done inside [AppViewModel.attachToExistingWebSocketIfAvailable]) so that
     * [SyncRoomsCoordinator.updateRoomsFromSyncJsonAsyncBody] processes them immediately instead of
     * re-queuing them in [AppViewModel.initialSyncCompleteQueue].
     *
     * NOTE: prefer [takeBufferedMessages] when you need to process them synchronously before
     * navigating (avoids the rooms-pop-in-one-by-one problem on first open after background start).
     */
    fun triggerBufferedSyncDrain() {
        val epochAtDrain: Int
        val toReplay: List<PendingSyncComplete>
        synchronized(noVmBufferLock) {
            epochAtDrain = noVmBufferEpoch
            if (noVmBuffer.isEmpty()) return
            toReplay = noVmBuffer.toList()
            noVmBuffer.clear()
        }
        Log.i(TAG, "triggerBufferedSyncDrain: re-enqueuing ${toReplay.size} buffered sync_complete(s) (epoch $epochAtDrain)")
        for (msg in toReplay) {
            val result = syncCompleteChannel.trySend(msg)
            if (!result.isSuccess) {
                Log.e(TAG, "triggerBufferedSyncDrain: channel failed, ${toReplay.size - toReplay.indexOf(msg)} message(s) lost")
                break
            }
        }
    }

    /**
     * Atomically removes and returns all buffered no-VM messages as raw (jsonString, hint) pairs,
     * without re-enqueuing them to the async pipeline channel.  Use this when the caller wants to
     * process the messages synchronously (e.g. before firing navigation) so that the room list is
     * fully populated before the user sees it.  The epoch is NOT advanced; only [clearSyncBuffer]
     * does that.
     */
    fun takeBufferedMessages(): List<Pair<String, IncomingWebSocketHint>> {
        synchronized(noVmBufferLock) {
            if (noVmBuffer.isEmpty()) return emptyList()
            val taken = noVmBuffer.map { it.jsonString to it.hint }
            noVmBuffer.clear()
            Log.i(TAG, "takeBufferedMessages: took ${taken.size} message(s) for direct processing (epoch $noVmBufferEpoch)")
            return taken
        }
    }

    /**
     * Enqueues a sentinel marker at the current tail of [syncCompleteChannel].  Because the pipeline
     * is single-threaded (FIFO), when the sentinel is processed ALL sync_completes that were enqueued
     * before it have already been dispatched to the attached VM.  [onDrained] is invoked on the
     * pipeline's IO thread — callers must dispatch to the appropriate thread themselves.
     *
     * Use this in [AppViewModel.attachToExistingWebSocketIfAvailable] to know exactly when it is safe
     * to drain [AppViewModel.initialSyncCompleteQueue] and fire navigation, without racing against
     * messages still in transit through the pipeline.
     */
    fun enqueueDrainSentinel(onDrained: () -> Unit) {
        drainSentinelCallbacks.add(onDrained)
        val result = syncCompleteChannel.trySend(PendingSyncComplete("{}", IncomingWebSocketHint.DRAIN_SENTINEL))
        if (!result.isSuccess) {
            // Channel closed — remove the orphaned callback and invoke immediately so the
            // caller is not stuck waiting for a sentinel that will never be processed.
            drainSentinelCallbacks.remove(onDrained)
            Log.e(TAG, "enqueueDrainSentinel: channel send failed — invoking callback immediately")
            onDrained()
        }
    }

    private suspend fun processSyncCompletePipeline(msg: PendingSyncComplete) {
        // Sentinel: not a real message — just notify the waiting VM that all prior messages
        // have been dispatched and then discard.
        if (msg.hint == IncomingWebSocketHint.DRAIN_SENTINEL) {
            val callback = drainSentinelCallbacks.poll()
            if (callback != null) {
                callback()
            } else {
                Log.w(TAG, "processSyncCompletePipeline: DRAIN_SENTINEL with no matching callback")
            }
            return
        }
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
            // No VM attached yet — buffer instead of drop.  A VM will call triggerBufferedSyncDrain()
            // via attachToExistingWebSocketIfAvailable() once it is ready to process these messages.
            synchronized(noVmBufferLock) {
                if (noVmBuffer.size < MAX_NO_VM_BUFFER) {
                    noVmBuffer.addLast(msg)
                    Log.w(TAG, "sync_complete: no VM (buffered ${noVmBuffer.size}/$MAX_NO_VM_BUFFER, epoch $noVmBufferEpoch)")
                } else {
                    Log.w(TAG, "sync_complete: no VM and buffer full ($MAX_NO_VM_BUFFER), message dropped")
                }
            }
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
