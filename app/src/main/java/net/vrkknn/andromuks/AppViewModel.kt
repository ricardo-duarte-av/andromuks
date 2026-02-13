package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import net.vrkknn.andromuks.utils.ReceiptFunctions
import net.vrkknn.andromuks.utils.processReactionEvent
import okhttp3.WebSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.util.Collections
import net.vrkknn.andromuks.utils.IntelligentMediaCache

data class MemberProfile(
    val displayName: String?,
    val avatarUrl: String?
)

data class SharedMediaItem(
    val uri: Uri,
    val mimeType: String?
)

data class PendingSharePayload(
    val items: List<SharedMediaItem>,
    val text: String? = null
)

data class UserProfile(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
)

data class MentionEntry(
    val roomId: String,
    val eventId: String
)

data class MentionEvent(
    val mentionEntry: MentionEntry,
    val event: TimelineEvent,
    val roomName: String? = null,
    val roomAvatarUrl: String? = null,
    val replyToEvent: TimelineEvent? = null
)

/**
 * Represents a single version of a message (original or edit)
 */
data class MessageVersion(
    val eventId: String,
    val event: TimelineEvent,
    val timestamp: Long,
    val isOriginal: Boolean = false
)

/**
 * Stores the complete edit history and state of a message
 */
data class VersionedMessage(
    val originalEventId: String,
    val originalEvent: TimelineEvent,
    val versions: List<MessageVersion>,  // Sorted by timestamp (newest first)
    val redactedBy: String? = null,
    val redactionEvent: TimelineEvent? = null
)

data class RoomListUiState(
    val currentUserProfile: UserProfile?,
    val currentUserId: String,
    val imageAuthToken: String,
    val isProcessingPendingItems: Boolean,
    val spacesLoaded: Boolean,
    val initialSyncComplete: Boolean,
    val roomListUpdateCounter: Int,
    val roomSummaryUpdateCounter: Int,
    val currentSpaceId: String?,
    val notificationActionInProgress: Boolean,
    val timestampUpdateCounter: Int,
    val pendingSyncCompleteCount: Int = 0,
    val processedSyncCompleteCount: Int = 0
)

/**
 * Result of WebSocket operations to handle connection issues gracefully
 */
enum class WebSocketResult {
    SUCCESS,
    NOT_CONNECTED,
    CONNECTION_ERROR
}

class AppViewModel : ViewModel() {
    // Tracks which sender profiles have been processed per room to avoid duplicate fetches.
    // Used by RoomListScreen opportunistic profile loading.
    val processedSendersByRoom = mutableStateMapOf<String, MutableSet<String>>()

    companion object {
        // File name for user profile disk cache (used in SharedPreferences)
        private const val PROFILE_CACHE_FILE = "user_profiles_cache.json"
        
        // MEMORY MANAGEMENT: Constants for cache limits and cleanup
        private const val INITIAL_ROOM_LOAD_EVENTS = 100 // Events to load when opening a room
        private const val MAX_MEMBER_CACHE_SIZE = 50000
        private const val MAX_MESSAGE_VERSIONS_PER_EVENT = 50
        
        // PHASE 4: Counter for generating unique ViewModel IDs
        private var viewModelCounter = 0
        
        // PHASE 5.1: Constants for outgoing message queue
        private const val MAX_QUEUE_SIZE = 800 // Maximum queue size (raised to cover bulk pagination hydrates)
        private const val MAX_MESSAGE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        // Processed timeline state is now stored in RoomTimelineCache singleton (no size limit needed)
        
        // Initial paginate limit when opening a room to fetch events from server
        // Used when cache is empty or to fetch newer events when cache has data
        // Default: 100 events
        @JvmStatic
        var INITIAL_ROOM_PAGINATE_LIMIT = 100
        
        // FCM registration debounce window to prevent duplicate registrations
        private const val FCM_REGISTRATION_DEBOUNCE_MS = 5000L // 5 seconds debounce window
        
        // Notification reply deduplication window to prevent duplicate sends
        private const val NOTIFICATION_REPLY_DEDUP_WINDOW_MS = 5000L // 5 seconds deduplication window
    }
    

    /**
     * Build a one-shot snapshot of the current section from in-memory data.
     * Room summaries are no longer persisted to DB - they're built in-memory from sync_complete.
     */
    suspend fun buildSectionSnapshot(): RoomSection {
        // Use in-memory data directly - no DB queries needed
        return getCurrentRoomSection()
    }

    // PHASE 4: Unique ID for this ViewModel instance (for WebSocket callback registration)
    private val viewModelId: String = "AppViewModel_${viewModelCounter++}"
    
    private enum class InstanceRole {
        PRIMARY,
        BUBBLE,
        SECONDARY
    }
    
    private var instanceRole: InstanceRole = InstanceRole.SECONDARY
    
    fun markAsPrimaryInstance() {
        instanceRole = InstanceRole.PRIMARY
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Instance role set to PRIMARY for $viewModelId")
        
        // STEP 2.1: Register/update this ViewModel with service (as primary)
        // This updates the registration if it was already registered as secondary
        WebSocketService.registerViewModel(viewModelId, isPrimary = true)
        
        // CRITICAL: Register to receive WebSocket messages
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // PHASE 1.4 FIX: Register primary callbacks immediately when marked as primary
        // This ensures callbacks are available before WebSocket connection is established
        // The service can then properly detect that AppViewModel is available
        registerPrimaryCallbacks()
    }
    
    fun promoteToPrimaryIfNeeded(reason: String) {
        if (instanceRole == InstanceRole.PRIMARY) {
            return
        }
        val currentPrimary = WebSocketService.getPrimaryViewModelId()
        if (currentPrimary == null) {
            android.util.Log.i("Andromuks", "AppViewModel: No primary instance detected - promoting $viewModelId ($reason)")
            markAsPrimaryInstance()
            startWebSocketService()
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Primary instance already registered ($currentPrimary) - skipping promotion request from $viewModelId ($reason)")
        }
    }

    suspend fun awaitRoomDataReadiness(
        timeoutMs: Long = 15_000L,
        pollDelayMs: Long = 100L,
        requireInitComplete: Boolean = false,
        roomId: String? = null
    ): Boolean {
        android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: START - roomId=$roomId, timeoutMs=$timeoutMs, requireInitComplete=$requireInitComplete, currentRoomId=$currentRoomId")
        val startTime = System.currentTimeMillis()
        return withTimeoutOrNull(timeoutMs) {
            var pollCount = 0
            while (true) {
                pollCount++
                // REMOVED: profileReady check - profiles load in background, events render with fallback immediately
                // Events can render instantly with username/avatar fallback, profiles update when they arrive
                // REMOVED: spacesReady check - not needed, init_complete check suffices if websocket was not connected
                // If websocket was already connected, we don't need to check for anything, just attach and proceed
                val pendingReady = !isProcessingPendingItems
                val syncReady = initialSyncComplete
                val initReady = !requireInitComplete || initializationComplete
                
                // CRITICAL FIX: Also wait for timeline to finish loading if we're loading a specific room
                // This prevents the timeline from showing a spinner indefinitely when opened during sync processing
                val timelineReady = if (roomId != null && currentRoomId == roomId) {
                    // If we're loading this specific room, wait for loading to complete
                    // Timeline is ready if:
                    // 1. We're not loading (!isTimelineLoading) - either loaded or not started
                    // 2. OR we have events (timelineEvents.isNotEmpty()) - data is available even if still loading
                    // This ensures we don't wait forever if loading fails or is slow
                    !isTimelineLoading || timelineEvents.isNotEmpty()
                } else {
                    // Not loading a specific room, or room doesn't match - don't wait for timeline
                    true
                }
                
                if (pollCount % 10 == 0 || (!pendingReady || !syncReady || !initReady || !timelineReady)) {
                    // Log every 10 polls or when not ready
                    android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: Polling - roomId=$roomId, pollCount=$pollCount, pendingReady=$pendingReady, syncReady=$syncReady, initReady=$initReady, timelineReady=$timelineReady, isTimelineLoading=$isTimelineLoading, timelineEvents.size=${timelineEvents.size}, currentRoomId=$currentRoomId")
                }
                
                if (pendingReady && syncReady && initReady && timelineReady) {
                    val elapsed = System.currentTimeMillis() - startTime
                    android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: READY - roomId=$roomId, elapsed=${elapsed}ms, pollCount=$pollCount")
                    break
                }
                delay(pollDelayMs)
            }
            true
        } ?: run {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.w("Andromuks", "🟣 awaitRoomDataReadiness: TIMEOUT - roomId=$roomId, elapsed=${elapsed}ms, timeoutMs=$timeoutMs, isProcessingPendingItems=$isProcessingPendingItems, initialSyncComplete=$initialSyncComplete, isTimelineLoading=$isTimelineLoading, timelineEvents.size=${timelineEvents.size}, currentRoomId=$currentRoomId")
            false
        }
    }
    
    /**
     * PHASE 1.4 FIX: Register primary callbacks with WebSocketService
     * This should be called when the instance is marked as primary, before WebSocket connection
     * This ensures the service knows the AppViewModel is available even before connection is established
     */
    /**
     * STEP 1.2: Register primary callbacks with service
     * Callbacks are stored in service and don't capture AppViewModel instance
     * This allows callbacks to work even if AppViewModel is destroyed
     */
    private fun registerPrimaryCallbacks() {
        if (instanceRole != InstanceRole.PRIMARY) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping primary callback registration - instance is not PRIMARY")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: STEP 1.2 - Registering primary callbacks for $viewModelId (callbacks don't capture AppViewModel)")
        
        // STEP 1.2: Store current credentials in service for callbacks to use
        // This allows callbacks to work even if this AppViewModel is destroyed
        val currentHomeserverUrl = homeserverUrl
        val currentAuthToken = authToken
        
        // CRITICAL FIX: Capture viewModelId and this instance for use in callback
        // This ensures the callback can use this instance directly if it's primary
        val callbackViewModelId = viewModelId
        val callbackViewModelInstance = this
        
        // Register reconnection callback - this will set this instance as primary
        // STEP 1.2: Callback reads from SharedPreferences and uses registered ViewModels (doesn't capture AppViewModel)
        // Register clear cache callback for WebSocket connect/reconnect
        val clearCacheRegistered = WebSocketService.setPrimaryClearCacheCallback(viewModelId) {
            clearAllTimelineCaches()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clear cache callback registered: $clearCacheRegistered")
        
        val reconnectionRegistered = WebSocketService.setReconnectionCallback(callbackViewModelId) { reason ->
            android.util.Log.i("Andromuks", "AppViewModel: STEP 1.2 - Reconnection callback triggered (reason: $reason)")
            
            // STEP 1.2: Read credentials from SharedPreferences (not from captured AppViewModel)
            val context = callbackViewModelInstance.appContext
            if (context == null) {
                android.util.Log.e("Andromuks", "AppViewModel: STEP 1.2 - Reconnection callback: appContext is null, cannot reconnect")
                return@setReconnectionCallback
            }
            
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val storedHomeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
            val storedAuthToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
            
            if (storedHomeserverUrl.isNotEmpty() && storedAuthToken.isNotEmpty()) {
                // STEP 1.2: Find primary ViewModel from registered ViewModels (may be different instance)
                // CRITICAL FIX: Use this AppViewModel instance directly if we're primary, otherwise search
                // This handles the case where headless ViewModel callback is invoked before it's fully registered
                val isThisPrimary = WebSocketService.isPrimaryInstance(callbackViewModelId)
                
                val primaryViewModel = if (isThisPrimary && callbackViewModelInstance.appContext != null) {
                    // This is the primary instance - use it directly (handles headless ViewModel case)
                    callbackViewModelInstance
                } else {
                    // Search for primary in registered ViewModels
                    val registeredViewModels = WebSocketService.getRegisteredViewModels()
                    registeredViewModels.firstOrNull { 
                        WebSocketService.isPrimaryInstance(it.viewModelId) 
                    }
                }
                
                if (primaryViewModel != null) {
                    android.util.Log.i("Andromuks", "AppViewModel: STEP 1.2 - Reconnection callback: Found primary ViewModel (${if (isThisPrimary) "this instance" else "from registry"}), initializing WebSocket connection")
                    // Clear WebSocket first
                    WebSocketService.clearWebSocket(reason)
                    // Then trigger reconnection directly on the primary ViewModel
                    primaryViewModel.initializeWebSocketConnection(storedHomeserverUrl, storedAuthToken)
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: STEP 1.2 - Reconnection callback: No primary ViewModel found (this instance isPrimary=$isThisPrimary), will be handled by promotion in Step 2")
                    // For now, just clear WebSocket - promotion will handle reconnection in Step 2
                    WebSocketService.clearWebSocket(reason)
                }
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: STEP 1.2 - Reconnection callback: No credentials in SharedPreferences")
            }
        }
        if (!reconnectionRegistered) {
            val existingPrimary = WebSocketService.getPrimaryViewModelId()
            android.util.Log.w("Andromuks", "AppViewModel: Failed to register as primary instance for reconnection callback. Current primary: $existingPrimary, This instance: $viewModelId")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Successfully registered reconnection callback for $viewModelId")
        }
        
        // Register offline mode callback - only if reconnection callback was registered successfully
        // STEP 1.2: Callback broadcasts to all registered ViewModels (doesn't capture AppViewModel)
        if (reconnectionRegistered) {
            val offlineModeRegistered = WebSocketService.setOfflineModeCallback(viewModelId) { isOffline ->
                android.util.Log.i("Andromuks", "AppViewModel: STEP 1.2 - Offline mode callback triggered: isOffline=$isOffline")
                
                // STEP 1.2: Broadcast to all registered ViewModels (not just this instance)
                val registeredViewModels = WebSocketService.getRegisteredViewModels()
                for (viewModel in registeredViewModels) {
                    try {
                        if (isOffline) {
                            android.util.Log.w("Andromuks", "AppViewModel: STEP 1.2 - Broadcasting offline mode to ViewModel ${viewModel.viewModelId}")
                            viewModel.logActivity("Entering Offline Mode", null)
                            viewModel.setOfflineMode(true)
                        } else {
                            android.util.Log.i("Andromuks", "AppViewModel: STEP 1.2 - Broadcasting online mode to ViewModel ${viewModel.viewModelId}")
                            viewModel.logActivity("Exiting Offline Mode", null)
                            viewModel.setOfflineMode(false)
                            // Reset reconnection state on network restoration
                            WebSocketService.resetReconnectionState()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: STEP 1.2 - Error broadcasting offline mode to ViewModel ${viewModel.viewModelId}", e)
                    }
                }
            }
            if (!offlineModeRegistered) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to register offline mode callback. This should not happen if reconnection callback was registered.")
            }
            
            // Register activity log callback - only if reconnection callback was registered successfully
            // STEP 1.2: Callback broadcasts to all registered ViewModels (doesn't capture AppViewModel)
            val activityLogRegistered = WebSocketService.setActivityLogCallback(viewModelId) { event, networkType ->
                android.util.Log.d("Andromuks", "AppViewModel: STEP 1.2 - Activity log callback triggered: event=$event, networkType=$networkType")
                
                // STEP 1.2: Broadcast to all registered ViewModels (not just this instance)
                val registeredViewModels = WebSocketService.getRegisteredViewModels()
                for (viewModel in registeredViewModels) {
                    try {
                        viewModel.logActivity(event, networkType)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: STEP 1.2 - Error broadcasting activity log to ViewModel ${viewModel.viewModelId}", e)
                    }
                }
            }
            if (!activityLogRegistered) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to register activity log callback. This should not happen if reconnection callback was registered.")
            }
        }
    }
    
    fun markAsBubbleInstance() {
        instanceRole = InstanceRole.BUBBLE
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Instance role set to BUBBLE for $viewModelId")
        
        // STEP 2.1: Register this ViewModel with service (as secondary)
        WebSocketService.registerViewModel(viewModelId, isPrimary = false)
    }
    
    /**
     * STEP 2.3: Called by WebSocketService when this ViewModel is promoted to primary
     * This happens automatically when the original primary ViewModel is destroyed
     * 
     * When promoted, the ViewModel:
     * 1. Registers as primary with the service
     * 2. Registers primary callbacks with service
     * 3. Takes over WebSocket management (attaches to existing WebSocket or ensures service is running)
     */
    fun onPromotedToPrimary() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: STEP 2.3 - ViewModel $viewModelId promoted to primary")
        
        // Update instance role
        instanceRole = InstanceRole.PRIMARY
        
        // STEP 2.3: Update registration to reflect primary status
        WebSocketService.registerViewModel(viewModelId, isPrimary = true)
        
        // CRITICAL: Register to receive WebSocket messages
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // STEP 2.3: Register primary callbacks with service
        // The callbacks are already stored in service (from previous primary), but we need to
        // ensure this ViewModel can handle them. Since callbacks don't capture AppViewModel
        // (Step 1.2), they should work, but we re-register to ensure this ViewModel is set as primary
        registerPrimaryCallbacks()
        
        // STEP 2.3: Take over WebSocket management
        // 1. Ensure WebSocket service is running (if not already)
        if (!WebSocketService.isServiceRunning()) {
            android.util.Log.i("Andromuks", "AppViewModel: STEP 2.3 - WebSocket service not running, starting it")
            startWebSocketService()
        }
        
        // 2. Attach to existing WebSocket if available and not already attached
        val existingWebSocket = WebSocketService.getWebSocket()
        if (existingWebSocket != null) {
            // REFACTORING: Service owns WebSocket - just register callbacks, no local storage needed
            android.util.Log.i("Andromuks", "AppViewModel: STEP 2.3 - Attaching to existing WebSocket")
            WebSocketService.registerReceiveCallback(viewModelId, this)
        } else {
            // 3. If no WebSocket exists and we have credentials, attempt to reconnect
            // This handles the case where the primary was destroyed while disconnected
            appContext?.let { context ->
                val prefs = context.getSharedPreferences("AndromuksPrefs", android.content.Context.MODE_PRIVATE)
                val storedHomeserverUrl = prefs.getString("homeserverUrl", null)
                val storedAuthToken = prefs.getString("authToken", null)
                
                if (storedHomeserverUrl != null && storedAuthToken != null) {
                    android.util.Log.i("Andromuks", "AppViewModel: STEP 2.3 - No WebSocket found, attempting to reconnect as new primary")
                    // Use viewModelScope to ensure connection survives activity recreation
                    viewModelScope.launch {
                        initializeWebSocketConnection(storedHomeserverUrl, storedAuthToken)
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: STEP 2.3 - No WebSocket and no credentials found - cannot reconnect")
                }
            }
        }
        
        android.util.Log.i("Andromuks", "AppViewModel: STEP 2.3 - ViewModel $viewModelId successfully promoted to primary, callbacks registered, and WebSocket management taken over")
    }
    
    /**
     * Check if this AppViewModel instance is the primary instance
     * Only the primary instance should create new WebSocket connections
     */
    fun isPrimaryInstance(): Boolean {
        return instanceRole == InstanceRole.PRIMARY
    }
    
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set
    var realMatrixHomeserverUrl by mutableStateOf("")
    var wellKnownElementCallBaseUrl by mutableStateOf("")
        private set
    private var appContext: Context? = null
    
    // Timeline cache for instant room opening (now singleton)
    // No need to instantiate - using object RoomTimelineCache

    // Auth/client state
    var currentUserId by mutableStateOf("")
        private set
    var deviceId by mutableStateOf("")
    private var callActiveInternal by mutableStateOf(false)
    private var callReadyForPipInternal by mutableStateOf(false)
        private set
    var imageAuthToken by mutableStateOf("")
        private set
    var currentUserProfile by mutableStateOf<UserProfile?>(null)
        private set
    
    // State to track if pending items are being processed (prevents showing stale data in RoomListScreen)
    var isProcessingPendingItems by mutableStateOf(false)
        private set

    private var activeNotificationActionCount = 0
    var notificationActionInProgress by mutableStateOf(false)
        private set
    
    // Settings
    var enableCompression by mutableStateOf(true)
        private set
    var enterKeySendsMessage by mutableStateOf(true) // true = Enter sends, Shift+Enter newline; false = Enter newline, Shift+Enter sends
        private set
    var loadThumbnailsIfAvailable by mutableStateOf(true)
        private set
    var renderThumbnailsAlways by mutableStateOf(true)
        private set
    var elementCallBaseUrl by mutableStateOf("")
        private set

    var pendingShare by mutableStateOf<PendingSharePayload?>(null)
        private set
    var pendingShareNavigationRequested by mutableStateOf(false)
        private set
    var pendingShareUpdateCounter by mutableStateOf(0)
        private set
    private var pendingShareTargetRoomId: String? = null

    fun setPendingShare(
        items: List<SharedMediaItem>,
        text: String?,
        autoSelectRoomId: String? = null
    ) {
        if (items.isEmpty() && text.isNullOrBlank()) {
            android.util.Log.w("Andromuks", "AppViewModel: Ignoring pending share with no content")
            return
        }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Pending share set with ${items.size} items, hasText=${!text.isNullOrBlank()}, autoSelectRoom=$autoSelectRoomId"
        )
        pendingShare = PendingSharePayload(items, text)
        pendingShareTargetRoomId = null
        pendingShareNavigationRequested = autoSelectRoomId == null
        pendingShareUpdateCounter++
        if (!autoSelectRoomId.isNullOrBlank()) {
            pendingShareTargetRoomId = autoSelectRoomId
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Pending share auto-selected room: $autoSelectRoomId"
            )
        }
    }

    fun reportPersonShortcutUsed(userId: String) {
        if (userId.isBlank()) return
        personsApi?.reportShortcutUsed(userId)
    }

    fun clearPendingShare() {
        pendingShare = null
        pendingShareTargetRoomId = null
        pendingShareNavigationRequested = false
        pendingShareUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared pending share state")
    }

    fun markPendingShareNavigationHandled() {
        if (pendingShareNavigationRequested) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pending share navigation marked as handled")
        }
        pendingShareNavigationRequested = false
    }

    fun selectPendingShareRoom(roomId: String) {
        pendingShareTargetRoomId = roomId
        pendingShareNavigationRequested = false
        pendingShareUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pending share target room selected: $roomId")
    }

    fun consumePendingShareForRoom(roomId: String): PendingSharePayload? {
        val share = pendingShare
        return if (share != null && pendingShareTargetRoomId == roomId) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Consuming pending share for room $roomId")
            pendingShare = null
            pendingShareTargetRoomId = null
            pendingShareUpdateCounter++
            share
        } else {
            null
        }
    }


    // List of spaces, each with their rooms
    var spaceList by mutableStateOf(listOf<SpaceItem>())
        private set
    
    // All rooms (for filtering into sections)
    var allRooms by mutableStateOf(listOf<RoomItem>())
        private set
    
    // All spaces (for Spaces section)
    var allSpaces by mutableStateOf(listOf<SpaceItem>())
        private set
    
    // Track known space room IDs (top-level and nested) so we can filter them from room lists.
    private val knownSpaceIds = mutableSetOf<String>()
    
    // PERFORMANCE: Cached room sections to avoid expensive filtering on every recomposition
    private var cachedDirectChatRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    private var cachedUnreadRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    private var cachedFavouriteRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    // PERFORMANCE: Pre-computed badge counts (always computed for immediate tab bar display)
    private var cachedDirectChatsUnreadCount by mutableStateOf(0)
        private set
    private var cachedDirectChatsHasHighlights by mutableStateOf(false)
        private set
    private var cachedUnreadCount by mutableStateOf(0)
        private set
    private var cachedFavouritesUnreadCount by mutableStateOf(0)
        private set
    private var cachedFavouritesHasHighlights by mutableStateOf(false)
        private set
    
    // PERFORMANCE: Track which sections have been loaded (for lazy loading)
    private val loadedSections = mutableSetOf<RoomSectionType>()
    
    // Cache invalidation tracking - use size + content hash for reliability
    private var lastAllRoomsSize: Int = 0
    private var lastAllRoomsContentHash: String = ""
    
    
    /**
     * Invalidate room section cache when allRooms data changes
     */
    private fun invalidateRoomSectionCache() {
        lastAllRoomsSize = -1 // Force cache recalculation on next access
        lastAllRoomsContentHash = ""
    }
    
    /**
     * Returns the set of known space room IDs.
     * Uses allSpaces when available, otherwise falls back to spaceList.
     */
    private fun currentSpaceIds(): Set<String> {
        val ids = mutableSetOf<String>()
        if (allSpaces.isNotEmpty()) ids.addAll(allSpaces.map { it.id })
        else if (spaceList.isNotEmpty()) ids.addAll(spaceList.map { it.id })
        ids.addAll(knownSpaceIds)

        return ids
    }
    
    /**
     * Registers newly discovered space IDs (top-level or nested) for filtering.
     */
    fun registerSpaceIds(spaceIds: Collection<String>) {
        if (spaceIds.isEmpty()) return
        val added = knownSpaceIds.addAll(spaceIds)
        if (added) {
            invalidateRoomSectionCache()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registered ${spaceIds.size} space IDs (total=${knownSpaceIds.size})")
        }
    }
    
    /**
     * Filters out rooms that correspond to spaces.
     * Home/unread/direct sections should never show space shells.
     */
    private fun filterOutSpaces(rooms: List<RoomItem>, spaceIds: Set<String> = currentSpaceIds()): List<RoomItem> {
        if (spaceIds.isEmpty()) return rooms
        return rooms.filter { it.id !in spaceIds }
    }
    
    // Current selected section
    var selectedSection by mutableStateOf(RoomSectionType.HOME)
        private set
    
    // Space navigation state
    var currentSpaceId by mutableStateOf<String?>(null)
        private set
    
    // Bridge navigation state
    var currentBridgeId by mutableStateOf<String?>(null)
        private set
    
    // Store space edges data for later processing
    private var storedSpaceEdges: JSONObject? = null
    
    // Room state data
    var currentRoomState by mutableStateOf<RoomState?>(null)
        private set
    
    // Typing indicators per room (roomId -> list of typing user IDs)
    private val typingUsersMap = mutableMapOf<String, List<String>>()
    var typingUsers by mutableStateOf(listOf<String>())
        private set
    
    // PERFORMANCE: Rate limiting for typing indicators to reduce WebSocket traffic
    private val lastTypingSent = mutableMapOf<String, Long>() // roomId -> timestamp
    private val TYPING_SEND_INTERVAL = 3000L // 3 seconds instead of 1 second
    
    // Message reactions: eventId -> list of reactions
    // Now using singleton MessageReactionsCache - synced for UI reactivity
    private var _messageReactions by mutableStateOf(mapOf<String, List<MessageReaction>>())
    var messageReactions: Map<String, List<MessageReaction>>
        get() = MessageReactionsCache.getAllReactions().also { 
            // Sync state with cache for UI reactivity
            if (_messageReactions != it) {
                _messageReactions = it
            }
        }
        private set(value) {
            MessageReactionsCache.setAll(value)
            _messageReactions = value
        }
    
    // Track processed reaction events to prevent duplicate processing
    private val processedReactions = mutableSetOf<String>()
    
    // Track pending message sends for send button animation
    var pendingSendCount by mutableStateOf(0)
        private set
    
    // Track uploads in progress per room (roomId -> count)
    private val uploadInProgressCount = mutableStateMapOf<String, Int>()
    // Track upload types per room (roomId -> set of upload types: "image", "video", "audio", "file")
    private val uploadTypesPerRoom = mutableStateMapOf<String, MutableSet<String>>()
    
    /**
     * Check if there are uploads in progress for a room
     */
    fun hasUploadInProgress(roomId: String): Boolean {
        return uploadInProgressCount[roomId] ?: 0 > 0
    }
    
    /**
     * Get the primary upload type for a room (for status message)
     * Returns the first type found, or "media" as fallback
     */
    fun getUploadType(roomId: String): String {
        val types = uploadTypesPerRoom[roomId] ?: return "media"
        return when {
            types.contains("video") -> "video"
            types.contains("image") -> "image"
            types.contains("audio") -> "audio"
            types.contains("file") -> "file"
            else -> "media"
        }
    }
    
    /**
     * Start tracking an upload for a room
     * @param roomId The room ID
     * @param uploadType The type of upload: "image", "video", "audio", or "file"
     */
    fun beginUpload(roomId: String, uploadType: String = "image") {
        val current = uploadInProgressCount[roomId] ?: 0
        uploadInProgressCount[roomId] = current + 1
        
        val types = uploadTypesPerRoom.getOrPut(roomId) { mutableSetOf() }
        types.add(uploadType)
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Begin upload for room $roomId (type: $uploadType, count: ${uploadInProgressCount[roomId]})")
    }
    
    /**
     * End tracking an upload for a room
     * @param roomId The room ID
     * @param uploadType The type of upload being completed
     */
    fun endUpload(roomId: String, uploadType: String = "image") {
        val current = uploadInProgressCount[roomId] ?: 0
        if (current > 0) {
            val newCount = current - 1
            if (newCount == 0) {
                uploadInProgressCount.remove(roomId)
                uploadTypesPerRoom.remove(roomId)
            } else {
                uploadInProgressCount[roomId] = newCount
                // Note: We keep all types in the set until count reaches 0
                // This ensures getUploadType() can still return the correct type
                // even if multiple uploads of different types are in progress
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: End upload for room $roomId (type: $uploadType, count: $newCount)")
        }
    }
    
    // Recent emojis for reactions (stored as list of strings for UI)
    // Now using singleton RecentEmojisCache - synced for UI reactivity
    private var _recentEmojis by mutableStateOf(listOf<String>())
    var recentEmojis: List<String>
        get() = RecentEmojisCache.getAll().also {
            // Sync state with cache for UI reactivity
            if (_recentEmojis != it) {
                _recentEmojis = it
            }
        }
        private set(value) {
            RecentEmojisCache.set(value)
            _recentEmojis = value
        }
    
    // Internal storage for emoji frequencies: list of [emoji, count] pairs
    private var recentEmojiFrequencies = mutableListOf<Pair<String, Int>>()
    // Track whether we've loaded the full recent emoji list from the server
    // This prevents sending incomplete updates that would reset the server's full list
    private var hasLoadedRecentEmojisFromServer = false
    
    // Mentions state - list of mention events with room info
    var mentionEvents by mutableStateOf<List<MentionEvent>>(emptyList())
        private set
    var isMentionsLoading by mutableStateOf(false)
        private set
    
    // Custom emoji packs from im.ponies.emote_rooms
    data class CustomEmoji(
        val name: String,
        val mxcUrl: String,
        val info: org.json.JSONObject?
    )
    
    data class Sticker(
        val name: String,
        val mxcUrl: String,
        val body: String?,
        val info: org.json.JSONObject?
    )
    
    data class EmojiPack(
        val packName: String,
        val displayName: String,
        val roomId: String,
        val emojis: List<CustomEmoji>
    )
    
    data class StickerPack(
        val packName: String,
        val displayName: String,
        val roomId: String,
        val stickers: List<Sticker>
    )
    
    // Custom emoji packs - now using singleton EmojiPacksCache
    var customEmojiPacks: List<EmojiPack>
        get() = EmojiPacksCache.getAll()
        private set(value) = EmojiPacksCache.setAll(value)
    
    // Sticker packs - now using singleton StickerPacksCache
    var stickerPacks: List<StickerPack>
        get() = StickerPacksCache.getAll()
        private set(value) = StickerPacksCache.setAll(value)
    
    // Track pending emoji pack requests: requestId -> (roomId, packName)
    private val emojiPackRequests = mutableMapOf<Int, Pair<String, String>>()
    
    // Queue for emoji pack requests that were deferred because WebSocket wasn't ready
    private val deferredEmojiPackRequests = mutableListOf<Pair<String, String>>() // (roomId, packName)
    
    // Cache for DM room IDs from m.direct account data
    private var directMessageRoomIds by mutableStateOf(setOf<String>())
        private set

    // Cache mapping of userId -> set of direct room IDs (from m.direct)
    private var directMessageUserMap: Map<String, Set<String>> = emptyMap()
        private set
    
    
    // Room state storage for future use
    private var roomStatesCache by mutableStateOf(mapOf<String, JSONArray>())
        private set
    
    /**
     * Check if a room is a direct message using m.direct account data
     * This is a secondary method to detect DMs more reliably
     */
    fun isDirectMessageFromAccountData(roomId: String): Boolean {
        return directMessageRoomIds.contains(roomId)
    }

    // Force recomposition counter - DEPRECATED: Use specific counters below instead
    var updateCounter by mutableStateOf(0)
        private set
    
    // Granular update counters to reduce unnecessary recompositions
    var roomListUpdateCounter by mutableStateOf(0)
        private set
    
    var timelineUpdateCounter by mutableStateOf(0)
        private set
    
    var reactionUpdateCounter by mutableStateOf(0)
        private set
    
    var memberUpdateCounter by mutableStateOf(0)
        private set
    
    var roomStateUpdateCounter by mutableStateOf(0)
        private set
    
    // Room summary update counter - triggers RoomListScreen to refresh message previews/senders
    var roomSummaryUpdateCounter by mutableStateOf(0)
        private set
    
    // SYNC OPTIMIZATION: Batched update mechanism
    private var pendingUIUpdates = mutableSetOf<String>() // Track which UI sections need updates
    private var batchUpdateJob: Job? = null // Job for batching UI updates
    
    // PERFORMANCE: Debounced room reordering to prevent frustrating "room jumping"
    private var lastRoomReorderTime = 0L
    private var roomReorderJob: Job? = null
    private val ROOM_REORDER_DEBOUNCE_MS = 30000L // 30 seconds debounce - reduces visual jumping
    private var forceSortNextReorder = false // Flag to force immediate sort on next reorder
    
    // SYNC OPTIMIZATION: Diff-based update tracking
    private var lastRoomStateHash: String = ""
    private var lastTimelineStateHash: String = ""
    private var lastMemberStateHash: String = ""
    
    // SYNC OPTIMIZATION: Selective update flags
    private var needsRoomListUpdate = false
    private var needsTimelineUpdate = false
    private var needsMemberUpdate = false
    private var needsReactionUpdate = false
    
    // NAVIGATION PERFORMANCE: Prefetch and caching system
    private val prefetchedRooms = mutableSetOf<String>() // Track which rooms have been prefetched
    private val navigationCache = mutableMapOf<String, RoomNavigationState>() // Cache room navigation state
    private var lastRoomListScrollPosition = 0 // Track scroll position for prefetching
    
    // Read receipts update counter - separate from main updateCounter to reduce unnecessary UI updates
    var readReceiptsUpdateCounter by mutableStateOf(0)
        private set
    
    // Timestamp update counter for dynamic time displays
    var timestampUpdateCounter by mutableStateOf(0)
        private set
    
    
    // FCM notification manager
    private var fcmNotificationManager: FCMNotificationManager? = null
    
    // Conversations API for shortcuts and enhanced notifications
    private var conversationsApi: ConversationsApi? = null
    
    // Persons API for People/Share surfaces
    private var personsApi: PersonsApi? = null
    
    // Web client push integration
    private var webClientPushIntegration: WebClientPushIntegration? = null
    
    
    // Notification action tracking
    private data class PendingNotificationAction(
        val type: String, // "send_message" or "mark_read"
        val roomId: String,
        val text: String? = null,
        val eventId: String? = null,
        val requestId: Int? = null,
        val onComplete: (() -> Unit)? = null
    )
    
    private val pendingNotificationActions = mutableListOf<PendingNotificationAction>()
    
    // FIFO buffer for notification replies - allows duplicates, processes in order
    // Messages are added by notification replies and removed when sent to WebSocket
    class PendingNotificationMessage(
        val roomId: String,
        val text: String,
        val timestamp: Long,
        val onComplete: (() -> Unit)? = null
    )
    
    // FIFO queue: oldest messages first, removed when sent to WebSocket
    private val pendingNotificationMessages = mutableListOf<PendingNotificationMessage>()
    private val pendingNotificationMessagesLock = Any() // Lock for thread safety
    
    private val notificationActionCompletionCallbacks = mutableMapOf<Int, () -> Unit>()
    private fun beginNotificationAction() {
        activeNotificationActionCount++
        if (!notificationActionInProgress) {
            notificationActionInProgress = true
        }
    }
    
    private fun endNotificationAction() {
        if (activeNotificationActionCount > 0) {
            activeNotificationActionCount--
        }
        if (activeNotificationActionCount == 0) {
            notificationActionInProgress = false
        }
    }
    

    // WebSocket pending operations for retry when connection is restored
    // PHASE 5.1: Enhanced PendingWebSocketOperation with persistence support
    private data class PendingWebSocketOperation(
        val type: String, // "sendMessage", "sendReply", "markRoomAsRead", etc.
        val data: Map<String, Any>,
        val retryCount: Int = 0,
        val messageId: String = java.util.UUID.randomUUID().toString(), // PHASE 5.1: Unique identifier
        val timestamp: Long = System.currentTimeMillis(), // PHASE 5.1: When message was queued
        val acknowledged: Boolean = false, // PHASE 5.1: Whether response was received
        val acknowledgmentTimeout: Long = System.currentTimeMillis() + 30000L // PHASE 5.1: When to consider message failed (30s default)
    ) {
        // PHASE 5.1: Helper to convert to JSON-serializable format
        fun toJsonMap(): Map<String, Any> {
            return mapOf(
                "type" to type,
                "data" to data,
                "retryCount" to retryCount,
                "messageId" to messageId,
                "timestamp" to timestamp,
                "acknowledged" to acknowledged,
                "acknowledgmentTimeout" to acknowledgmentTimeout
            )
        }
        
        companion object {
            // PHASE 5.1: Helper to create from JSON-serializable format
            @Suppress("UNCHECKED_CAST")
            fun fromJsonMap(jsonMap: Map<String, Any>): PendingWebSocketOperation? {
                return try {
                    PendingWebSocketOperation(
                        type = jsonMap["type"] as? String ?: return null,
                        data = jsonMap["data"] as? Map<String, Any> ?: return null,
                        retryCount = (jsonMap["retryCount"] as? Number)?.toInt() ?: 0,
                        messageId = jsonMap["messageId"] as? String ?: java.util.UUID.randomUUID().toString(),
                        timestamp = (jsonMap["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                        acknowledged = jsonMap["acknowledged"] as? Boolean ?: false,
                        acknowledgmentTimeout = (jsonMap["acknowledgmentTimeout"] as? Number)?.toLong() ?: System.currentTimeMillis() + 30000L
                    )
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to parse PendingWebSocketOperation from JSON", e)
                    null
                }
            }
        }
    }
    
    // NAVIGATION PERFORMANCE: Room navigation state cache
    data class RoomNavigationState(
        val roomId: String,
        val essentialDataLoaded: Boolean = false,
        val memberDataLoaded: Boolean = false,
        val timelineDataLoaded: Boolean = false,
        val lastPrefetchTime: Long = System.currentTimeMillis()
    )
    
    private val pendingWebSocketOperations = mutableListOf<PendingWebSocketOperation>()
    private val pendingOperationsLock = Any() // Lock for synchronizing access to pendingWebSocketOperations
    private val maxRetryAttempts = 3
    
    // Track last reconnection time for stabilization period
    private var lastReconnectionTime = 0L
    
    // INFINITE LOOP FIX: Track restart state to prevent rapid-fire restarts
    private var isRestarting = false
    private var lastRestartTime = 0L
    private val RESTART_COOLDOWN_MS = 5000L // 5 seconds minimum between restarts

    var spacesLoaded by mutableStateOf(false)
        private set
    
    // Track if init_complete has been received (distinguishes initialization from real-time updates)
    private var initializationComplete = false
    
    // CRITICAL FIX: Track initial sync phase and queue sync_complete messages received before init_complete
    // This ensures we process all initial room data before showing UI
    private var initialSyncPhase = false // Set to false when WebSocket connects, true when init_complete arrives
    private val initialSyncCompleteQueue = mutableListOf<JSONObject>() // Queue for sync_complete messages before init_complete
    private val initialSyncProcessingMutex = Mutex() // Use Mutex for coroutine-safe locking
    var initialSyncProcessingComplete by mutableStateOf(false) // Set to true when all initial sync_complete messages are processed
        private set
    var initialSyncComplete by mutableStateOf(false) // Public state for UI to observe
        private set
    
    // CRITICAL FIX: Serialize sync_complete processing to prevent race conditions
    // Multiple sync_complete messages can arrive rapidly, and concurrent processing can cause messages to be missed
    private val syncCompleteProcessingMutex = Mutex() // Mutex to serialize sync_complete processing after init_complete
    
    // BATTERY OPTIMIZATION: Batch sync_complete messages when app is backgrounded
    // Reduces CPU wake-ups from 480/min (8 Hz) to 6/min (every 10s) = 98.75% reduction
    private val syncBatchProcessor = SyncBatchProcessor(
        scope = viewModelScope,
        processSyncImmediately = { syncJson, requestId, runId ->
            processInitialSyncComplete(syncJson, onComplete = null)
        }
    )
    
    // Track if shortcuts have been refreshed on startup (only refresh once per app session)
    private var shortcutsRefreshedOnStartup = false
    
    // Track sync_complete progress for UI display
    var pendingSyncCompleteCount by mutableStateOf(0)
        private set
    var processedSyncCompleteCount by mutableStateOf(0)
        private set
    
    // CRITICAL FIX: Track loading of all room states (for bridge badges) after init_complete
    // This must complete before allowing other commands and before navigating to RoomListScreen
    private var allRoomStatesRequested = false
    private var allRoomStatesLoaded = false
    private val pendingRoomStateResponses = mutableSetOf<String>() // Track which rooms we're waiting for
    private var totalRoomStateRequests = 0
    private var completedRoomStateRequests = 0
    
    // CRITICAL FIX: Block sending commands to backend until init_complete arrives and all initial sync_complete messages are processed
    // This prevents get_room_state commands from being sent before rooms are populated from sync_complete
    // Only applies on initial connection (not reconnections with last_received_event)
    private var canSendCommandsToBackend = false
    private val pendingCommandsQueue = mutableListOf<Triple<String, Int, Map<String, Any>>>() // Queue for commands blocked before init_complete
    
    // Startup progress messages for loading screen (last 10 messages, newest on top)
    private val _startupProgressMessages = mutableStateListOf<String>()
    val startupProgressMessages: List<String> get() = _startupProgressMessages
    
    // Track if startup is complete (ready to show room list)
    var isStartupComplete by mutableStateOf(false)
        private set
    
    /**
     * Add a progress message to the startup loading screen
     * Messages are added to the front (newest on top), keeping only the last 10
     */
    fun addStartupProgressMessage(message: String) {
        synchronized(_startupProgressMessages) {
            _startupProgressMessages.add(0, message) // Add to front
            if (_startupProgressMessages.size > 10) {
                _startupProgressMessages.removeAt(_startupProgressMessages.size - 1) // Remove oldest
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟦 Startup Progress: $message")
    }
    
    /**
     * Clear all startup progress messages (e.g., on app restart)
     */
    fun clearStartupProgressMessages() {
        synchronized(_startupProgressMessages) {
            _startupProgressMessages.clear()
        }
    }
    
    /**
     * Check if startup is complete and room list is ready to be displayed
     * Startup is complete when:
     * 1. init_complete was received (initializationComplete = true)
     * 2. All initial sync_complete messages have been processed (initialSyncComplete = true)
     * 3. Spaces are loaded (spacesLoaded = true)
     * Note: Bridge avatars are loaded lazily in background, so we don't wait for them
     */
    fun checkStartupComplete() {
        val wasComplete = isStartupComplete
        // CRITICAL FIX: Check that ALL initial sync_complete messages are processed, not just queued
        // Also ensure profile is loaded (or at least requested) before showing room list
        // EDGE CASE: Allow zero rooms (new account) - only require rooms if we haven't received any sync_complete yet
        // If initialSyncProcessingComplete is true, we've processed all messages, so even 0 rooms is valid
        val hasRoomsOrProcessingComplete = roomMap.isNotEmpty() || initialSyncProcessingComplete
        
        val nowComplete = initializationComplete && 
                         initialSyncComplete && 
                         initialSyncProcessingComplete && // All queued messages must be processed
                         spacesLoaded &&
                         hasRoomsOrProcessingComplete && // Have rooms OR all processing is complete (handles zero rooms case)
                         (currentUserProfile != null || currentUserId.isBlank()) && // Profile loaded OR not logged in
                         allRoomStatesLoaded // CRITICAL: Wait for all room states to load (for bridge badges)
        
        // CRITICAL FIX: Always log when startup is blocked (not just on state change)
        // This helps debug intermittent race conditions
        if (BuildConfig.DEBUG) {
            if (nowComplete != wasComplete) {
                android.util.Log.d("Andromuks", "🟦 checkStartupComplete: nowComplete=$nowComplete (init=$initializationComplete, sync=$initialSyncComplete, processing=$initialSyncProcessingComplete, spaces=$spacesLoaded, rooms=${roomMap.size}, profile=${currentUserProfile != null}, allRoomStates=$allRoomStatesLoaded)")
            }
            if (!nowComplete) {
                // Log which condition is blocking startup (always log, not just on state change)
                val missing = mutableListOf<String>()
                if (!initializationComplete) missing.add("initializationComplete")
                if (!initialSyncComplete) missing.add("initialSyncComplete")
                if (!initialSyncProcessingComplete) missing.add("initialSyncProcessingComplete")
                if (!spacesLoaded) missing.add("spacesLoaded")
                if (!hasRoomsOrProcessingComplete) missing.add("rooms/processing")
                if (currentUserProfile == null && currentUserId.isNotBlank()) missing.add("profile")
                if (!allRoomStatesLoaded) missing.add("allRoomStatesLoaded")
                if (missing.isNotEmpty()) {
                    android.util.Log.d("Andromuks", "🟦 checkStartupComplete: BLOCKED - missing: ${missing.joinToString(", ")}")
                }
            }
        }
        
        if (nowComplete && !wasComplete) {
            isStartupComplete = true
            addStartupProgressMessage("Ready!")
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟦 Startup complete - Room list ready to display (all sync messages processed, profile loaded, ${roomMap.size} rooms)")
            }
        }
    }

    fun setSpaces(spaces: List<SpaceItem>, skipCounterUpdate: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setSpaces called with ${spaces.size} spaces")
        if (spaces.isNotEmpty() && !initialSyncComplete) {
            addStartupProgressMessage("Processing ${spaces.size} spaces...")
        }
        spaceList = spaces
        
        // SYNC OPTIMIZATION: Allow skipping immediate counter updates for batched updates
        if (!skipCounterUpdate) {
            roomListUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily     
        } 
    }
    
    fun updateAllSpaces(spaces: List<SpaceItem>) {
        val previousSize = allSpaces.size
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: updateAllSpaces called - setting allSpaces from $previousSize to ${spaces.size} spaces")
        if (spaces.isNotEmpty() && BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: updateAllSpaces - space names: ${spaces.map { it.name }.joinToString(", ")}")
        }
        if (previousSize > 0 && spaces.isEmpty() && BuildConfig.DEBUG) {
            android.util.Log.w("Andromuks", "AppViewModel: ⚠️ WARNING - updateAllSpaces clearing spaces from $previousSize to 0! Stack trace:")
            Thread.dumpStack()
        }
        allSpaces = spaces
        // CRITICAL: Also update singleton cache so spaces persist across ViewModel instances
        SpaceListCache.updateSpaces(spaces)
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: allSpaces set to ${spaces.size} spaces (was $previousSize), cache updated")
    }
    
    fun changeSelectedSection(section: RoomSectionType) {
        val previousSection = selectedSection
        selectedSection = section
        // Exit space/bridge when switching to a different section
        if (section != RoomSectionType.SPACES && currentSpaceId != null) {
            currentSpaceId = null
        }
        if (section != RoomSectionType.BRIDGES && currentBridgeId != null) {
            currentBridgeId = null
        }
        // Reset space navigation when switching tabs
        if (section != RoomSectionType.SPACES) {
            currentSpaceId = null
        }
        
        // PERFORMANCE: Force immediate sort when switching tabs to show correct order
        if (previousSection != section) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tab changed from $previousSection to $section - forcing immediate sort")
            forceRoomListSort()
        }
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun enterSpace(spaceId: String) {
        currentSpaceId = spaceId
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun exitSpace() {
        currentSpaceId = null
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun enterBridge(bridgeId: String) {
        currentBridgeId = bridgeId
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun exitBridge() {
        currentBridgeId = null
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun incrementUpdateCounter() {
        updateCounter++
    }
    
    fun triggerTimestampUpdate() {
        timestampUpdateCounter++
    }
    
    
    fun restartWebSocketConnection(reason: String = "Manual reconnection") {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket connection - Reason: $reason")
        logActivity("Manual Reconnection - $reason", null)
        restartWebSocket(reason)
    }
    /**
     * Performs a full refresh by resetting all state and reconnecting for a complete payload.
     * This is triggered by:
     * 1. User pull-to-refresh gesture
     * 2. Automatic stale state detection (cached data > 10 minutes old)
     * 
     * Steps:
     * 1. Drop WebSocket connection
     * 2. Clear all room data
     * 3. Reset requestIdCounter to 1
     * 4. Reset last_received_sync_id to 0
     * 5. Reconnect with run_id but WITHOUT last_received_id (full payload)
     */
    fun performFullRefresh() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Performing full refresh - resetting state")
        logActivity("Full Refresh - Resetting State", null)
        
        // 1. Drop WebSocket connection
        clearWebSocket("Full refresh")
        
        // 2. Clear all room data
        roomMap.clear()
        allRooms = emptyList()
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        allSpaces = emptyList()
        spaceList = emptyList()
        knownSpaceIds.clear()
        storedSpaceEdges = null
        spacesLoaded = false
        personsApi?.clear()
        synchronized(readReceiptsLock) {
            readReceipts.clear()
        }
        roomsWithLoadedReceipts.clear()
        roomsWithLoadedReactions.clear()
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        readReceiptsUpdateCounter++
        
        // 3. Reset requestIdCounter to 1
        requestIdCounter = 1
        
        // 4. Clear timeline caches on full refresh (all caches are stale)
        RoomTimelineCache.clearAll()
        // Processed timeline state is cleared by RoomTimelineCache.clearAll()
        lastReceivedRequestId = 0
        // CRITICAL: Also clear lastReceivedRequestId from SharedPreferences so reconnect doesn't use it
        appContext?.let { context ->
            WebSocketService.clearLastReceivedRequestId(context)
        }
        
        val preservedRunId = currentRunId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: State reset complete - run_id preserved: $preservedRunId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: FORCE REFRESH - timeline caches cleared, will reconnect with run_id only (no last_received_id)")
        
        // Clear caches and wait for fresh sync payloads from websocket.
        // All room/space data comes from sync_complete on reconnect - no DB loading needed
        // This function is called after a full refresh, which triggers a reconnect with clear_state: true
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full refresh completed - room/space data will come from sync_complete on reconnect")
        
        // 5. Trigger reconnection (will use run_id but not last_received_id since it's 0)
        onRestartWebSocket?.invoke("Full refresh")
    }
    
    
    // Get current room section based on selected tab
    /**
     * Get the count of unread rooms for Unread tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getUnreadCount(): Int {
        return cachedUnreadCount
    }
    
    /**
     * Get the count of unread rooms for Direct Chats tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getDirectChatsUnreadCount(): Int {
        return cachedDirectChatsUnreadCount
    }
    
    /**
     * Check if Direct Chats has any room with highlights
     * PERFORMANCE: Uses pre-computed cached flag for O(1) access
     */
    fun hasDirectChatsHighlights(): Boolean {
        return cachedDirectChatsHasHighlights
    }
    
    /**
     * Get the most relevant direct-message room for a given user, if any.
     * Prefers rooms listed in account data; falls back to scanning direct rooms.
     */
    fun getDirectRoomIdForUser(userId: String): String? {
        if (userId.isBlank()) return null

        val normalizedUserId = if (userId.startsWith("@")) userId else "@$userId"
        val candidateRooms = mutableListOf<RoomItem>()

        // 1. Use mapping from m.direct account data if available
        directMessageUserMap[normalizedUserId]?.forEach { roomId ->
            roomMap[roomId]?.let { candidateRooms.add(it) }
        }

        // 2. Fallback: scan known direct rooms for the user
        if (candidateRooms.isEmpty()) {
            val roomsToCheck = if (cachedDirectChatRooms.isNotEmpty()) {
                cachedDirectChatRooms
            } else {
                allRooms.filter { it.isDirectMessage }
            }

            for (room in roomsToCheck) {
                try {
                    val members = getMemberMap(room.id)
                    if (members.containsKey(normalizedUserId)) {
                        candidateRooms.add(room)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Failed to inspect members for room ${room.id} when resolving DM for $normalizedUserId",
                        e
                    )
                }
            }
        }

        if (candidateRooms.isEmpty()) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: No direct room found for user $normalizedUserId"
            )
        }

        return candidateRooms.maxByOrNull { it.sortingTimestamp ?: 0L }?.id
    }
    
    /**
     * Get all DM room IDs for a user from m.direct account data
     * Returns empty set if user is not in m.direct or has no DM rooms
     */
    fun getDirectRoomIdsForUser(userId: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        
        val normalizedUserId = if (userId.startsWith("@")) userId else "@$userId"
        return directMessageUserMap[normalizedUserId] ?: emptySet()
    }

    /**
     * Get the count of unread rooms for Favourites tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getFavouritesUnreadCount(): Int {
        return cachedFavouritesUnreadCount
    }
    
    /**
     * Check if Favourites has any room with highlights
     * PERFORMANCE: Uses pre-computed cached flag for O(1) access
     */
    fun hasFavouritesHighlights(): Boolean {
        return cachedFavouritesHasHighlights
    }
    
    
    fun getPendingInvites(): List<RoomInvite> {
        return pendingInvites.values.toList()
    }
    /**
     * Returns a read-only copy of the read receipts map.
     * 
     * This map contains read receipts organized by event ID. Each event ID maps to a list
     * of ReadReceipt objects representing users who have read the room up to that event.
     * 
     * Note: Read receipts represent "User has read the room up to event X", so when a user
     * reads a newer message, their receipt should only appear on the latest message they've read.
     * This is handled automatically by the ReceiptFunctions.processReadReceipts() function.
     * 
     * @return Map where keys are event IDs and values are lists of read receipts for that event
     */
    fun getReadReceiptsMap(): Map<String, List<ReadReceipt>> {
        return synchronized(readReceiptsLock) {
            readReceipts.mapValues { it.value.toList() }
        }
    }
    
    /**
     * Get receipt movements for animation tracking
     * @return Map of userId -> (previousEventId, currentEventId, timestamp)
     */
    fun getReceiptMovements(): Map<String, Triple<String?, String, Long>> {
        // Clean up old movements (older than 2 seconds) to prevent memory leaks
        synchronized(readReceiptsLock) {
            val currentTime = System.currentTimeMillis()
            receiptMovements.entries.removeAll { (_, movement) ->
                currentTime - movement.third > 2000
            }
            return receiptMovements.toMap()
        }
    }
    
    /**
     * Get new message animations for slide-up effect
     * @return Map of eventId -> timestamp when animation should start
     */
    /**
     * Get new message IDs for sound notification triggering.
     * PERFORMANCE: Removed animations - this now only tracks new messages for sound notifications.
     * @return map of new message event IDs (eventId -> current timestamp)
     */
    fun getNewMessageAnimations(): Map<String, Long> = newMessageAnimations.toMap()
    
    /**
     * Update cached room sections to avoid expensive filtering on every recomposition.
     * Only recalculates when allRooms actually changes.
     */
    /**
     * PERFORMANCE OPTIMIZATION: Update cached room sections and badge counts
     * Always pre-computes badge counts for immediate tab bar display, but only
     * filters room lists for sections that have been loaded (lazy loading)
     */
    private fun updateCachedRoomSections() {
        // Get rooms from spaceList if allRooms is empty (fallback for existing data)
        val roomsToUse = if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
            spaceList.firstOrNull()?.rooms ?: emptyList()
        } else {
            allRooms
        }
        val spaceIds = currentSpaceIds()
        val roomsWithoutSpaces = filterOutSpaces(roomsToUse, spaceIds)
        
        // BUG FIX: Use size + content hash for reliable cache invalidation
        // Content hash is based on room IDs and key properties to detect actual changes
        val currentSize = roomsWithoutSpaces.size
        val spaceIdsHash = if (spaceIds.isEmpty()) "none" else spaceIds.sorted().joinToString(",")
        val currentContentHash = roomsWithoutSpaces.joinToString("|") {
            // Include sortingTimestamp so cached sections reorder when last message changes
            "${it.id}:${it.isDirectMessage}:${it.isFavourite}:${it.unreadCount}:${it.highlightCount}:${it.sortingTimestamp ?: 0L}"
        } + "|spaces:$spaceIdsHash"
        
        // Check if we need to update cache
        if (currentSize == lastAllRoomsSize && currentContentHash == lastAllRoomsContentHash) {
            return // Cache is still valid
        }
        
        lastAllRoomsSize = currentSize
        lastAllRoomsContentHash = currentContentHash
        
        // PERFORMANCE: Always pre-compute badge counts (needed for tab bar badges)
        // This is fast even for large room lists (O(n) single pass)
        updateBadgeCounts(roomsWithoutSpaces)
        
        // BUG FIX: Always update cached sections when allRooms changes, not just when accessed
        // This ensures tabs show correct data even if user hasn't visited them recently
        // The filtering is fast (O(n)) and ensures UI consistency
        cachedDirectChatRooms = roomsWithoutSpaces.filter { it.isDirectMessage }
        cachedUnreadRooms = roomsWithoutSpaces.filter { 
            (it.unreadCount != null && it.unreadCount > 0) || 
            (it.highlightCount != null && it.highlightCount > 0) 
        }
        cachedFavouriteRooms = roomsWithoutSpaces.filter { it.isFavourite }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated cached sections - allRooms(raw): ${roomsToUse.size}, spaces filtered: ${roomsWithoutSpaces.size}, DMs: ${cachedDirectChatRooms.size}, Unread: ${cachedUnreadRooms.size}, Favourites: ${cachedFavouriteRooms.size}")
        
    }
    
    /**
     * PERFORMANCE: Pre-compute badge counts in a single O(n) pass
     * Always computed for immediate tab bar badge display
     */
    private fun updateBadgeCounts(rooms: List<RoomItem>) {
        var directChatsUnread = 0
        var directChatsHighlights = false
        var unreadCount = 0
        var favouritesUnread = 0
        var favouritesHighlights = false
        
        for (room in rooms) {
            val hasUnread = (room.unreadCount != null && room.unreadCount > 0) || 
                           (room.highlightCount != null && room.highlightCount > 0)
            val hasHighlights = room.highlightCount != null && room.highlightCount > 0
            
            // Count unread for all rooms
            if (hasUnread) {
                unreadCount++
            }
            
            // Count direct chats
            if (room.isDirectMessage) {
                if (hasUnread) {
                    directChatsUnread++
                    if (hasHighlights) {
                        directChatsHighlights = true
                    }
                }
            }
            
            // Count favourites
            if (room.isFavourite) {
                if (hasUnread) {
                    favouritesUnread++
                    if (hasHighlights) {
                        favouritesHighlights = true
                    }
                }
            }
        }
        
        // Update cached counts
        cachedDirectChatsUnreadCount = directChatsUnread
        cachedDirectChatsHasHighlights = directChatsHighlights
        cachedUnreadCount = unreadCount
        cachedFavouritesUnreadCount = favouritesUnread
        cachedFavouritesHasHighlights = favouritesHighlights
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Badge counts - DMs: $directChatsUnread, Unread: $unreadCount, Favs: $favouritesUnread")
    }

    private fun buildDirectPersonTargets(rooms: List<RoomItem>): List<PersonTarget> {
        if (currentUserId.isBlank()) {
            return emptyList()
        }

        val result = mutableMapOf<String, PersonTarget>()

        for (room in rooms) {
            if (!room.isDirectMessage) continue

            val timestamp = room.sortingTimestamp ?: 0L
            val roomDisplayName = room.name
            var foundOtherMember = false

            val memberMap = try {
                getMemberMap(room.id)
            } catch (e: Exception) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to get member map for ${room.id}", e)
                emptyMap()
            }

            for ((userId, profile) in memberMap) {
                if (userId == currentUserId) continue
                foundOtherMember = true
                val displayName = profile.displayName?.takeIf { it.isNotBlank() }
                    ?: roomDisplayName.ifBlank { userId }
                val existing = result[userId]
                if (existing == null || timestamp > existing.lastActiveTimestamp) {
                    result[userId] = PersonTarget(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = profile.avatarUrl,
                        roomId = room.id,
                        roomDisplayName = roomDisplayName,
                        lastActiveTimestamp = timestamp
                    )
                }
            }

            if (!foundOtherMember) {
                val inferredUserId = room.messageSender
                if (!inferredUserId.isNullOrBlank() && inferredUserId != currentUserId) {
                    val displayName = roomDisplayName.ifBlank { inferredUserId }
                    val existing = result[inferredUserId]
                    if (existing == null || timestamp > existing.lastActiveTimestamp) {
                        result[inferredUserId] = PersonTarget(
                            userId = inferredUserId,
                            displayName = displayName,
                            avatarUrl = null,
                            roomId = room.id,
                            roomDisplayName = roomDisplayName,
                            lastActiveTimestamp = timestamp
                        )
                    }
                }
            }
        }

        return result.values.sortedByDescending { it.lastActiveTimestamp }
    }
    
    fun getCurrentRoomSection(): RoomSection {
        // PERFORMANCE: Mark current section as loaded (enables lazy filtering)
        if (!loadedSections.contains(selectedSection)) {
            loadedSections.add(selectedSection)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Lazy loading section: $selectedSection")
            
            // If this is the first section being loaded, also mark HOME as loaded
            // This ensures badge counts are always computed (HOME doesn't need filtering)
            if (loadedSections.size == 1 && selectedSection != RoomSectionType.HOME) {
                loadedSections.add(RoomSectionType.HOME)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Auto-loading HOME section for badge counts")
            }
            
            
            // Trigger cache update to filter this section
            invalidateRoomSectionCache()
        }
        
        // BUG FIX: Always update cached room sections when getCurrentRoomSection is called
        // This ensures cache is fresh even if allRooms changed while user was on a different tab
        updateCachedRoomSections()
        
        // Get rooms from spaceList if allRooms is empty (fallback for existing data)
        val roomsToUse = if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
            spaceList.firstOrNull()?.rooms ?: emptyList()
        } else {
            allRooms
        }
        val roomsWithoutSpaces = filterOutSpaces(roomsToUse)
        
        return when (selectedSection) {
            RoomSectionType.HOME -> RoomSection(
                type = RoomSectionType.HOME,
                rooms = roomsWithoutSpaces
            )
            RoomSectionType.SPACES -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SPACES section - currentSpaceId = $currentSpaceId, allSpaces.size = ${allSpaces.size}")
                if (currentSpaceId != null) {
                    // Show rooms within the selected space
                    val selectedSpace = allSpaces.find { it.id == currentSpaceId }
                    val spaceRooms = selectedSpace?.rooms ?: emptyList()
                    // Enrich space rooms with latest list summaries by replacing with roomMap/allRooms entries when available.
                    val enrichedSpaceRooms = spaceRooms.mapNotNull { room ->
                        roomMap[room.id] ?: allRooms.firstOrNull { it.id == room.id } ?: room
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Selected space = $selectedSpace, rooms.size = ${spaceRooms.size}, enriched.size=${enrichedSpaceRooms.size}")
                    RoomSection(
                        type = RoomSectionType.SPACES,
                        rooms = enrichedSpaceRooms,
                        spaces = emptyList()
                    )
                } else {
                    // Show list of spaces
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Showing space list with ${allSpaces.size} spaces")
                    RoomSection(
                        type = RoomSectionType.SPACES,
                        rooms = emptyList(),
                        spaces = allSpaces
                    )
                }
            }
            RoomSectionType.DIRECT_CHATS -> {
                // PERFORMANCE: Use cached direct chat rooms instead of filtering every time
                val unreadDmCount = cachedDirectChatRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.DIRECT_CHATS,
                    rooms = cachedDirectChatRooms,
                    unreadCount = unreadDmCount
                )
            }
            RoomSectionType.UNREAD -> {
                // PERFORMANCE: Use cached unread rooms instead of filtering every time
                RoomSection(
                    type = RoomSectionType.UNREAD,
                    rooms = cachedUnreadRooms,
                    unreadCount = cachedUnreadRooms.size
                )
            }
            RoomSectionType.MENTIONS -> {
                // Mentions section doesn't show rooms in the list - it navigates to MentionsScreen
                RoomSection(RoomSectionType.MENTIONS, rooms = emptyList())
            }
            RoomSectionType.FAVOURITES -> {
                // PERFORMANCE: Use cached favourite rooms instead of filtering every time
                val unreadFavouriteCount = cachedFavouriteRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.FAVOURITES,
                    rooms = cachedFavouriteRooms,
                    unreadCount = unreadFavouriteCount
                )
            }
            RoomSectionType.BRIDGES -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: BRIDGES section - currentBridgeId = $currentBridgeId")
                
                // Group rooms by bridgeProtocolAvatarUrl to create pseudo-spaces
                val bridgedRooms = roomsWithoutSpaces.filter { it.bridgeProtocolAvatarUrl != null }
                val bridgeGroups = bridgedRooms.groupBy { it.bridgeProtocolAvatarUrl!! }
                
                // Create pseudo-spaces (SpaceItem) for each bridge
                // Group by bridgeProtocolAvatarUrl - each unique avatar URL represents a different bridge protocol
                val bridgeSpaces = bridgeGroups.map { (bridgeAvatarUrl, rooms) ->
                    // Derive bridge name from the first room's bridge info if available
                    // Since we're grouping by avatar URL, all rooms in this group share the same bridge protocol
                    val bridgeName = rooms.firstOrNull()?.let { room ->
                        // Try to get bridge display name from SharedPreferences cache first (fastest)
                        val context = appContext
                        if (context != null) {
                            val cachedDisplayName = net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeDisplayName(context, room.id)
                            if (cachedDisplayName != null) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: BRIDGES - Found cached display name for room ${room.id}: $cachedDisplayName")
                                return@let cachedDisplayName
                            }
                        }
                        
                        // Fallback: try to get from room state cache
                        getBridgeDisplayNameFromRoomState(room.id) ?: "Bridge"
                    } ?: "Bridge"
                    
                    if (BuildConfig.DEBUG && bridgeName == "Bridge") {
                        android.util.Log.d("Andromuks", "AppViewModel: BRIDGES - Using fallback name 'Bridge' for bridge with avatar $bridgeAvatarUrl (room: ${rooms.firstOrNull()?.id})")
                    }
                    
                    SpaceItem(
                        id = bridgeAvatarUrl, // Use avatar URL as unique identifier
                        name = bridgeName,
                        avatarUrl = bridgeAvatarUrl,
                        rooms = rooms
                    )
                }
                
                if (currentBridgeId != null) {
                    // Show rooms within the selected bridge
                    val selectedBridge = bridgeSpaces.find { it.id == currentBridgeId }
                    val bridgeRooms = selectedBridge?.rooms ?: emptyList()
                    // Enrich bridge rooms with latest list summaries
                    val enrichedBridgeRooms = bridgeRooms.mapNotNull { room ->
                        roomMap[room.id] ?: allRooms.firstOrNull { it.id == room.id } ?: room
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Selected bridge = $selectedBridge, rooms.size = ${bridgeRooms.size}, enriched.size=${enrichedBridgeRooms.size}")
                    RoomSection(
                        type = RoomSectionType.BRIDGES,
                        rooms = enrichedBridgeRooms,
                        spaces = emptyList()
                    )
                } else {
                    // Show list of bridges
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Showing bridge list with ${bridgeSpaces.size} bridges")
                    RoomSection(
                        type = RoomSectionType.BRIDGES,
                        rooms = emptyList(),
                        spaces = bridgeSpaces
                    )
                }
            }
        }
    }

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }
    
    /**
     * Updates the set of low priority room IDs in SharedPreferences.
     * This is used by FCMService to filter out notifications for low priority rooms.
     */
    // BATTERY OPTIMIZATION: Cache last low priority rooms hash to avoid unnecessary SharedPreferences writes
    private var lastLowPriorityRoomsHash: String? = null
    
    /**
     * Update low priority rooms set for notification filtering.
     * BATTERY OPTIMIZATION: Only writes to SharedPreferences when the set actually changes.
     * This avoids expensive SharedPreferences writes on every sync when low priority status hasn't changed.
     */
    private fun updateLowPriorityRooms(rooms: List<RoomItem>) {
        val lowPriorityRoomIds = rooms.filter { it.isLowPriority }.map { it.id }.toSet()
        
        // BATTERY OPTIMIZATION: Only update SharedPreferences if low priority rooms actually changed
        // Generate hash of room IDs to detect changes
        val newHash = lowPriorityRoomIds.sorted().joinToString(",")
        if (newHash == lastLowPriorityRoomsHash) {
            // No change - skip expensive SharedPreferences write
            return
        }
        
        lastLowPriorityRoomsHash = newHash
        
        appContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putStringSet("low_priority_rooms", lowPriorityRoomIds)
                .apply()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated low priority rooms set: ${lowPriorityRoomIds.size} rooms (changed)")
        }
    }

    fun updateHomeserverUrl(url: String) {
        homeserverUrl = url
    }
    
    fun updateAuthToken(token: String) {
        authToken = token
    }
    
    /**
     * Initializes FCM and related notification components.
     * 
     * This function delegates to FCMNotificationManager.initializeComponents() to set up
     * all FCM-related functionality including push notifications, conversation shortcuts,
     * and web client push integration.
     * 
     * @param context Application context
     * @param homeserverUrl The Gomuks backend URL (optional, can be empty at initialization)
     * @param authToken Authentication token for the backend (optional, can be empty at initialization)
     */
    fun initializeFCM(context: Context, homeserverUrl: String = "", authToken: String = "", skipCacheClear: Boolean = false) {
        // STEP 2.1: Register this ViewModel with service (as secondary by default)
        // Will be updated to primary if markAsPrimaryInstance() is called later
        if (!WebSocketService.isViewModelRegistered(viewModelId)) {
            WebSocketService.registerViewModel(viewModelId, isPrimary = false)
        }
        appContext = context
        // Provide application context to RoomTimelineCache for debug-only diagnostics/toasts
        RoomTimelineCache.setAppContext(context)
        
        // Clear current room ID on app startup - ensures notifications aren't suppressed after crash/restart
        // The room ID will be set again when user actually opens a room
        // OPTIMIZATION: Skip cache clearing when opening from notification to preserve preemptive pagination cache
        if (!skipCacheClear) {
            clearCurrentRoomId()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared current room ID on app startup")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping cache clear on app startup (opening from notification)")
        }
        
        // PHASE 5.1: Load pending WebSocket operations from storage
        loadPendingOperationsFromStorage()
        
        val components = FCMNotificationManager.initializeComponents(
            context = context,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            realMatrixHomeserverUrl = realMatrixHomeserverUrl
        )
        fcmNotificationManager = components.fcmNotificationManager
        conversationsApi = components.conversationsApi
        personsApi = components.personsApi
        webClientPushIntegration = components.webClientPushIntegration
        
        // Network monitoring will be started when WebSocket service starts
        
        // PHASE 5.2: Start periodic acknowledgment timeout check
        startAcknowledgmentTimeoutCheck()
        
        // PHASE 5.4: Start periodic cleanup of acknowledged messages
        startAcknowledgedMessagesCleanup()
    }
    
    // PHASE 5.2: Periodic acknowledgment timeout check job
    private var acknowledgmentTimeoutJob: Job? = null
    
    // PHASE 5.4: Periodic cleanup job for acknowledged messages
    private var acknowledgedMessagesCleanupJob: Job? = null
    
    /**
     * PHASE 5.2: Start periodic check for unacknowledged messages
     * Checks every 10 seconds for messages that have exceeded their acknowledgment timeout
     */
    private fun startAcknowledgmentTimeoutCheck() {
        acknowledgmentTimeoutJob?.cancel()
        acknowledgmentTimeoutJob = viewModelScope.launch {
            while (isActive) {
                delay(10000L) // Check every 10 seconds
                checkAcknowledgmentTimeouts()
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Started acknowledgment timeout check job")
    }
    
    /**
     * PHASE 5.2: Check for unacknowledged messages that have exceeded their timeout
     * Retries messages if retryCount < maxRetryAttempts, otherwise marks as failed
     * QUEUE FLUSHING FIX: Respects stabilization period after reconnection to prevent triple-sending
     */
    private fun checkAcknowledgmentTimeouts() {
        val currentTime = System.currentTimeMillis()
        val operationsToRetry = mutableListOf<PendingWebSocketOperation>()
        val operationsToRemove = mutableListOf<PendingWebSocketOperation>()
        // Take a snapshot to avoid ConcurrentModificationException while iterating
        val operationsSnapshot = synchronized(pendingOperationsLock) { pendingWebSocketOperations.toList() }
        
        // QUEUE FLUSHING FIX: Don't retry immediately after reconnection - give backend time to stabilize
        // Wait at least 5 seconds after reconnection before retrying
        val stabilizationPeriodMs = 5000L // 5 seconds
        val timeSinceReconnection = if (lastReconnectionTime > 0) currentTime - lastReconnectionTime else Long.MAX_VALUE
        val isInStabilizationPeriod = timeSinceReconnection < stabilizationPeriodMs
        
        operationsSnapshot.forEach { operation ->
            if (!operation.acknowledged && currentTime >= operation.acknowledgmentTimeout) {
                // QUEUE FLUSHING FIX: If we're in stabilization period, extend timeout instead of retrying
                if (isInStabilizationPeriod && operation.retryCount == 0) {
                    // First retry after reconnection - extend timeout to give backend more time
                    val newTimeout = currentTime + 10000L // 10 more seconds
                    android.util.Log.i("Andromuks", "AppViewModel: QUEUE FLUSHING - Extending timeout for ${operation.type} (stabilization period, ${timeSinceReconnection}ms since reconnection)")
                    val updatedOperation = operation.copy(acknowledgmentTimeout = newTimeout)
                    operationsToRemove.add(operation)
                    operationsToRetry.add(updatedOperation)
                } else if (operation.retryCount < maxRetryAttempts) {
                    // Retry the message
                    val newRetryCount = operation.retryCount + 1
                    val newTimeout = currentTime + 30000L // 30 seconds for next attempt
                    android.util.Log.w("Andromuks", "AppViewModel: Message acknowledgment timeout - retrying (attempt $newRetryCount/${maxRetryAttempts}): ${operation.type}, messageId: ${operation.messageId}")
                    
                    operationsToRetry.add(operation.copy(
                        retryCount = newRetryCount,
                        acknowledgmentTimeout = newTimeout
                    ))
                    operationsToRemove.add(operation)
                } else {
                    // Max retries exceeded - mark as failed and remove
                    android.util.Log.e("Andromuks", "AppViewModel: Message failed after ${maxRetryAttempts} retries: ${operation.type}, messageId: ${operation.messageId}")
                    logActivity("Message Failed - ${operation.type} (${maxRetryAttempts} retries)", null)
                    operationsToRemove.add(operation)
                }
            }
        }
        
        // Remove failed/retried operations (guarded)
        if (operationsToRemove.isNotEmpty()) {
            synchronized(pendingOperationsLock) {
                operationsToRemove.forEach { pendingWebSocketOperations.remove(it) }
            }
        }
        
        // Add retried operations back with updated retry count (addPendingOperation already syncs)
        // Skip command_* operations here because sendWebSocketCommand() will re-track them with the new request_id.
        // CRITICAL FIX: Save to storage for retries (need persistence)
        operationsToRetry
            .filterNot { it.type.startsWith("command_") }
            .forEach { addPendingOperation(it, saveToStorage = true) }
        
        // Retry the operations
        if (operationsToRetry.isNotEmpty()) {
            android.util.Log.i("Andromuks", "AppViewModel: Retrying ${operationsToRetry.size} timed-out messages")
            operationsToRetry.forEach { operation ->
                when {
                    operation.type == "sendMessage" -> {
                        val roomId = operation.data["roomId"] as? String
                        val text = operation.data["text"] as? String
                        if (roomId != null && text != null) {
                            sendMessageInternal(roomId, text)
                        }
                    }
                    operation.type == "sendReply" -> {
                        // Note: sendReply requires originalEvent which is complex to restore
                        // For now, we'll skip retrying sendReply operations
                        android.util.Log.w("Andromuks", "AppViewModel: Skipping retry of sendReply (originalEvent not stored)")
                    }
                    operation.type == "markRoomAsRead" -> {
                        val roomId = operation.data["roomId"] as? String
                        val eventId = operation.data["eventId"] as? String
                        if (roomId != null && eventId != null) {
                            markRoomAsReadInternal(roomId, eventId)
                        }
                    }
                    operation.type.startsWith("command_") -> {
                        // PHASE 5.2: Retry generic commands
                        val command = operation.type.removePrefix("command_")
                        val requestId = operation.data["requestId"] as? Int
                        @Suppress("UNCHECKED_CAST")
                        val data = operation.data["data"] as? Map<String, Any>
                        if (requestId != null && data != null) {
                            // SAFETY GUARD: prevent duplicate mark_read storms when acknowledgments never arrive
                            if (command == "mark_read") {
                                val roomId = data["room_id"] as? String
                                val eventId = data["event_id"] as? String
                                if (roomId != null && eventId != null) {
                                    val lastSent = lastMarkReadSent[roomId]
                                    if (lastSent == eventId) {
                                        android.util.Log.w(
                                            "Andromuks",
                                            "AppViewModel: Skipping retry for duplicate mark_read for room $roomId event $eventId"
                                        )
                                        return@forEach
                                    }
                                    lastMarkReadSent[roomId] = eventId
                                }
                            }
                            // Generate new request_id for retry (can't reuse old one)
                            val newRequestId = requestIdCounter++
                            android.util.Log.w("Andromuks", "AppViewModel: Retrying command '$command' with new request_id: $newRequestId (was: $requestId)")
                            sendWebSocketCommand(command, newRequestId, data)
                        }
                    }
                    else -> {
                        android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
                    }
                }
            }
        }
    }
    
    /**
     * PHASE 5.4: Start periodic cleanup of acknowledged messages
     * Removes acknowledged messages older than 1 hour every 5 minutes
     */
    private fun startAcknowledgedMessagesCleanup() {
        acknowledgedMessagesCleanupJob?.cancel()
        acknowledgedMessagesCleanupJob = viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                cleanupAcknowledgedMessages()
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Started acknowledged messages cleanup job")
    }
    
    /**
     * PHASE 5.4: Cleanup acknowledged messages older than 1 hour
     * Prevents queue from growing indefinitely with old acknowledged messages
     */
    private fun cleanupAcknowledgedMessages() {
        val currentTime = System.currentTimeMillis()
        val oneHourAgo = currentTime - (60 * 60 * 1000L) // 1 hour
        
        val operationsToRemove = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.filter { 
            it.acknowledged && it.timestamp < oneHourAgo
            }
        }
        
        if (operationsToRemove.isNotEmpty()) {
            synchronized(pendingOperationsLock) {
            operationsToRemove.forEach { pendingWebSocketOperations.remove(it) }
            }
            savePendingOperationsToStorage()
            android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.4 - Cleaned up ${operationsToRemove.size} acknowledged messages older than 1 hour")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.4 - No acknowledged messages to clean up")
        }
    }
    
    
    /**
     * Registers FCM notifications with the Gomuks backend.
     * 
     * This function delegates to FCMNotificationManager.registerNotifications() to initiate
     * the FCM token registration process. When the token is ready, it triggers the
     * WebSocket-based registration with the Gomuks backend.
     */
    fun registerFCMNotifications() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: registerFCMNotifications called")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: fcmNotificationManager=${fcmNotificationManager != null}, homeserverUrl=$homeserverUrl, authToken=${authToken.take(20)}..., currentUserId=$currentUserId")
        
        fcmNotificationManager?.let { manager ->
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Calling FCMNotificationManager.registerNotifications")
            FCMNotificationManager.registerNotifications(
                fcmNotificationManager = manager,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                currentUserId = currentUserId,
                onTokenReady = {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: FCM token ready callback triggered, registering with Gomuks Backend")
                    registerFCMWithGomuksBackend()
                }
            )
        } ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: fcmNotificationManager is null, cannot register FCM notifications")
        }
    }
    
    /**
     * Get FCM token for Gomuks Backend registration
     */
    fun getFCMTokenForGomuksBackend(): String? {
        return fcmNotificationManager?.getTokenForGomuksBackend()
    }
    
    /**
     * Check if push registration should be performed (time-based)
     */
    fun shouldRegisterPush(): Boolean {
        val result = webClientPushIntegration?.shouldRegisterPush() ?: false
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() called, result=$result")
        return result
    }
    
    /**
     * Mark push registration as completed
     */
    fun markPushRegistrationCompleted() {
        webClientPushIntegration?.markPushRegistrationCompleted()
    }
    
    /**
     * Get device ID for push registration (backend's device_id, for reference only)
     */
    fun getDeviceID(): String? {
        return webClientPushIntegration?.getDeviceID()
    }
    
    /**
     * Get local device ID used for FCM registration.
     * CRITICAL: This is the unique per-device identifier that prevents one device from overwriting another's FCM registration.
     * 
     * @return A unique device identifier that persists across app restarts
     */
    fun getLocalDeviceID(): String? {
        return webClientPushIntegration?.getLocalDeviceID()
    }
    
    /**
     * Register FCM token with Gomuks Backend via WebSocket
     * 
     * DEBOUNCE FIX: Prevents multiple registrations within a short time window (5 seconds)
     * This fixes the issue where registrations were triggered from multiple places:
     * - setWebSocket() 
     * - onInitComplete()
     * - FCM token ready callback
     */
    fun registerFCMWithGomuksBackend() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: registerFCMWithGomuksBackend called")
        
        // DEBOUNCE: Check if we registered recently (within debounce window)
        val now = System.currentTimeMillis()
        val timeSinceLastRegistration = now - lastFCMRegistrationTime
        if (timeSinceLastRegistration < FCM_REGISTRATION_DEBOUNCE_MS) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping FCM registration - only ${timeSinceLastRegistration}ms since last registration (debounce: ${FCM_REGISTRATION_DEBOUNCE_MS}ms)")
            return
        }
        
        // Check if registration is needed (time-based check)
        val shouldRegister = shouldRegisterPush()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() returned $shouldRegister")
        
        // Force registration if we have a new FCM token but haven't registered via WebSocket yet
        val hasRegisteredViaWebSocket = appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .getBoolean("fcm_registered_via_websocket", false)
        } ?: false
        val forceRegistration = !hasRegisteredViaWebSocket
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: hasRegisteredViaWebSocket=$hasRegisteredViaWebSocket, forceRegistration=$forceRegistration")
        
        // Register FCM on WebSocket connection to ensure backend has current token
        // Note: Only called once per connection lifecycle (from onInitComplete) due to debounce
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registering FCM with Gomuks Backend")
        
        val token = getFCMTokenForGomuksBackend()
        // CRITICAL FIX: Use local device ID instead of backend's device_id
        // This ensures each Android device has a unique identifier and prevents one device from overwriting another's FCM registration
        val deviceId = webClientPushIntegration?.getLocalDeviceID()
        val encryptionKey = webClientPushIntegration?.getPushEncryptionKey()
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: registerFCMWithGomuksBackend - token=${token?.take(20)}..., deviceId=$deviceId, encryptionKey=${encryptionKey?.take(20)}...")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: webClientPushIntegration=${webClientPushIntegration != null}")
        
        if (token != null && deviceId != null && encryptionKey != null) {
            val registrationRequestId = requestIdCounter++
            fcmRegistrationRequests[registrationRequestId] = "fcm_registration"
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registering FCM with request_id=$registrationRequestId")
            
            // Use the correct JSON structure for Gomuks Backend
            val registrationData = mapOf(
                "type" to "fcm",
                "device_id" to deviceId,
                "data" to token,
                "encryption" to mapOf(
                    "key" to encryptionKey
                ),
                "expiration" to (System.currentTimeMillis() / 1000 + 86400) // 24 hours from now
            )
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending WebSocket command: register_push with data: $registrationData")
            sendWebSocketCommand("register_push", registrationRequestId, registrationData)
            
            // DEBOUNCE: Update last registration time immediately to prevent duplicates
            lastFCMRegistrationTime = now
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent FCM registration to Gomuks Backend with device_id=$deviceId (request_id=$registrationRequestId)")
            
            // Mark that we've attempted WebSocket registration (will be confirmed when response comes back)
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("fcm_websocket_registration_attempted", true)
                    .apply()
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Missing required data for FCM registration - token=${token != null}, deviceId=${deviceId != null}, encryptionKey=${encryptionKey != null}")
            android.util.Log.w("Andromuks", "AppViewModel: webClientPushIntegration=${webClientPushIntegration != null}")
        }
    }
    /**
     * Handle FCM registration response from Gomuks Backend
     */
    fun handleFCMRegistrationResponse(requestId: Int, data: Any) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleFCMRegistrationResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: FCM registration response data: $data")
        
        // Remove from pending requests
        fcmRegistrationRequests.remove(requestId)
        
        // Handle the response - it could be a boolean, string, or object
        when (data) {
            is Boolean -> {
                if (data) {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (boolean true)")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    // Mark that WebSocket registration was successful
                    appContext?.let { context ->
                        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket")
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (boolean false)")
                }
            }
            is String -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (string): $data")
                // Assume string response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
                // Mark that WebSocket registration was successful
                appContext?.let { context ->
                    context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("fcm_registered_via_websocket", true)
                        .apply()
                }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (string response)")
            }
            is org.json.JSONObject -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (JSON): ${data.toString()}")
                // Check if there's a success field or assume JSON response means success
                val success = data.optBoolean("success", true)
                if (success) {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (JSON)")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    // Mark that WebSocket registration was successful
                    appContext?.let { context ->
                        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (JSON response)")
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (JSON)")
                }
            }
            else -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (unknown type): $data")
                // Assume any response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
                // Mark that WebSocket registration was successful
                appContext?.let { context ->
                    context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("fcm_registered_via_websocket", true)
                        .apply()
                }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (unknown response type)")
            }
        }
    }
    
    fun updateTypingUsers(roomId: String, userIds: List<String>) {
        // Only update if room is in cache (opened or actively cached)
        if (roomId.isBlank()) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Ignoring typing update with blank roomId")
            return
        }
        
        // Check if room is in cache - only update typing info for rooms we care about
        val isRoomInCache = RoomTimelineCache.isRoomOpened(roomId) || RoomTimelineCache.isRoomActivelyCached(roomId)
        
        if (!isRoomInCache) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Ignoring typing update for room $roomId (not in cache)")
            return
        }
        
        // Store typing users per-room
        typingUsersMap[roomId] = userIds
        
        // Update the current room's typing users if this is the current room
        if (currentRoomId == roomId) {
            typingUsers = userIds
        }
        // Note: We don't clear typingUsers if this update is for a different room,
        // as the current room's typing users should remain displayed
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated typing users for room $roomId: ${userIds.size} users typing (currentRoomId=$currentRoomId)")
    }
    
    /**
     * Get typing users for a specific room
     */
    fun getTypingUsersForRoom(roomId: String): List<String> {
        return typingUsersMap[roomId] ?: emptyList()
    }
    
    private fun normalizeTimestamp(primary: Long, vararg fallbacks: Long): Long {
        if (primary > 0) return primary
        for (candidate in fallbacks) {
            if (candidate > 0) return candidate
        }
        return System.currentTimeMillis()
    }
    
    fun processReactionEvent(reactionEvent: ReactionEvent, isHistorical: Boolean = false) {
        // Create a unique key for this logical reaction (sender + emoji + target message)
        // This prevents the same logical reaction from being processed twice even if it comes
        // from both send_complete and sync_complete with different event IDs
        val reactionKey = "${reactionEvent.sender}_${reactionEvent.emoji}_${reactionEvent.relatesToEventId}"
        
        // Only apply duplicate detection to live reactions, not historical reactions
        // Historical reactions should always be processed as they may have been previously
        // processed during app startup but need to be displayed in the current room context
        if (!isHistorical && processedReactions.contains(reactionKey)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate logical reaction: $reactionKey (eventId: ${reactionEvent.eventId})")
            return
        }
        
        // Only mark live reactions as processed to prevent duplicates
        if (!isHistorical) {
            processedReactions.add(reactionKey)
        }
        
        // Clean up old processed reactions to prevent memory leaks (keep only last 100)
        // Only do cleanup for live reactions since historical reactions don't add to processedReactions
        if (!isHistorical && processedReactions.size > 100) {
            val toRemove = processedReactions.take(processedReactions.size - 100)
            processedReactions.removeAll(toRemove)
        }
        
        val previousReactions = messageReactions[reactionEvent.relatesToEventId] ?: emptyList()
        val updatedReactionsMap = net.vrkknn.andromuks.utils.processReactionEvent(reactionEvent, currentRoomId, messageReactions)
        messageReactions = updatedReactionsMap // This will update both cache and state
        val updatedReactions = updatedReactionsMap[reactionEvent.relatesToEventId] ?: emptyList()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processReactionEvent - eventId: ${reactionEvent.eventId}, logicalKey: $reactionKey, previous=${previousReactions.size}, updated=${updatedReactions.size}, reactionUpdateCounter: $reactionUpdateCounter")
        
        if (!isHistorical) {
            val previousPairs = previousReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()
            val updatedPairs = updatedReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()

            val additionOccurred = updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !previousPairs.contains(reactionEvent.emoji to reactionEvent.sender)
            val removalOccurred = previousPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender)

            // Reactions are in-memory only - no DB persistence needed
            if (BuildConfig.DEBUG && (additionOccurred || removalOccurred)) {
                android.util.Log.d("Andromuks", "AppViewModel: Reaction change processed (in-memory only): addition=$additionOccurred, removal=$removalOccurred for event ${reactionEvent.relatesToEventId}")
            }
        }

        reactionUpdateCounter++ // Trigger UI recomposition for reactions only
        updateCounter++ // Keep for backward compatibility temporarily
    }

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) {
            currentUserId = userId
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set currentUserId: $userId")
            appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("current_user_id", userId)
                ?.apply()
        }
        if (!device.isNullOrBlank()) {
            deviceId = device
            // Store device ID for FCM registration
            webClientPushIntegration?.storeDeviceId(device)
        }
        // Store the real Matrix homeserver URL for matrix: URI handling
        if (!homeserver.isNullOrBlank()) {
            realMatrixHomeserverUrl = homeserver
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set realMatrixHomeserverUrl: $homeserver")
            refreshElementCallBaseUrlFromWellKnown()
        }
        // IMPORTANT: Do NOT override gomuks backend URL with Matrix homeserver URL from client_state
        // The backend URL is set via AuthCheck from SharedPreferences (e.g., https://webmuks.aguiarvieira.pt)
        // Optionally, fetch profile for current user
        if (!currentUserId.isNullOrBlank()) {
            requestUserProfile(currentUserId)
        }
    }

    private fun refreshElementCallBaseUrlFromWellKnown() {
        val homeserver = realMatrixHomeserverUrl.trim()
        if (homeserver.isBlank()) return
        val wellKnownUrl = homeserver.trimEnd('/') + "/.well-known/matrix/client"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().build()
                val request = Request.Builder().url(wellKnownUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: .well-known fetch failed ${response.code}"
                            )
                        }
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val json = JSONObject(body)
                    val rtcFoci = json.optJSONArray("org.matrix.msc4143.rtc_foci")
                    var derivedBaseUrl: String? = null
                    if (rtcFoci != null) {
                        for (i in 0 until rtcFoci.length()) {
                            val entry = rtcFoci.optJSONObject(i) ?: continue
                            if (entry.optString("type") != "livekit") continue
                            val serviceUrl = entry.optString("livekit_service_url").trim()
                            if (serviceUrl.isBlank()) continue
                            try {
                                val uri = java.net.URI(serviceUrl)
                                val scheme = uri.scheme ?: "https"
                                val host = uri.host ?: continue
                                val port = uri.port
                                val origin = if (port == -1) {
                                    "$scheme://$host"
                                } else {
                                    "$scheme://$host:$port"
                                }
                                derivedBaseUrl = origin.trimEnd('/') + "/room"
                                break
                            } catch (_: Exception) {
                                // Ignore invalid URLs and continue scanning.
                            }
                        }
                    }
                    if (!derivedBaseUrl.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            wellKnownElementCallBaseUrl = derivedBaseUrl
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Resolved Element Call base URL from .well-known: $derivedBaseUrl"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "AppViewModel: .well-known fetch failed", e)
                }
            }
        }
    }

    fun updateImageAuthToken(token: String) {
        imageAuthToken = token
    }

    fun setCallActive(active: Boolean) {
        callActiveInternal = active
    }

    fun isCallActive(): Boolean {
        return callActiveInternal
    }

    fun setCallReadyForPip(ready: Boolean) {
        callReadyForPipInternal = ready
    }

    fun isCallReadyForPip(): Boolean {
        return callReadyForPipInternal
    }

    private var widgetToDeviceHandler: ((Any?) -> Unit)? = null

    fun setWidgetToDeviceHandler(handler: ((Any?) -> Unit)?) {
        widgetToDeviceHandler = handler
    }

    fun handleToDeviceMessage(data: Any?) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: to_device received ${data?.toString()?.take(200)}")
        }
        widgetToDeviceHandler?.invoke(normalizeToDevicePayload(data))
    }

    private fun handleSyncToDeviceEvents(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: syncJson
        // to_device can be either an array directly, or an object with "events" key
        val toDeviceValue = data.opt("to_device")
        if (toDeviceValue == null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: No to_device in sync_complete")
            }
            return
        }
        
        val events = when (toDeviceValue) {
            is JSONArray -> toDeviceValue // Direct array (gomuks format)
            is JSONObject -> toDeviceValue.optJSONArray("events") // Object with events key
            else -> null
        }
        
        if (events == null || events.length() == 0) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: to_device exists but no events (count: ${events?.length() ?: 0})")
            }
            return
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Extracting ${events.length()} to_device events from sync_complete")
        }
        try {
            val payload = JSONObject().put("events", events)
            handleToDeviceMessage(payload)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to parse to_device events from sync", e)
            }
        }
    }

    private fun normalizeToDevicePayload(data: Any?): Any? {
        return when (data) {
            is JSONObject -> {
                if (data.has("messages")) {
                    JSONObject().apply {
                        put("events", normalizeToDeviceMessages(data))
                    }
                } else if (data.has("events")) {
                    val normalizedEvents = normalizeToDeviceEvents(data.optJSONArray("events"))
                    JSONObject().apply {
                        put("events", normalizedEvents)
                    }
                } else {
                    data
                }
            }
            is JSONArray -> {
                JSONObject().apply {
                    put("events", normalizeToDeviceEvents(data))
                }
            }
            is Map<*, *> -> normalizeToDevicePayload(JSONObject(data))
            is List<*> -> normalizeToDevicePayload(JSONArray(data))
            else -> data
        }
    }

    private fun normalizeToDeviceEvents(rawEvents: JSONArray?): JSONArray {
        val normalized = JSONArray()
        if (rawEvents == null) return normalized
        for (i in 0 until rawEvents.length()) {
            val raw = rawEvents.optJSONObject(i) ?: continue
            normalized.put(normalizeToDeviceEvent(raw))
        }
        return normalized
    }

    private fun normalizeToDeviceMessages(raw: JSONObject): JSONArray {
        val normalized = JSONArray()
        val eventType = raw.optString("type")
        val sender = raw.optString("sender").takeIf { it.isNotBlank() }
        val messages = raw.optJSONObject("messages") ?: return normalized
        val userIds = messages.keys()
        while (userIds.hasNext()) {
            val userId = userIds.next()
            val devices = messages.optJSONObject(userId) ?: continue
            val deviceIds = devices.keys()
            while (deviceIds.hasNext()) {
                val deviceId = deviceIds.next()
                val content = devices.optJSONObject(deviceId) ?: continue
                val event = JSONObject()
                if (eventType.isNotBlank()) {
                    event.put("type", eventType)
                }
                if (sender != null) {
                    event.put("sender", sender)
                }
                event.put("content", content)
                event.put("to_user_id", userId)
                event.put("to_device_id", deviceId)
                normalized.put(event)
            }
        }
        return normalized
    }

    private fun normalizeToDeviceEvent(raw: JSONObject): JSONObject {
        val event = JSONObject()
        val decryptedType = raw.optString("decrypted_type").takeIf { it.isNotBlank() }
        if (decryptedType != null) {
            event.put("type", decryptedType)
        } else {
            raw.optString("type").takeIf { it.isNotBlank() }?.let { event.put("type", it) }
        }
        raw.optString("sender").takeIf { it.isNotBlank() }?.let { event.put("sender", it) }
        if (raw.has("content")) {
            event.put("content", raw.opt("content"))
        }
        if (decryptedType != null && raw.has("decrypted")) {
            event.put("content", raw.opt("decrypted"))
        }
        if (raw.has("encrypted")) {
            event.put("encrypted", raw.opt("encrypted"))
        }
        return event
    }
    
    /**
     * Populate roomMap from singleton cache when it's suspiciously small (e.g., only 1 room after opening from notification)
     * This ensures RoomListScreen has access to all rooms even when opening from notification bypassed normal initialization
     */
    fun populateRoomMapFromCache() {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache called - current roomMap size: ${roomMap.size}, cache size: ${RoomListCache.getRoomCount()}")
            
            val cachedRooms = RoomListCache.getAllRooms()
            if (cachedRooms.isNotEmpty()) {
                // Populate roomMap with rooms from singleton cache
                roomMap.putAll(cachedRooms)
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache - populated roomMap with ${cachedRooms.size} rooms from cache (new size: ${roomMap.size})")
                
                // CRITICAL: If we loaded rooms from cache, mark spaces as loaded
                // This prevents "Loading spaces..." from showing when we have rooms but spacesLoaded is false
                // The cache only contains rooms that have been processed by SpaceRoomParser, so spaces are effectively loaded
                if (!spacesLoaded && cachedRooms.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache - marking spaces as loaded since cache has ${cachedRooms.size} rooms")
                    spacesLoaded = true
                }
                
                // Update allRooms and invalidate cache
                forceRoomListSort()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: populateRoomMapFromCache - cache is empty, cannot populate roomMap")
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate roomMap from cache", e)
        }
    }
    
    /**
     * Populates allSpaces and storedSpaceEdges from singleton SpaceListCache.
     * This ensures spaces persist across ViewModel instances (e.g., when opening from notification).
     */
    fun populateSpacesFromCache() {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache called - current allSpaces size: ${allSpaces.size}, cache size: ${SpaceListCache.getSpaceCount()}")
            
            val cachedSpaces = SpaceListCache.getAllSpaces()
            if (cachedSpaces.isNotEmpty()) {
                // Populate allSpaces from singleton cache
                allSpaces = cachedSpaces
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - populated allSpaces with ${cachedSpaces.size} spaces from cache")
                
                // Also restore space_edges if available
                val cachedSpaceEdges = SpaceListCache.getSpaceEdges()
                if (cachedSpaceEdges != null) {
                    storedSpaceEdges = cachedSpaceEdges
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - restored space_edges from cache")
                }
                
                // Mark spaces as loaded since we restored them from cache
                if (!spacesLoaded) {
                    spacesLoaded = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - marking spaces as loaded")
                }
                
                roomListUpdateCounter++
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - cache is empty, spaces will be loaded from sync_complete")
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate spaces from cache", e)
        }
    }
    
    /**
     * Populate readReceipts from singleton cache
     * This ensures read receipts persist across AppViewModel instances
     */
    fun populateReadReceiptsFromCache() {
        try {
            synchronized(readReceiptsLock) {
                val cachedReceipts = ReadReceiptCache.getAllReceipts()
                if (cachedReceipts.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache called - current receipts: ${readReceipts.size} events, cache: ${cachedReceipts.size} events")
                    
                    // CRITICAL FIX: Merge cache with existing receipts instead of replacing
                    // This prevents overwriting fresh receipts from paginate/sync_complete
                    // Only add receipts from cache that don't already exist (by eventId)
                    var hasChanges = false
                    cachedReceipts.forEach { (eventId, cachedReceiptsList) ->
                        if (cachedReceiptsList.isNotEmpty()) {
                            val existingReceipts = readReceipts[eventId]
                            if (existingReceipts == null || existingReceipts.isEmpty()) {
                                // No existing receipts for this event - add from cache
                                readReceipts[eventId] = cachedReceiptsList.toMutableList()
                                hasChanges = true
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - added ${cachedReceiptsList.size} receipts for eventId=$eventId from cache")
                            } else {
                                // Receipts already exist - merge (add receipts from cache that don't exist yet)
                                // CRITICAL FIX: Create a new list instead of modifying existing one to avoid concurrent modification during composition
                                val existingUserIds = existingReceipts.map { it.userId }.toSet()
                                val newReceipts = cachedReceiptsList.filter { it.userId !in existingUserIds }
                                if (newReceipts.isNotEmpty()) {
                                    // Create a new list instead of modifying the existing one
                                    readReceipts[eventId] = (existingReceipts + newReceipts).toMutableList()
                                    hasChanges = true
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - merged ${newReceipts.size} new receipts for eventId=$eventId from cache (${readReceipts[eventId]?.size} total)")
                                }
                            }
                        }
                    }
                    
                    if (hasChanges) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - merged cache with existing receipts, total events: ${readReceipts.size}")
                        readReceiptsUpdateCounter++
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - no changes (all cache receipts already in readReceipts)")
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - cache is empty, no receipts to populate")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate readReceipts from cache", e)
        }
    }
    
    /**
     * Populate messageReactions from singleton cache
     * This ensures reactions persist across AppViewModel instances
     */
    fun populateMessageReactionsFromCache() {
        try {
            val cachedReactions = MessageReactionsCache.getAllReactions()
            if (cachedReactions.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateMessageReactionsFromCache - populated with ${cachedReactions.size} events from cache")
                messageReactions = cachedReactions
                reactionUpdateCounter++
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate messageReactions from cache", e)
        }
    }
    
    /**
     * Populate recentEmojis from singleton cache
     * This ensures recent emojis persist across AppViewModel instances
     */
    fun populateRecentEmojisFromCache() {
        try {
            val cachedEmojis = RecentEmojisCache.getAll()
            if (cachedEmojis.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRecentEmojisFromCache - populated with ${cachedEmojis.size} emojis from cache")
                recentEmojis = cachedEmojis
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate recentEmojis from cache", e)
        }
    }
    
    /**
     * Populate pendingInvites from singleton cache
     * This ensures invites persist across AppViewModel instances
     */
    fun populatePendingInvitesFromCache() {
        try {
            val cachedInvites = PendingInvitesCache.getAllInvites()
            if (cachedInvites.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populatePendingInvitesFromCache - populated with ${cachedInvites.size} invites from cache")
                // Invites are accessed via getPendingInvites() which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate pendingInvites from cache", e)
        }
    }
    
    /**
     * Initialize member cache from singleton RoomMemberCache
     * This ensures member profiles persist across AppViewModel instances
     */
    fun populateRoomMemberCacheFromCache() {
        try {
            val cachedMembers = RoomMemberCache.getAllMembers()
            if (cachedMembers.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMemberCacheFromCache - populated with ${cachedMembers.size} rooms from cache")
                // Member cache is accessed via getMemberProfile() which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate member cache from RoomMemberCache", e)
        }
    }
    
    /**
     * Populate customEmojiPacks from singleton cache
     * This ensures emoji packs persist across AppViewModel instances
     */
    fun populateEmojiPacksFromCache() {
        try {
            val cachedPacks = EmojiPacksCache.getAll()
            if (cachedPacks.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateEmojiPacksFromCache - populated with ${cachedPacks.size} packs from cache")
                // Emoji packs are accessed via customEmojiPacks which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate emoji packs from cache", e)
        }
    }
    
    /**
     * Populate stickerPacks from singleton cache
     * This ensures sticker packs persist across AppViewModel instances
     */
    fun populateStickerPacksFromCache() {
        try {
            val cachedPacks = StickerPacksCache.getAll()
            if (cachedPacks.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateStickerPacksFromCache - populated with ${cachedPacks.size} packs from cache")
                // Sticker packs are accessed via stickerPacks which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate sticker packs from cache", e)
        }
    }

    // Use a thread-safe Map to avoid ConcurrentModificationException when snapshots are taken
    private val roomMap = java.util.concurrent.ConcurrentHashMap<String, RoomItem>()
    private var syncMessageCount = 0
    
    // Track newly joined rooms (rooms that appeared in sync_complete for the first time)
    // These should be sorted to the top of the room list
    private val newlyJoinedRoomIds = mutableSetOf<String>()

    // MEMORY MANAGEMENT: Profile caches are now singletons (ProfileCache)
    // This ensures profiles are shared across all AppViewModel instances
    // No instance variables needed - all access goes through ProfileCache singleton
    
    // OPTIMIZED EDIT/REDACTION SYSTEM - O(1) lookups for all operations
    // Now using singleton MessageVersionsCache
    // These are computed properties that read from the singleton cache
    private val messageVersions: Map<String, VersionedMessage>
        get() = MessageVersionsCache.getAllVersions()
    
    private val editToOriginal: Map<String, String>
        get() = MessageVersionsCache.getAllVersions().flatMap { (originalId, versioned) ->
            versioned.versions.filter { !it.isOriginal && it.eventId != originalId }
                .map { it.eventId to originalId }
        }.toMap()
    
    private val redactionCache: Map<String, TimelineEvent>
        get() = MessageVersionsCache.getAllVersions()
            .filter { it.value.redactionEvent != null }
            .mapNotNull { (eventId, versioned) ->
                versioned.redactionEvent?.let { eventId to it }
            }.toMap()
    
    private fun clearMessageVersions() {
        MessageVersionsCache.clear()
    }
    
    private fun clearMessageVersionsForRoom(roomId: String) {
        MessageVersionsCache.clearForRoom(roomId)
    }

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        // MEMORY MANAGEMENT: Try room-specific cache first (only exists if profile differs from global)
        val flattenedProfile = ProfileCache.getFlattenedProfile(roomId, userId)
        if (flattenedProfile != null) {
            return flattenedProfile
        }
        
        // If no room-specific profile, check global cache
        val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
        if (globalProfile != null) {
            ProfileCache.updateGlobalProfileAccess(userId)
            return globalProfile
        }
        
        // Fallback to legacy cache (for compatibility during transition)
        return RoomMemberCache.getMember(roomId, userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        // OPTIMIZED: Use indexed cache for O(1) lookups instead of scanning all entries
        val memberMap = mutableMapOf<String, MemberProfile>()
        
        // Try indexed lookup first - get room-specific profiles
        val userIds = ProfileCache.getRoomUserIds(roomId)
        if (userIds != null && userIds.isNotEmpty()) {
            for (userId in userIds) {
                val profile = ProfileCache.getFlattenedProfile(roomId, userId)
                if (profile != null) {
                    memberMap[userId] = profile
                } else {
                    // Room-specific profile doesn't exist, check global cache
                    val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
                    if (globalProfile != null) {
                        ProfileCache.updateGlobalProfileAccess(userId)
                        memberMap[userId] = globalProfile
                    }
                }
            }
        } else {
            // Fallback to legacy cache if index is empty
            val legacyMap = RoomMemberCache.getRoomMembers(roomId)
            if (legacyMap.isNotEmpty()) {
                memberMap.putAll(legacyMap)
            }
        }
        
        // CRITICAL FIX: Also check global cache for users in this room's timeline events
        // This ensures profiles are found even if they're not in the room index
        // (e.g., when a user has a global profile but hasn't been indexed for this room)
        // Check both current timelineEvents (for current room) and RoomTimelineCache (for any room)
        val eventsToCheck = if (currentRoomId == roomId && timelineEvents.isNotEmpty()) {
            timelineEvents
        } else {
            // For other rooms, check RoomTimelineCache
            RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
        }
        
        if (eventsToCheck.isNotEmpty()) {
            for (event in eventsToCheck) {
                val sender = event.sender
                if (!memberMap.containsKey(sender)) {
                    // Check global cache for this user
                    val globalProfile = ProfileCache.getGlobalProfileProfile(sender)
                    if (globalProfile != null) {
                        ProfileCache.updateGlobalProfileAccess(sender)
                        memberMap[sender] = globalProfile
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added global profile fallback for $sender in room $roomId via getMemberMap()")
                    }
                }
            }
        }
        
        return memberMap
    }
    
    /**
     * Enhanced getMemberMap that includes global cache fallback for users in timeline events
     */
    fun getMemberMapWithFallback(roomId: String, timelineEvents: List<TimelineEvent>? = null): Map<String, MemberProfile> {
        val memberMap = getMemberMap(roomId).toMutableMap()
        
        // If timeline events are provided, add fallback profiles from global cache for users in events
        timelineEvents?.let { events ->
            for (event in events) {
                val sender = event.sender
                if (!memberMap.containsKey(sender)) {
                    // Check global cache and add to member map if found
                    val globalProfile = ProfileCache.getGlobalProfileProfile(sender)
                    if (globalProfile != null) {
                        ProfileCache.updateGlobalProfileAccess(sender)
                        memberMap[sender] = globalProfile
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added global profile fallback for $sender in room $roomId")
                    }
                }
            }
        }

        // Normalize display names: if empty/null, fall back to the username part without domain
        return memberMap.mapValues { (userId, profile) ->
            val fallbackName = userId.removePrefix("@").substringBefore(":")
            MemberProfile(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
                avatarUrl = profile.avatarUrl
            )
        }
    }
    /**
     * MEMORY MANAGEMENT: Helper method to store member profile in both flattened and legacy caches
     * OPTIMIZATION: Only stores room-specific profile if it differs from global profile to avoid redundancy.
     * 
     * Strategy:
     * - If no global profile exists, set it as global (no room-specific entry needed)
     * - If global profile exists and matches, don't store room-specific entry (use global)
     * - If global profile exists and differs, store room-specific entry (but don't update global)
     * 
     * Note: Global profile should ideally come from explicit profile requests, not from room member events.
     * This prevents the global from drifting and ensures room-specific entries only exist when truly different.
     */
    private fun storeMemberProfile(roomId: String, userId: String, profile: MemberProfile) {
        // Check existing global profile BEFORE updating (to compare)
        val existingGlobalProfileEntry = ProfileCache.getGlobalProfile(userId)
        val existingGlobalProfile = existingGlobalProfileEntry?.profile
        
        if (existingGlobalProfile == null) {
            // No global profile yet - set this as global, don't store room-specific
            ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
            // Remove any existing room-specific entry (cleanup)
            ProfileCache.removeFlattenedProfile(roomId, userId)
            ProfileCache.removeFromRoomIndex(roomId, userId)
        } else {
            // Global profile exists - compare
            val profilesDiffer = existingGlobalProfile.displayName != profile.displayName ||
                existingGlobalProfile.avatarUrl != profile.avatarUrl
            
            if (profilesDiffer) {
                // Profile differs from global - store room-specific entry
                ProfileCache.setFlattenedProfile(roomId, userId, profile)
                
                // OPTIMIZED: Update indexed cache for fast lookups
                ProfileCache.addToRoomIndex(roomId, userId)
                
                // DON'T update global here - it should come from explicit profile requests
                // This prevents global from drifting and ensures consistency
            } else {
                // Profile matches global - remove room-specific entry if it exists (cleanup)
                ProfileCache.removeFlattenedProfile(roomId, userId)
                // Also remove from index if present
                ProfileCache.removeFromRoomIndex(roomId, userId)
            }
        }
        
        // Also maintain legacy cache for compatibility (but this will be deprecated)
        // Update singleton RoomMemberCache
        RoomMemberCache.updateMember(roomId, userId, profile)
        
        // MEMORY MANAGEMENT: Cleanup if cache gets too large
        if (ProfileCache.getFlattenedCacheSize() > MAX_MEMBER_CACHE_SIZE) {
            performMemberCacheCleanup()
        }
    }
    
    /**
     * Updates the global profile cache explicitly (e.g., from profile requests).
     * This should be called when we receive a canonical profile, not from room member events.
     * When global profile is updated, we should re-evaluate room-specific entries.
     */
    fun updateGlobalProfile(userId: String, profile: MemberProfile) {
        val existingGlobalProfileEntry = ProfileCache.getGlobalProfile(userId)
        val existingGlobalProfile = existingGlobalProfileEntry?.profile
        
        // Update global profile
        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
        
        // If global profile changed, clean up room-specific entries that now match global
        if (existingGlobalProfile != null && 
            (existingGlobalProfile.displayName != profile.displayName ||
             existingGlobalProfile.avatarUrl != profile.avatarUrl)) {
            
            // Use ProfileCache cleanup method
            ProfileCache.cleanupMatchingRoomProfiles(userId, profile)
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated global profile for $userId and cleaned up matching room-specific entries")
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Cleanup old member cache entries to prevent memory pressure
     */
    private fun performMemberCacheCleanup() {
        // Use ProfileCache cleanup methods
        ProfileCache.cleanupGlobalProfiles(MAX_MEMBER_CACHE_SIZE)
        ProfileCache.cleanupFlattenedProfiles(MAX_MEMBER_CACHE_SIZE)
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Performed member cache cleanup - flattened: ${ProfileCache.getFlattenedCacheSize()}, global: ${ProfileCache.getGlobalCacheSize()}")
    }
    
    /**
     * Gets the complete version history for a message (O(1) lookup)
     * @param eventId Either the original event ID or an edit event ID
     * @return VersionedMessage containing all versions, or null if not found
     */
    fun getMessageVersions(eventId: String): VersionedMessage? {
        // Check if this is an edit event first
        val originalEventId = editToOriginal[eventId] ?: eventId
        return messageVersions[originalEventId]
    }
    
    
    /**
     * Gets the redaction event for a deleted message (O(1) lookup)
     * @param eventId The event ID that was redacted
     * @return The redaction event, or null if not redacted
     */
    fun getRedactionEvent(eventId: String): TimelineEvent? {
        return redactionCache[eventId]
    }
    
    /**
     * Checks if a message has been edited (O(1) lookup from memory)
     * Note: This checks the in-memory cache which is populated from RoomTimelineCache.
     */
    fun isMessageEdited(eventId: String): Boolean {
        val versioned = getMessageVersions(eventId)
        return versioned != null && versioned.versions.size > 1
    }
    
    /**
     * Data class for room-specific profile entry
     */
    data class RoomProfileEntry(
        val roomId: String?,
        val userId: String,
        val profile: MemberProfile
    )
    
    /**
     * Gets all profiles from memory cache (ProfileCache singleton).
     * For memory cache, we preserve room-specific profiles since Matrix allows different
     * display names/avatars per room.
     * @return List of RoomProfileEntry with roomId and profile info, sorted by display name
     */
    suspend fun getAllMemoryCachedProfiles(): List<RoomProfileEntry> = withContext(Dispatchers.Default) {
        val profiles = mutableListOf<RoomProfileEntry>()
        
        // Collect from flattened member cache (room-specific profiles)
        // Keys are in format "roomId:userId" where roomId starts with '!' and userId starts with '@'
        for ((key, profile) in ProfileCache.getAllFlattenedProfiles()) {
            // Find the last colon (userId starts with '@' so we need to find the separator correctly)
            val lastColonIndex = key.lastIndexOf(':')
            if (lastColonIndex > 0 && lastColonIndex < key.length - 1) {
                val roomId = key.substring(0, lastColonIndex)
                val userId = key.substring(lastColonIndex + 1)
                // Validate that userId is a valid Matrix user ID (starts with '@' and contains ':')
                // Also ensure it's not just a domain (like "aguiarvieira.pt")
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    // Additional validation: userId should have format @name:domain
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        // Validate roomId format (should start with '!' or be a valid room ID)
                        // Allow roomId to be present even if it doesn't start with '!' (for edge cases)
                        profiles.add(RoomProfileEntry(
                            roomId = roomId.takeIf { it.isNotEmpty() },
                            userId = userId,
                            profile = profile
                        ))
                    }
                }
            }
        }
        
        // Collect from global profile cache (room-agnostic profiles)
        // These are global (not room-specific), so roomId is null
        for ((userId, entry) in ProfileCache.getAllGlobalProfiles()) {
            val profile = entry.profile
                // Validate userId format: must be @name:domain
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        profiles.add(RoomProfileEntry(
                            roomId = null, // Global profile, not room-specific
                            userId = userId,
                            profile = profile
                        ))
                }
            }
        }
        
        // Sort by display name (nulls last), then by userId, then by roomId
        profiles.sortedWith(compareBy(
            { it.profile.displayName ?: "\uFFFF" }, // Put nulls at the end
            { it.userId },
            { it.roomId ?: "" }
        ))
    }
    
    /**
     * Gets all cached media from memory cache.
     * 
     * IMPORTANT: Coil's MemoryCache stores bitmaps in RAM and doesn't support enumeration.
     * This method shows media that COULD be in Coil's memory cache by checking Coil's disk cache,
     * since disk cache is what gets loaded into memory when accessed.
     * 
     * For true RAM enumeration, Coil's MemoryCache API doesn't provide this capability.
     */
    suspend fun getAllMemoryCachedMedia(context: Context): List<CachedMediaEntry> = withContext(Dispatchers.IO) {
        try {
            // IMPORTANT: Coil's MemoryCache stores bitmaps in RAM and CANNOT be enumerated.
            // The MemoryCache API doesn't provide a way to list what's cached.
            // 
            // Instead, we show Coil's DISK cache, which represents images that:
            // 1. Are stored on disk (persistent)
            // 2. Get loaded into RAM (MemoryCache) when accessed via AsyncImage
            // 
            // So "Memory" cache shows what COULD be in RAM, but we can't verify actual RAM presence.
            
            // Load URL mappings first for faster lookups
            net.vrkknn.andromuks.utils.CoilUrlMapper.loadMappings(context)
            
            val entries = mutableListOf<CachedMediaEntry>()
            
            // Show Coil's disk cache (these get loaded into RAM when accessed)
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Checking Coil disk cache at: ${coilCacheDir.absolutePath}, exists: ${coilCacheDir.exists()}")
            
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                // Process files in batches to reduce memory pressure
                val files = coilCacheDir.walkTopDown().filter { it.isFile }.toList()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found ${files.size} files in Coil disk cache")
                
                // Process in smaller batches to avoid OOM
                val batchSize = 50
                for (i in files.indices step batchSize) {
                    val batch = files.subList(i, minOf(i + batchSize, files.size))
                    
                    for (file in batch) {
                        // Try to find MXC URL via mapper
                        val mxcUrl = findMxcUrlForCoilFile(context, file)
                        
                        entries.add(CachedMediaEntry(
                            mxcUrl = mxcUrl,
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            cacheType = "memory",
                            file = file
                        ))
                    }
                    
                    // Yield to other coroutines between batches to prevent blocking
                    kotlinx.coroutines.yield()
                }
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Coil disk cache directory does not exist or is not a directory")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Returning ${entries.size} memory cached media entries")
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get memory cached media", e)
            emptyList()
        }
    }
    /**
     * Find MXC URL for a Coil cached file by checking the URL mapper.
     */
    private suspend fun findMxcUrlForCoilFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            // First, try CoilUrlMapper (fastest, most reliable)
            val mappedUrl = net.vrkknn.andromuks.utils.CoilUrlMapper.findMxcUrlForCacheFile(context, file)
            if (mappedUrl != null) {
                return@withContext mappedUrl
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error finding MXC URL for Coil file ${file.name}", e)
            null
        }
    }
    
    /**
     * Gets all cached media from disk cache (IntelligentMediaCache + Coil disk cache).
     */
    suspend fun getAllDiskCachedMedia(context: Context): List<CachedMediaEntry> = withContext(Dispatchers.IO) {
        try {
            // Load URL mappings first for faster lookups
            net.vrkknn.andromuks.utils.CoilUrlMapper.loadMappings(context)
            
            val entries = mutableListOf<CachedMediaEntry>()
            
            // 1. Get entries from IntelligentMediaCache (has MXC URLs stored)
            val cacheDir = IntelligentMediaCache.getCacheDir(context)
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val files: Array<File>? = cacheDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile) {
                            // Try to find MXC URL by matching cache key
                            val mxcUrl: String? = IntelligentMediaCache.getMxcUrlForFile(file.name)
                            if (mxcUrl != null) {
                            entries.add(CachedMediaEntry(
                                mxcUrl = mxcUrl,
                                filePath = file.absolutePath,
                                fileSize = file.length(),
                                cacheType = "disk",
                                file = file
                            ))
                            }
                        }
                    }
                }
            }
            
            // 2. Get entries from Coil's disk cache (process in batches to reduce memory pressure)
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                val files = coilCacheDir.walkTopDown().filter { it.isFile }.toList()
                
                // Process in smaller batches to avoid OOM
                val batchSize = 50
                for (i in files.indices step batchSize) {
                    val batch = files.subList(i, minOf(i + batchSize, files.size))
                    
                    for (file in batch) {
                        // Try to find MXC URL via mapper
                        val mxcUrl = findMxcUrlForCoilFile(context, file)
                        
                        entries.add(CachedMediaEntry(
                            mxcUrl = mxcUrl, // May be null if not found
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            cacheType = "disk",
                            file = file
                        ))
                    }
                    
                    // Yield to other coroutines between batches to prevent blocking
                    kotlinx.coroutines.yield()
                }
            }
            
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get disk cached media", e)
            emptyList()
        }
    }
    
    /**
     * Data class for cached media entry
     */
    data class CachedMediaEntry(
        val mxcUrl: String?,
        val filePath: String,
        val fileSize: Long,
        val cacheType: String, // "memory" or "disk"
        val file: java.io.File
    )
    
    /**
     * Gets the latest version of a message (O(1) lookup)
     */
    fun getLatestMessageVersion(eventId: String): TimelineEvent? {
        val versioned = getMessageVersions(eventId)
        return versioned?.versions?.firstOrNull()?.event
    }
    
    /**
     * Gets user profile information for a given user ID (CACHE ONLY - NON-BLOCKING)
     * 
     * This function ONLY checks caches and returns immediately without triggering network requests.
     * This prevents UI stalls during rendering.
     * 
     * PERFORMANCE: Uses O(1) global cache lookup instead of scanning all room caches.
     * 
     * To request missing profiles, use requestUserProfileAsync() from a LaunchedEffect or background coroutine.
     * 
     * @param userId The Matrix user ID to look up
     * @param roomId Optional room ID to check room-specific member cache first
     * @return MemberProfile if found in cache, null otherwise (UI should show fallback)
     */
    fun getUserProfile(userId: String, roomId: String? = null): MemberProfile? {
        // IMPORTANT: Check room-specific cache FIRST when roomId is provided
        // Users can have different names in different rooms, so room-specific profiles take precedence
        if (roomId != null) {
            val flattenedProfile = ProfileCache.getFlattenedProfile(roomId, userId)
            if (flattenedProfile != null) {
                return flattenedProfile
            }
            
            // Fallback to legacy room member cache
            val roomMember = RoomMemberCache.getMember(roomId, userId)
            if (roomMember != null) {
                return roomMember
            }
        }
        
        // OPTIMIZED: Check if it's the current user (check both currentUserProfile and global cache)
        if (userId == currentUserId) {
            if (currentUserProfile != null) {
                return MemberProfile(
                    displayName = currentUserProfile!!.displayName,
                    avatarUrl = currentUserProfile!!.avatarUrl
                )
            }
            // Also check global cache for current user (in case profile was loaded but currentUserProfile not set yet)
            val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
            if (globalProfile != null) {
                ProfileCache.updateGlobalProfileAccess(userId)
                return globalProfile
            }
        }
        
        // OPTIMIZED: Check global profile cache (fallback for when no roomId or room-specific not found)
        val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
        if (globalProfile != null) {
            ProfileCache.updateGlobalProfileAccess(userId)
            return globalProfile
        }
        
        // NOT FOUND - Return null immediately (don't block UI)
        // UI will show fallback and can request profile asynchronously
        return null
    }
    
    /**
     * Request user profile asynchronously (non-blocking)
     * Call this from LaunchedEffect or background coroutine, NOT during composition
     */
    fun requestUserProfileAsync(userId: String, roomId: String? = null) {
        // Only request if it's a valid Matrix ID and not already in cache
        if (!userId.startsWith("@") || !userId.contains(":")) {
            return
        }
        
        // Check if already in cache (quick check)
        val profile = getUserProfile(userId, roomId)
        if (profile != null) {
            return // Already cached
        }
        
        // Not in cache - request it (async)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Async requesting profile for Matrix user: $userId")
        requestUserProfile(userId, roomId)
    }
    

    
    /**
     * Helper function to check if an event is an edit (m.replace relationship)
     */
    private fun isEditEvent(event: TimelineEvent): Boolean {
        return when {
            event.type == "m.room.message" -> 
                event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> 
                event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            else -> false
        }
    }
    /**
     * Helper to merge message versions without duplicates and keep newest-first ordering.
     */
    private fun mergeVersionsDistinct(
        existing: List<MessageVersion>,
        extra: MessageVersion? = null
    ): List<MessageVersion> {
        return (if (extra != null) existing + extra else existing)
            .groupBy { it.eventId }           // de-dupe by eventId
            .map { (_, versions) -> versions.maxByOrNull { it.timestamp } ?: versions.first() } // keep newest per id
            .sortedByDescending { it.timestamp }
    }

    /**
     * OPTIMIZED: Process events to build version cache (O(n) where n = number of events)
     * This replaces the old chain-following approach with direct version storage
     */
    private fun processVersionedMessages(events: List<TimelineEvent>) {
        for (event in events) {
            when {
                // Handle redaction events - O(1) storage
                event.type == "m.room.redaction" -> {
                    val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    
                    if (redactsEventId != null) {
                        // Mark the original message as redacted
                        val versioned = messageVersions[redactsEventId]
                        if (versioned != null) {
                            MessageVersionsCache.updateVersion(redactsEventId, versioned.copy(
                                redactedBy = event.eventId,
                                redactionEvent = event
                            ))
                        } else {
                            // Redaction came before the original event - try to find original in cache
                            // This happens when pagination returns redaction before the original message
                            val originalEvent = RoomTimelineCache.getCachedEvents(event.roomId)?.find { it.eventId == redactsEventId }
                            
                            if (originalEvent != null) {
                                // Found original event in cache - create VersionedMessage with redaction
                                MessageVersionsCache.updateVersion(redactsEventId, VersionedMessage(
                                    originalEventId = redactsEventId,
                                    originalEvent = originalEvent,
                                    versions = emptyList(),
                                    redactedBy = event.eventId,
                                    redactionEvent = event
                                ))
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Redaction event ${event.eventId} received before original $redactsEventId, but found original in cache")
                            } else {
                                // Original not in cache yet - create placeholder so redaction can be found
                                // We'll use the redaction event itself as a temporary originalEvent
                                // This will be updated when the original event arrives
                                MessageVersionsCache.updateVersion(redactsEventId, VersionedMessage(
                                    originalEventId = redactsEventId,
                                    originalEvent = event,  // Temporary placeholder
                                    versions = emptyList(),
                                    redactedBy = event.eventId,
                                    redactionEvent = event
                                ))
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Redaction event ${event.eventId} received before original $redactsEventId - created placeholder")
                            }
                        }
                    }
                }
                
                // Handle edit events (m.replace) - O(1) storage
                isEditEvent(event) -> {
                    val relatesTo = when {
                        event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")
                        event.type == "m.room.encrypted" -> event.decrypted?.optJSONObject("m.relates_to")
                        else -> null
                    }
                    
                    val originalEventId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
                    
                    if (originalEventId != null) {
                        // Store reverse mapping for quick lookup (handled by MessageVersionsCache)
                        // editToOriginal is computed from messageVersions
                        
                        val versioned = messageVersions[originalEventId]
                        if (versioned != null) {
                            // Add this edit to the version list
                            val newVersion = MessageVersion(
                                eventId = event.eventId,
                                event = event,
                                timestamp = event.timestamp,
                                isOriginal = false
                            )
                            
                            // Merge and sort versions (newest first) without duplicates
                            val updatedVersions = mergeVersionsDistinct(versioned.versions, newVersion)
                            
                            // MEMORY MANAGEMENT: Limit versions per message to prevent memory leaks
                            val limitedVersions = if (updatedVersions.size > MAX_MESSAGE_VERSIONS_PER_EVENT) {
                                updatedVersions.take(MAX_MESSAGE_VERSIONS_PER_EVENT)
                            } else {
                                updatedVersions
                            }
                            
                            val updatedVersioned = versioned.copy(
                                versions = limitedVersions
                            )
                            
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added edit ${event.eventId} to original $originalEventId (total versions: ${updatedVersions.size})")
                        } else {
                            // Edit came before original - create placeholder with just the edit
                            MessageVersionsCache.updateVersion(originalEventId, VersionedMessage(
                                originalEventId = originalEventId,
                                originalEvent = event,  // Temporary, will be replaced when original arrives
                                versions = listOf(MessageVersion(
                                    eventId = event.eventId,
                                    event = event,
                                    timestamp = event.timestamp,
                                    isOriginal = false
                                ))
                            ))
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Edit ${event.eventId} received before original $originalEventId - created placeholder")
                        }
                    }
                }
                
                // Handle regular messages (original events) - O(1) storage
                event.type == "m.room.message" || event.type == "m.room.encrypted" -> {
                    val existing = messageVersions[event.eventId]
                    
                    if (existing != null) {
                        // We already have edit versions, now add/update the original
                        val originalVersion = MessageVersion(
                            eventId = event.eventId,
                            event = event,
                            timestamp = event.timestamp,
                            isOriginal = true
                        )
                        
                        // Merge with existing edits and sort (de-duped)
                        val updatedVersions = mergeVersionsDistinct(
                            existing.versions.filter { !it.isOriginal },
                            originalVersion
                        )
                        
                        MessageVersionsCache.updateVersion(event.eventId, existing.copy(
                            originalEvent = event,
                            versions = updatedVersions
                        ))
                        
                        //android.util.Log.d("Andromuks", "AppViewModel: Updated original event ${event.eventId} with ${updatedVersions.size} total versions")
                    } else {
                        // First time seeing this message - create new versioned message
                        MessageVersionsCache.updateVersion(event.eventId, VersionedMessage(
                            originalEventId = event.eventId,
                            originalEvent = event,
                            versions = listOf(MessageVersion(
                                eventId = event.eventId,
                                event = event,
                                timestamp = event.timestamp,
                                isOriginal = true
                            ))
                        ))
                    }
                }
            }
        }
    }
    
    // PERFORMANCE: Track member processing state for incremental updates
    private var memberProcessingIndex = 0
    private val lastProcessedMembers = mutableSetOf<String>() // Track which rooms had member events
    
    /**
     * PERFORMANCE OPTIMIZATION: Incremental member cache processing
     * Only processes members for rooms that changed, and only every 3rd sync message
     * This prevents 100-300ms delays on every sync for large rooms
     */
    private fun populateMemberCacheFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val roomsJson = data.optJSONObject("rooms") ?: return
        
        // BATTERY OPTIMIZATION: Only process rooms with member events
        // First pass: Quickly identify which rooms have member events (without processing them)
        val roomsWithMemberEvents = mutableListOf<Pair<String, JSONObject>>()
        
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val events = roomObj.optJSONArray("events") ?: continue
            
            // Quick check: Does this room have any member events?
            var hasMemberEvents = false
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                if (eventType == "m.room.member") {
                    hasMemberEvents = true
                    break // Found one, no need to check further
                }
            }
            
            // Only process rooms that have member events
            if (hasMemberEvents) {
                roomsWithMemberEvents.add(roomId to roomObj)
            }
        }
        
        if (roomsWithMemberEvents.isEmpty()) {
            // No rooms with member events - nothing to process
            //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: MEMBER PROCESSING - No rooms with member events in this sync")
            return
        }
        
        // Removed debug log to reduce log spam during initial sync
        
        // Track current sync's room IDs with member events
        val currentSyncRooms = mutableSetOf<String>()
        
        // Second pass: Process only rooms with member events
        for ((roomId, roomObj) in roomsWithMemberEvents) {
            val events = roomObj.optJSONArray("events") ?: continue
            // Get existing members from singleton cache or create empty map
            val existingMembers = RoomMemberCache.getRoomMembers(roomId)
            val memberMap = existingMembers.toMutableMap()
            
            // Process member events
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                
                if (eventType == "m.room.member") {
                    val userId = event.optString("state_key") ?: event.optString("sender")
                    val content = event.optJSONObject("content")
                    val membership = content?.optString("membership")
                    val unsigned = event.optJSONObject("unsigned")
                    val prevContent = unsigned?.optJSONObject("prev_content")
                    val prevMembership = prevContent?.optString("membership")
                    
                    if (userId != null) {
                        when (membership) {
                            "join" -> {
                                // Add/update only joined members to room cache for mention lists
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                val previousProfile = memberMap[userId]
                                
                                // Update singleton cache
                                RoomMemberCache.updateMember(roomId, userId, profile)
                                
                                // Check if this is actually a new join (not just a profile change)
                                val isNewJoin = previousProfile == null
                                
                                // Check if this is a profile change (join -> join with different profile data)
                                val isProfileChange = prevMembership == "join" && membership == "join" && !isNewJoin &&
                                    (previousProfile?.displayName != displayName || previousProfile?.avatarUrl != avatarUrl)
                                
                                // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                                storeMemberProfile(roomId, userId, profile)
                                
                                // COLD START FIX: Only trigger UI updates for actual real-time changes, not historical data
                                // During initial sync (before initialSyncComplete), all member events are historical and shouldn't trigger UI updates
                                // After initial sync is complete, only actual state transitions (invite→join, leave→join) should trigger updates
                                val isActualNewJoin = isNewJoin && (prevMembership == null || prevMembership == "invite" || prevMembership == "leave")
                                
                                // BATTERY OPTIMIZATION: Only trigger UI updates when foregrounded AND initial sync is complete
                                // Cache is still updated (for accuracy), but no recompositions during initial sync or when backgrounded
                                // CRITICAL: Check initialSyncComplete (not initializationComplete) because we're still processing
                                // historical data from queued sync_complete messages even after initializationComplete is set
                                if (isAppVisible && initialSyncComplete) {
                                    if (isActualNewJoin) {
                                        // New joins are critical - trigger member update immediately
                                        memberUpdateCounter++
                                    } else if (isProfileChange) {
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile change detected for $userId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                                        // Trigger UI update for profile changes
                                        memberUpdateCounter++
                                    }
                                }
                                // Removed debug log for member changes during initial sync to reduce log spam
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached joined member '$userId' in room '$roomId' -> displayName: '$displayName'")
                            }
                            "invite" -> {
                                // Store invited members in global cache only (for profile lookups) but not in room member cache
                                // This prevents them from appearing in mention lists but allows profile display if they send messages
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached invited member '$userId' profile in global cache only -> displayName: '$displayName'")
                            }
                            "leave", "ban" -> {
                                // Remove members who left or were banned from room cache only
                                memberMap.remove(userId)
                                ProfileCache.removeFlattenedProfile(roomId, userId)
                                
                                // OPTIMIZED: Remove from indexed cache
                                ProfileCache.removeFromRoomIndex(roomId, userId)
                                
                                // COLD START FIX: Only trigger UI updates for actual real-time changes, not historical data
                                // BATTERY OPTIMIZATION: Only trigger UI updates when foregrounded AND initialization is complete
                                // Cache is still updated (for accuracy), but no recompositions during initialization or when backgrounded
                                if (isAppVisible && initializationComplete) {
                                    // Trigger member update for leaves (critical change)
                                    memberUpdateCounter++
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member left/banned: $userId in room $roomId - triggering immediate UI update")
                                } else {
                                    val reason = when {
                                        !initializationComplete -> "initialization"
                                        !isAppVisible -> "app backgrounded"
                                        else -> "unknown"
                                    }
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member left/banned cached ($reason) - $userId in room $roomId")
                                }
                                // Note: Don't remove from global cache as they might be in other rooms
                                // Note: Keep disk cache for potential future re-joining
                            }
                        }
                    }
                }
            }
            
            // Track rooms with member events in this sync
            currentSyncRooms.add(roomId)
        }
        
        // Update tracking set for next sync comparison
        lastProcessedMembers.clear()
        lastProcessedMembers.addAll(currentSyncRooms)
        
        // Removed debug log to reduce log spam during initial sync
    }
    
    /**
     * Process account_data from sync_complete or cached state
     * Can be called with either a sync JSON object or a direct account_data JSON string
     * 
     * Rules for processing:
     * 1. Only process keys that are present in accountDataJson (partial updates)
     * 2. If a key is present but empty/null, clear the corresponding state
     * 3. If a key is missing, preserve existing state (don't touch it)
     * 
     * IMPORTANT: This function should be called with the merged account_data from cache/state,
     * not the incoming partial data.
     * This ensures that keys not present in the incoming sync are preserved.
     */
    private fun processAccountData(accountDataJson: JSONObject) {
        // Account data is already extracted, process it directly
        if (BuildConfig.DEBUG) {
            val allKeys = accountDataJson.keys().asSequence().toList()
            android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Processing account_data with ${allKeys.size} keys: ${allKeys.joinToString(", ")}")
        }
        
        // Process recent emoji account data
        // Check if key is present (even if null/empty) - this indicates we should process it
        if (accountDataJson.has("io.element.recent_emoji")) {
            val recentEmojiData = accountDataJson.optJSONObject("io.element.recent_emoji")
            if (recentEmojiData != null) {
                val content = recentEmojiData.optJSONObject("content")
                val recentEmojiArray = content?.optJSONArray("recent_emoji")
                
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Found io.element.recent_emoji, content=${content != null}, array length=${recentEmojiArray?.length() ?: 0}")
                }
                
                if (recentEmojiArray != null && recentEmojiArray.length() > 0) {
                    val frequencies = mutableListOf<Pair<String, Int>>()
                    for (i in 0 until recentEmojiArray.length()) {
                        val emojiEntry = recentEmojiArray.optJSONArray(i)
                        if (emojiEntry != null && emojiEntry.length() >= 1) {
                            val emoji = emojiEntry.optString(0)
                            if (emoji.isNotBlank()) {
                                // Get count from entry, default to 1 if not present
                                val count = if (emojiEntry.length() >= 2) {
                                    emojiEntry.optInt(1, 1)
                                } else {
                                    1
                                }
                                frequencies.add(Pair(emoji, count))
                            }
                        }
                    }
                    // Sort by frequency (descending) to ensure proper order
                    val sortedFrequencies = frequencies.sortedByDescending { it.second }
                    if (sortedFrequencies.isNotEmpty()) {
                        // CRITICAL FIX: Always trust the server's data as the source of truth.
                        // The server always sends the FULL list in account_data (not partial updates),
                        // so we should always replace our local list with what the server sends.
                        // This ensures we stay in sync with the server and other clients.
                        recentEmojiFrequencies = sortedFrequencies.toMutableList()
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded ${sortedFrequencies.size} recent emojis from account_data (server is source of truth): ${sortedFrequencies.take(5).joinToString(", ") { "${it.first}(${it.second})" }}${if (sortedFrequencies.size > 5) "..." else ""}")
                        val emojisList = recentEmojiFrequencies.map { it.first }
                        RecentEmojisCache.set(emojisList)
                        recentEmojis = emojisList
                        // Mark that we've loaded the full list from the server
                        hasLoadedRecentEmojisFromServer = true
                    } else {
                        // Key is present but array is empty - clear recent emojis
                        recentEmojiFrequencies.clear()
                        RecentEmojisCache.clear()
                        recentEmojis = emptyList()
                        // Still mark as loaded (server has empty list, which is valid)
                        hasLoadedRecentEmojisFromServer = true
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is present but empty, cleared recent emojis")
                    }
                } else {
                    // Key is present but content/array is null or empty - clear recent emojis
                    recentEmojiFrequencies.clear()
                    recentEmojis = emptyList()
                    // Still mark as loaded (server has empty/null list, which is valid)
                    hasLoadedRecentEmojisFromServer = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is present but empty/null, cleared recent emojis")
                }
            } else {
                // Key is present but value is null - clear recent emojis
                recentEmojiFrequencies.clear()
                recentEmojis = emptyList()
                // Still mark as loaded (server has null value, which is valid)
                hasLoadedRecentEmojisFromServer = true
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is null, cleared recent emojis")
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processAccountData - io.element.recent_emoji key not found in account_data")
        }
        // If key is missing, don't process it (preserve existing state)
        
        // Process m.direct account data for DM room detection
        if (accountDataJson.has("m.direct")) {
            val mDirectData = accountDataJson.optJSONObject("m.direct")
            if (mDirectData != null) {
                val content = mDirectData.optJSONObject("content")
                if (content != null && content.length() > 0) {
                    val dmRoomIds = mutableSetOf<String>()
                    val dmUserMap = mutableMapOf<String, MutableSet<String>>()
                    
                    // Extract all room IDs from m.direct content
                    val keys = content.names()
                    if (keys != null) {
                        for (i in 0 until keys.length()) {
                            val userId = keys.optString(i)
                            val roomIdsArray = content.optJSONArray(userId)
                            if (roomIdsArray != null) {
                                val roomsForUser = dmUserMap.getOrPut(userId) { mutableSetOf() }
                                for (j in 0 until roomIdsArray.length()) {
                                    val roomId = roomIdsArray.optString(j)
                                    if (roomId.isNotBlank()) {
                                        dmRoomIds.add(roomId)
                                        roomsForUser.add(roomId)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Update the DM room IDs cache
                    directMessageRoomIds = dmRoomIds
                    directMessageUserMap = dmUserMap.mapValues { it.value.toSet() }
                    if (BuildConfig.DEBUG) android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Loaded ${dmRoomIds.size} DM room IDs for ${dmUserMap.size} users from m.direct account data"
                    )
                    
                    // PERFORMANCE: Update existing rooms in roomMap with correct DM status from account_data
                    // This ensures rooms loaded from cache have correct isDirectMessage flag
                    updateRoomsDirectMessageStatus(dmRoomIds)
                } else {
                    // Key is present but content is null or empty - clear DM room IDs
                    directMessageRoomIds = emptySet()
                    directMessageUserMap = emptyMap()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.direct is present but empty/null, cleared DM room IDs")
                }
            } else {
                // Key is present but value is null - clear DM room IDs
                directMessageRoomIds = emptySet()
                directMessageUserMap = emptyMap()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.direct is null, cleared DM room IDs")
            }
        }
        // If key is missing, don't process it (preserve existing state)
        
        // Process im.ponies.emote_rooms for custom emoji packs
        if (accountDataJson.has("im.ponies.emote_rooms")) {
            val emoteRoomsData = accountDataJson.optJSONObject("im.ponies.emote_rooms")
            if (emoteRoomsData != null) {
                val content = emoteRoomsData.optJSONObject("content")
                val rooms = content?.optJSONObject("rooms")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Found im.ponies.emote_rooms, content=${content != null}, rooms=${rooms != null}, rooms length=${rooms?.length() ?: 0}")
                }
                if (rooms != null && rooms.length() > 0) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found im.ponies.emote_rooms account data with ${rooms.length()} rooms")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found rooms object in emote_rooms, requesting emoji pack data")
                    // Request emoji pack data for each room/pack combination
                    val keys = rooms.names()
                    if (keys != null) {
                        var packCount = 0
                        for (i in 0 until keys.length()) {
                            val roomId = keys.optString(i)
                            val packsObj = rooms.optJSONObject(roomId)
                            if (packsObj != null) {
                                val packNames = packsObj.names()
                                if (packNames != null) {
                                    for (j in 0 until packNames.length()) {
                                        val packName = packNames.optString(j)
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting emoji pack data for pack $packName in room $roomId")
                                        requestEmojiPackData(roomId, packName)
                                        packCount++
                                    }
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requested emoji pack data for $packCount packs across ${keys.length()} rooms")
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No room keys found in emote_rooms")
                    }
                } else {
                    // Key is present but rooms is null or empty - clear emoji/sticker packs
                    EmojiPacksCache.clear()
                    StickerPacksCache.clear()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: im.ponies.emote_rooms is present but empty/null, cleared emoji/sticker packs")
                }
            } else {
                // Key is present but value is null - clear emoji/sticker packs
                EmojiPacksCache.clear()
                StickerPacksCache.clear()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: im.ponies.emote_rooms is null, cleared emoji/sticker packs")
            }
        } else {
            // Key is missing from incoming account_data - preserve existing state
            // Only reload from storage if we have no packs loaded (e.g., after clear_state)
            // Account data is fully populated on every websocket reconnect (clear_state: true)
            // No need to reload from storage - if packs are missing, they'll come on next reconnect
            if (customEmojiPacks.isEmpty() && stickerPacks.isEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No im.ponies.emote_rooms in incoming sync and no packs loaded - will be populated on next reconnect")
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No im.ponies.emote_rooms in incoming sync, preserving existing ${customEmojiPacks.size} emoji packs and ${stickerPacks.size} sticker packs")
            }
        }
        
        // CRITICAL: Log completion of account data processing for debugging startup stalls
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Account data processed.")
        }
        addStartupProgressMessage("Account data processed.")
    }
    
    /**
     * Updates the isDirectMessage flag for all rooms in roomMap based on m.direct account data.
     */
    private fun updateRoomsDirectMessageStatus(dmRoomIds: Set<String>) {
        var updatedCount = 0
        
        // Update each room's isDirectMessage flag based on m.direct account data
        // Update the map in place (roomMap is a val but points to a mutable map)
        for ((roomId, room) in roomMap) {
            val shouldBeDirect = dmRoomIds.contains(roomId)
            if (room.isDirectMessage != shouldBeDirect) {
                // Update room with correct DM status
                roomMap[roomId] = room.copy(isDirectMessage = shouldBeDirect)
                updatedCount++
            }
        }
        
        if (updatedCount > 0) {
            // Update allRooms to reflect the changes
            allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
            // Invalidate cache to force refresh of filtered sections
            invalidateRoomSectionCache()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedCount rooms with correct DM status from m.direct/bridge data")
        }
    }

    // SYNC OPTIMIZATION: Helper functions for diff-based and batched updates
    
    /**
     * Generate a hash for room state to detect actual changes
     */
    private fun generateRoomStateHash(rooms: List<RoomItem>): String {
        return rooms.joinToString("|") { "${it.id}:${it.name}:${it.unreadCount}:${it.messagePreview}:${it.messageSender}:${it.sortingTimestamp}" }
    }
    
    /**
     * Generate a hash for timeline state to detect actual changes
     */
    private fun generateTimelineStateHash(events: List<TimelineEvent>): String {
        return events.takeLast(50).joinToString("|") { "${it.eventId}:${it.timestamp}:${it.content?.toString()}" }
    }
    
    /**
     * Generate a hash for member state to detect actual changes
     */
    private fun generateMemberStateHash(): String {
        return ProfileCache.getAllFlattenedProfiles().entries.take(100).joinToString("|") { "${it.key}:${it.value.displayName}:${it.value.avatarUrl}" }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Adaptive batched UI updates
     * This prevents excessive recompositions by batching rapid updates with context-aware delays
     * 
     * Strategy:
     * - Initial sync (spacesLoaded = false): 100ms delay to batch many rapid updates
     * - Normal operation: 16ms delay (~60fps) for responsive UI
     * - During rapid syncs: Auto-increases delay if updates keep coming
     * 
     * This reduces recompositions from 10-20 per second to ~1-2 during initial sync
     */
    private fun scheduleUIUpdate(updateType: String) {
        pendingUIUpdates.add(updateType)
        
        // Cancel existing batch job if any
        batchUpdateJob?.cancel()
        
        // PERFORMANCE: Adaptive delay based on app state
        val delay = when {
            !spacesLoaded -> 100L  // Longer delay during initial sync to batch many rapid updates
            else -> 16L             // Normal operation - ~60fps for responsive UI
        }
        
        // Schedule new batch update with adaptive delay
        batchUpdateJob = viewModelScope.launch {
            delay(delay)
            
            if (pendingUIUpdates.isNotEmpty()) {
                performBatchedUIUpdates()
                pendingUIUpdates.clear()
            }
        }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Debounced room reordering
     * Updates badges and timestamps immediately, but only reorders the list every 30 seconds
     * This prevents the frustrating "room jumping" effect when new messages arrive
     * Can be forced to sort immediately via forceImmediate parameter
     */
    private fun scheduleRoomReorder(forceImmediate: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReorder = currentTime - lastRoomReorderTime
        
        // Cancel existing reorder job
        roomReorderJob?.cancel()
        
        if (forceImmediate || timeSinceLastReorder >= ROOM_REORDER_DEBOUNCE_MS) {
            // Force immediate sort or enough time has passed, reorder immediately
            performRoomReorder()
        } else {
            // Schedule reorder for later
            val delayMs = ROOM_REORDER_DEBOUNCE_MS - timeSinceLastReorder
            roomReorderJob = viewModelScope.launch {
                delay(delayMs)
                performRoomReorder()
            }
        }
    }
    
    /**
     * Force immediate room list re-sorting
     * Called when switching tabs or returning to RoomListScreen
     */
    fun forceRoomListSort() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Force sorting room list (tab change or screen return)")
        scheduleRoomReorder(forceImmediate = true)
    }
    
    /**
     * Actually perform the room reordering
     */
    private fun performRoomReorder() {
        lastRoomReorderTime = System.currentTimeMillis()
        
        // Separate newly joined rooms from existing rooms
        val newlyJoinedRooms = roomMap.values.filter { newlyJoinedRoomIds.contains(it.id) }
        val existingRooms = roomMap.values.filter { !newlyJoinedRoomIds.contains(it.id) }
        
        // Sort existing rooms by timestamp (newest first)
        val sortedExistingRooms = existingRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        // Sort newly joined rooms by timestamp (newest first) - these go at the top
        val sortedNewlyJoinedRooms = newlyJoinedRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        // Combine: newly joined rooms first, then existing rooms
        val sortedRooms = sortedNewlyJoinedRooms + sortedExistingRooms
        
        // Clear newly joined set after sorting (rooms are no longer "new" after first sort)
        if (newlyJoinedRoomIds.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sorting ${newlyJoinedRoomIds.size} newly joined rooms to the top")
            newlyJoinedRoomIds.clear()
        }
        
        // ANTI-FLICKER FIX: Only update allRooms if the order actually changed
        // Compare room IDs to detect order changes without creating new list instances unnecessarily
        val currentRoomIds = allRooms.map { it.id }
        val newRoomIds = sortedRooms.map { it.id }
        val orderChanged = currentRoomIds != newRoomIds
        
        if (orderChanged || allRooms.size != sortedRooms.size) {
            // Order changed or size changed - update the list
            setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)), skipCounterUpdate = true)
            allRooms = sortedRooms
            invalidateRoomSectionCache()
            
            // Trigger UI update
            needsRoomListUpdate = true
            scheduleUIUpdate("roomList")
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PERFORMANCE - Debounced room reorder completed (${sortedRooms.size} rooms, order changed: $orderChanged)")
        } else {
            // Order didn't change - skip update to prevent unnecessary recomposition
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PERFORMANCE - Room reorder skipped (order unchanged, ${sortedRooms.size} rooms)")
        }
    }
    /**
     * Perform batched UI updates only for changed sections
     */
    private fun performBatchedUIUpdates() {
        // Only update counters for sections that actually need updates
        if (needsRoomListUpdate) {
            roomListUpdateCounter++
            needsRoomListUpdate = false
            
        }
        
        if (needsTimelineUpdate) {
            timelineUpdateCounter++
            needsTimelineUpdate = false

        }
        
        if (needsMemberUpdate) {
            memberUpdateCounter++
            needsMemberUpdate = false

        }
        
        if (needsReactionUpdate) {
            reactionUpdateCounter++
            needsReactionUpdate = false
 
        }
        
        // Keep for backward compatibility temporarily
        updateCounter++
        

    }

    // NAVIGATION PERFORMANCE: Helper functions for prefetching and navigation caching
    
    /**
     * Prefetch essential room data for rooms visible or near visible in the room list
     */
    fun prefetchRoomData(visibleRoomIds: List<String>, scrollPosition: Int = 0) {
        // NAVIGATION PERFORMANCE: Update scroll position for prefetch tracking
        lastRoomListScrollPosition = scrollPosition
        
        // Only prefetch if we haven't done it recently (avoid excessive requests)
        // CRITICAL FIX: Room state (including bridge info) doesn't change frequently, so use 30 minutes
        // This prevents unnecessary re-requests while still allowing updates if room state actually changes
        val currentTime = System.currentTimeMillis()
        val prefetchThreshold = 30 * 60 * 1000L // 30 minutes (room state is relatively stable)
        
        visibleRoomIds.forEach { roomId ->
            val navigationState = navigationCache[roomId]
            val shouldPrefetch = when {
                navigationState == null -> true // Never prefetched
                currentTime - navigationState.lastPrefetchTime > prefetchThreshold -> true // Stale data
                !navigationState.essentialDataLoaded -> true // Essential data missing
                else -> false
            }
            
            if (shouldPrefetch && !prefetchedRooms.contains(roomId)) {
                prefetchEssentialRoomData(roomId)
            }
        }
    }
    
    /**
     * Prefetch essential room data (basic state, timeline cache check)
     */
    private fun prefetchEssentialRoomData(roomId: String) {
        prefetchedRooms.add(roomId)
        
        // Initialize navigation state if not exists
        if (!navigationCache.containsKey(roomId)) {
            navigationCache[roomId] = RoomNavigationState(roomId)
        }
        
        // CRITICAL FIX: Defer bridge protocol avatar requests until after initial sync completes
        // During initial sync with 580+ rooms, sending hundreds of get_room_state requests overwhelms the system
        // Bridge avatars are non-essential and can be loaded lazily after UI is shown
        if (!initialSyncComplete) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Deferring room state request for $roomId until initial sync completes (bridge avatars are non-essential)")
            }
            // Update navigation state but don't request room state yet
            val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
            navigationCache[roomId] = currentState.copy(
                essentialDataLoaded = false, // Mark as not loaded yet
                lastPrefetchTime = System.currentTimeMillis()
            )
            return
        }
        
        // NAVIGATION PERFORMANCE: Load essential data first (room state without members)
        if (!pendingRoomStateRequests.contains(roomId)) {
            requestRoomState(roomId)
        }
        
        // Update navigation state
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            essentialDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Load additional room details (members, full timeline) after essential data
     */
    private fun loadRoomDetails(roomId: String, navigationState: RoomNavigationState) {
        // OPPORTUNISTIC PROFILE LOADING: Skip bulk member loading to prevent OOM
        // Profiles will be loaded on-demand when actually needed for rendering
        if (navigationState.essentialDataLoaded && !navigationState.memberDataLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SKIPPING bulk member loading for $roomId (using opportunistic loading)")
            navigationCache[roomId] = navigationState.copy(memberDataLoaded = true)
        }
        
        // Load additional timeline data if needed
        if (navigationState.essentialDataLoaded && !navigationState.timelineDataLoaded) {
            navigationCache[roomId] = navigationState.copy(timelineDataLoaded = true)
        }
    }
    
    /**
     * Get cached navigation state for a room
     */
    fun getRoomNavigationState(roomId: String): RoomNavigationState? {
        return navigationCache[roomId]
    }

    /**
     * Process a single initial sync_complete message (called after init_complete for queued messages)
     * This is the same logic as updateRoomsFromSyncJsonAsync but without the queue check
     * @param onComplete Callback invoked when processing actually completes (after DB work)
     * @return Job for the summary update, or null if no summary update is needed
     */
    private fun processInitialSyncComplete(syncJson: JSONObject, onComplete: (suspend () -> Unit)? = null): Job? {
        return try {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟣 processInitialSyncComplete: START - request_id=${syncJson.optInt("request_id", 0)}")
            }
            
            handleSyncToDeviceEvents(syncJson)
            
            // CRITICAL FIX: handleClearStateReset must be called BEFORE parsing for queued messages
            // This ensures state is cleared atomically before processing the message
            val data = syncJson.optJSONObject("data")
            val isClearState = data?.optBoolean("clear_state") == true
            if (isClearState) {
                val requestId = syncJson.optInt("request_id", 0)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "🟣 processInitialSyncComplete: clear_state=true in queued message - clearing state (request_id=$requestId)")
                }
                handleClearStateReset()
            }
            
            // Update last sync timestamp immediately (this is lightweight)
            lastSyncTimestamp = System.currentTimeMillis()
            
            // Update service with new sync timestamp
            WebSocketService.updateLastSyncTimestamp()
            
            // Extract request_id from sync_complete (no longer tracked for reconnection)
            val requestId = syncJson.optInt("request_id", 0)
        
        // Process sync_complete in memory (run in background)
        // CRITICAL FIX: Return the summary update job so we can wait for it to complete
        val summaryUpdateJob = appContext?.let { context ->
            ensureSyncIngestor()

            // Clone JSON before launching background jobs to avoid concurrent mutation issues
            val persistenceJson = try {
                org.json.JSONObject(syncJson.toString())
            } catch (e: Exception) {
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: Failed to clone sync_complete JSON for persistence: ${e.message}",
                    e
                )
                null
            }
            
            viewModelScope.launch(Dispatchers.IO) {
                val jsonForPersistence = persistenceJson ?: try {
                    org.json.JSONObject(syncJson.toString())
                } catch (cloneException: Exception) {
                    android.util.Log.e(
                        "Andromuks",
                        "AppViewModel: Unable to clone sync_complete JSON on IO dispatcher: ${cloneException.message}",
                        cloneException
                    )
                    null
                }

                if (jsonForPersistence == null) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Skipping sync persistence because JSON clone failed"
                    )
                    // Still invoke callback even if we skip (to update counter)
                    onComplete?.invoke()
                    return@launch
                }

                try {
                    // Pass isAppVisible to SyncIngestor for battery optimizations
                    // Returns IngestResult with rooms that had events and parsed invites
                    val ingestResult = syncIngestor?.ingestSyncComplete(jsonForPersistence!!, requestId, currentRunId, isAppVisible)
                    val roomsWithEvents = ingestResult?.roomsWithEvents ?: emptySet()
                    val invites = ingestResult?.invites ?: emptyList()
                    
                    // CRITICAL: Update pendingInvites directly from parsed invites (in-memory only)
                    if (invites.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Update in-memory pendingInvites map
                            invites.forEach { invite ->
                                PendingInvitesCache.updateInvite(invite)
                            }
                            // Trigger UI update to show invites
                            needsRoomListUpdate = true
                            roomListUpdateCounter++
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated ${invites.size} invites from sync_complete (in-memory only, total: ${pendingInvites.size})")
                        }
                    }
                    
                    // CRITICAL: Notify RoomListScreen that room summaries may have been updated
                    // This ensures room list shows new message previews/senders immediately
                    if (roomsWithEvents.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            roomSummaryUpdateCounter++
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room summaries updated for ${roomsWithEvents.size} rooms - triggering RoomListScreen refresh (roomSummaryUpdateCounter: $roomSummaryUpdateCounter, isAppVisible=$isAppVisible)")
                        }
                    }
                    
                    // CRITICAL FIX: Don't invoke completion callback here - it will be invoked after account data processing completes
                    // The completion callback is now invoked in accountDataProcessingJob after processParsedSyncResult completes
                    // This ensures startup doesn't complete before account data is processed
                    
                    // Events are processed in-memory via processSyncEventsArray()
                    // No DB refresh needed - timeline is updated directly from sync_complete events
                    
                    // CRITICAL: Update last_received_request_id in RAM after sync_complete is processed successfully
                    // This is used for faster reconnections (only on reconnections, not initial connections)
                    // Note: request_id can be negative (and usually is), so check != 0 instead of > 0
                    val requestId = syncJson.optInt("request_id", 0)
                    if (requestId != 0 && appContext != null) {
                        WebSocketService.updateLastReceivedRequestId(requestId, appContext!!)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting sync_complete: ${e.message}", e)
                    // Don't block UI updates if persistence fails
                    // Still invoke callback to update counter even on error
                    onComplete?.invoke()
                }
            }
        } ?: run {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Skipping sync persistence because appContext is null"
            )
            // CRITICAL FIX: Don't invoke callback here - it will be invoked in accountDataProcessingJob
            // The account data processing job runs regardless of appContext, so callback will be invoked there
            null
        }
        
            // CRITICAL FIX: Account data is processed in processParsedSyncResult (called from background job below)
            // Don't process it here to avoid duplicate processing
            // This ensures account data is processed once, in the correct order, after sync parsing completes
            
            // PERFORMANCE: Move heavy JSON parsing to background thread
            // CRITICAL FIX: Capture this job so we can wait for account data processing to complete
            val accountDataProcessingJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "🟣 processInitialSyncComplete: Starting background parsing (roomMap.size=${roomMap.size})")
                    }
                    // Parse sync data on background thread (200-500ms for large accounts)
                    // IMPORTANT: use snapshot to avoid ConcurrentModification when roomMap is cleared/reset concurrently.
                    val existingRoomsSnapshot = synchronized(roomMap) { HashMap(roomMap) }
                    // isClearState was already determined above (before handleClearStateReset)
                    val syncResult = SpaceRoomParser.parseSyncUpdate(
                        syncJson, 
                        RoomMemberCache.getAllMembers(), 
                        this@AppViewModel,
                        existingRooms = existingRoomsSnapshot, // Pass snapshot to avoid concurrent modification
                        isClearState = isClearState
                    )
                    
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "🟣 processInitialSyncComplete: Parsed sync - newRooms=${syncResult.newRooms.size}, updatedRooms=${syncResult.updatedRooms.size}, removedRooms=${syncResult.removedRoomIds.size}")
                    }
                    
                    // SpaceRoomParser parses all room data including message previews and metadata
                    
                    // Switch back to main thread for UI updates only
                    withContext(Dispatchers.Main) {
                        try {
                            processParsedSyncResult(syncResult, syncJson)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("Andromuks", "🟣 processInitialSyncComplete: processParsedSyncResult completed successfully")
                            }
                            // CRITICAL FIX: Invoke completion callback AFTER account data processing completes
                            // This ensures startup doesn't complete before account data is processed
                            onComplete?.invoke()
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH in processParsedSyncResult - ${e.message}", e)
                            // Still invoke callback even on error to update counter
                            onComplete?.invoke()
                            // Don't rethrow - continue processing other messages
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH in background parsing - ${e.message}", e)
                    // Still invoke callback even on error to update counter
                    onComplete?.invoke()
                    // Don't rethrow - continue processing other messages
                }
            }
            
            // CRITICAL FIX: Return the account data processing job instead of summary update job
            // This ensures we wait for account data processing to complete before marking startup as complete
            // The summary update job runs in parallel but doesn't block startup completion
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟣 processInitialSyncComplete: END - returning accountDataProcessingJob (will wait for account data processing)")
            }
            accountDataProcessingJob
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH at start - ${e.message}", e)
            // Return null to indicate failure, but don't crash the app
            null
        }
    }
    
    /**
     * Handles server-directed clear_state=true: purge in-memory derived state
     * while keeping events/profile/media intact.
     */
    private fun handleClearStateReset() {
        if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: clear_state=true received - clearing derived room/space state (events preserved)")
        clearDerivedStateInMemory()
        
        ensureSyncIngestor()
        
        runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                syncIngestor?.handleClearStateSignal()
            }
        }.onFailure {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Failed to clear derived state on clear_state: ${it.message}", it)
        }
    }
    
    /**
     * Clears in-memory derived room/space state so subsequent syncs repopulate from scratch.
     * Events, profile info, and media cache remain untouched.
     */
    private fun clearDerivedStateInMemory() {
        val previousSpacesSize = allSpaces.size
        if (BuildConfig.DEBUG && previousSpacesSize > 0) {
            android.util.Log.w("Andromuks", "AppViewModel: clearDerivedStateInMemory - clearing $previousSpacesSize spaces")
        }
        roomMap.clear()
        allRooms = emptyList()
        invalidateRoomSectionCache()
        allSpaces = emptyList()
        spaceList = emptyList()
        knownSpaceIds.clear()
        storedSpaceEdges = null
        spacesLoaded = false
        newlyJoinedRoomIds.clear()
        loadedSections.clear()
        
        synchronized(readReceiptsLock) {
            readReceipts.clear()
        }
        roomsWithLoadedReceipts.clear()
        roomsWithLoadedReactions.clear()
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        
        // Also clear derived account_data caches so the next full sync repopulates from the
        // authoritative dataset sent after clear_state=true.
        recentEmojiFrequencies.clear()
        recentEmojis = emptyList()
        hasLoadedRecentEmojisFromServer = false
        directMessageRoomIds = emptySet()
        directMessageUserMap = emptyMap()
        EmojiPacksCache.clear()
        StickerPacksCache.clear()
        
        // Clear pending invites - new invites will come from clear_state sync_complete
        PendingInvitesCache.clear()
        
        // CRITICAL: Clear singleton RoomListCache when clear_state=true is received
        // This ensures that when WebSocket reconnects after primary AppViewModel dies,
        // all AppViewModel instances (including new ones) start with a clean cache
        RoomListCache.clear()
        // CRITICAL: Also clear SpaceListCache when clear_state=true is received
        // This ensures spaces are repopulated from the fresh sync_complete messages
        SpaceListCache.clear()
        ReadReceiptCache.clear()
        MessageReactionsCache.clear()
        RecentEmojisCache.clear()
        PendingInvitesCache.clear()
        MessageVersionsCache.clear()
        RoomMemberCache.clear()
        
        // Force room list refresh to reflect cleared state until new data arrives
        needsRoomListUpdate = true
        scheduleUIUpdate("roomList")
    }

    /**
     * PERFORMANCE OPTIMIZATION: Async version that processes JSON on background thread
     * This prevents UI blocking during sync parsing (200-500ms improvement)
     */
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        handleSyncToDeviceEvents(syncJson)
        
        // CRITICAL FIX: Queue sync_complete messages received before init_complete
        // These are initial sync messages that populate all rooms - we'll process them after init_complete
        // CRITICAL: Messages MUST be queued in FIFO order to prevent race conditions
        if (!initialSyncPhase) {
            // We're in initial sync phase (before init_complete) - queue this message
            synchronized(initialSyncCompleteQueue) {
                val clonedJson = JSONObject(syncJson.toString()) // Clone to avoid mutation
                val requestId = syncJson.optInt("request_id", 0)
                initialSyncCompleteQueue.add(clonedJson)
                pendingSyncCompleteCount = initialSyncCompleteQueue.size
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Queued initial sync_complete message (request_id=$requestId, queue size: ${initialSyncCompleteQueue.size}) - FIFO order preserved")
                }
            }
            // Don't process yet - wait for init_complete
            return
        }
        
        // BATTERY OPTIMIZATION: Use batch processor to reduce CPU wake-ups when backgrounded
        // Foreground: processes immediately, Background: batches every 10s (98.75% fewer wake-ups)
        val requestId = syncJson.optInt("request_id", 0)
        val runId = syncJson.optJSONObject("data")?.optString("run_id", "") ?: ""
        viewModelScope.launch {
            syncBatchProcessor.processSyncComplete(syncJson, requestId, runId)
        }
    }
    
    
/**
     * Process parsed sync result and update UI
     * Called on main thread after background parsing completes
     */
    private fun processParsedSyncResult(syncResult: SyncUpdateResult, syncJson: JSONObject) {
        // CRITICAL: Increment sync message count FIRST to prevent duplicate processing
        syncMessageCount++
        
        // NOTE: Invites are loaded from sync_complete right after ingestSyncComplete completes
        // (in the background thread, before processParsedSyncResult runs)
        // This ensures invites are loaded before the UI checks for them
        
        // CRITICAL FIX: Process read receipts from sync_complete for ALL rooms, not just the currently open one
        // This ensures receipts are updated even when rooms are not currently open
        // Receipts are stored globally (by eventId) but should be updated whenever sync_complete arrives
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null) {
                val roomKeys = rooms.keys()
                while (roomKeys.hasNext()) {
                    val roomId = roomKeys.next()
                    val roomData = rooms.optJSONObject(roomId) ?: continue

                    // Only process receipts for:
                    // 1) Rooms that are actively cached (have a timeline cache), OR
                    // 2) The room that is currently open in the UI.
                    // This avoids wasting work on rooms whose timeline we are not keeping,
                    // since paginate will provide authoritative receipts when they are opened.
                    val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
                    val isCurrentRoom = (currentRoomId == roomId)
                    if (!isActivelyCached && !isCurrentRoom) {
                        continue
                    }

                    val receipts = roomData.optJSONObject("receipts")
                    if (receipts != null && receipts.length() > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Processing read receipts from sync_complete for room: $roomId (${receipts.length()} event receipts)")
                        synchronized(readReceiptsLock) {
                            // Use processReadReceiptsFromSyncComplete - sync_complete moves receipts
                            // CRITICAL FIX: Pass roomId to prevent cross-room receipt corruption
                            ReceiptFunctions.processReadReceiptsFromSyncComplete(
                                receipts,
                                readReceipts,
                                { readReceiptsUpdateCounter++ },
                                { userId, previousEventId, newEventId ->
                                    // Track receipt movement for animation (thread-safe)
                                    synchronized(readReceiptsLock) {
                                        receiptMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
                                    }
                                    receiptAnimationTrigger = System.currentTimeMillis()
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Receipt movement detected: $userId from $previousEventId to $newEventId")
                                },
                                roomId = roomId // Pass room ID to prevent cross-room corruption
                            )

                            // Update singleton cache after processing receipts
                            val receiptsForCache = readReceipts.mapValues { it.value.toList() }
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updating ReadReceiptCache with ${receiptsForCache.size} events (${receiptsForCache.values.sumOf { it.size }} total receipts) from sync_complete for room: $roomId")
                            ReadReceiptCache.setAll(receiptsForCache)
                            if (BuildConfig.DEBUG) {
                                val cacheAfter = ReadReceiptCache.getAllReceipts()
                                android.util.Log.d("Andromuks", "AppViewModel: ReadReceiptCache after update: ${cacheAfter.size} events (${cacheAfter.values.sumOf { it.size }} total receipts)")
                            }
                        }
                    }
                }
            }
        }
        
        // Populate member cache from sync data and check for changes
        val oldMemberStateHash = generateMemberStateHash()
        populateMemberCacheFromSync(syncJson)
        val newMemberStateHash = generateMemberStateHash()
        val memberStateChanged = newMemberStateHash != oldMemberStateHash
        val hasRoomChanges = syncResult.updatedRooms.isNotEmpty() ||
                syncResult.newRooms.isNotEmpty() ||
                syncResult.removedRoomIds.isNotEmpty()
        val accountData = data?.optJSONObject("account_data")
        // CRITICAL FIX: account_data is ALWAYS present in sync_complete (usually as {} for no updates)
        // Empty {} means "no updates" - preserve existing state
        // Non-empty means "update these keys" - process them
        // null means "no account_data field" (shouldn't happen per protocol, but handle gracefully)
        val accountDataChanged = accountData != null && accountData.length() > 0
        
        // CRITICAL FIX: Process account_data BEFORE early return check
        // This ensures account_data is ALWAYS processed when present with keys, even if there are no room/member changes
        // This is essential after clear_state=true when account_data arrives in subsequent sync_completes
        val isClearState = data?.optBoolean("clear_state") == true
        if (accountData != null) {
            if (accountData.length() > 0) {
                // Account_data has keys - process them (this updates recent emojis, m.direct, etc.)
                if (BuildConfig.DEBUG) {
                    val accountDataKeys = accountData.keys().asSequence().toList()
                    android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Processing account_data with keys: ${accountDataKeys.joinToString(", ")} (clear_state=$isClearState)")
                }
                processAccountData(accountData)
            } //else {
            //    // Account_data is empty {} - no updates, preserve existing state
            //    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Incoming account_data is empty {}, preserving existing state")
            //}
        } else {
            // Account_data is null (special case: first clear_state message may have null)
            // This means "no account_data updates" - preserve existing state
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - account_data is null (preserving existing state)")
        }
        
        // Early return check AFTER processing account_data
        // This ensures account_data is processed even when there are no room/member changes
        if (!hasRoomChanges && !accountDataChanged && !memberStateChanged) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: processParsedSyncResult - no changes detected (rooms/account/member), skipping UI work (account_data already processed above)"
                )
            }
            return
        }
        
        // Auto-save state periodically (every 10 sync_complete messages) for crash recovery
        if (syncMessageCount > 0 && syncMessageCount % 10 == 0) {
            appContext?.let { context ->
                saveStateToStorage(context)
            }
        }
        
        // BATTERY OPTIMIZATION: This loop only processes rooms that actually changed in this sync (not all 588 rooms)
        // syncResult.updatedRooms typically contains 1-10 rooms per sync, not all rooms
        // Total cost: ~0.01-0.1ms per sync (much better than processing all 588 rooms)
        // Update existing rooms
        syncResult.updatedRooms.forEach { room ->
            val existingRoom = roomMap[room.id]
            if (existingRoom != null) {
                // Preserve existing message preview and sender if new room data doesn't have one
                // CRITICAL: Also preserve isFavourite and isLowPriority flags if sync doesn't include account_data.m.tag
                // This prevents favorite rooms from disappearing from the Favs tab
                val updatedRoom = room.copy(
                    messagePreview = if (room.messagePreview.isNullOrBlank() && !existingRoom.messagePreview.isNullOrBlank()) {
                        existingRoom.messagePreview
                    } else {
                        room.messagePreview
                    },
                    messageSender = if (room.messageSender.isNullOrBlank() && !existingRoom.messageSender.isNullOrBlank()) {
                        existingRoom.messageSender
                    } else {
                        room.messageSender
                    },
                    // Preserve favorite and low priority flags if sync doesn't explicitly update them
                    // SpaceRoomParser only sets these to true if account_data.m.tag is present
                    // If sync doesn't include account_data, we preserve the existing values
                    isFavourite = room.isFavourite || existingRoom.isFavourite, // Keep true if either is true
                    isLowPriority = room.isLowPriority || existingRoom.isLowPriority, // Keep true if either is true
                    isDirectMessage = room.isDirectMessage || existingRoom.isDirectMessage, // Preserve DM status
                    // WRITE-ONLY BRIDGE INFO: Preserve bridge protocol avatar if it was previously set
                    // Bridge info comes from get_room_state (m.bridge event), not from sync_complete
                    // Once set, it's never removed (will be resolved on app restart if room is no longer bridged)
                    // CRITICAL OPTIMIZATION: Also check SharedPreferences cache for bridge info
                    bridgeProtocolAvatarUrl = run {
                        val cachedBridgeAvatar = appContext?.let { context ->
                            net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
                        }
                        val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                            cachedBridgeAvatar
                        } else {
                            null
                        }
                        room.bridgeProtocolAvatarUrl 
                            ?: existingRoom.bridgeProtocolAvatarUrl 
                            ?: cachedBridgeAvatarUrl
                    }
                )
                // Log if favorite status was preserved (for debugging)
                if (existingRoom.isFavourite && !room.isFavourite && updatedRoom.isFavourite) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preserved isFavourite=true for room ${room.id} (sync didn't include account_data.m.tag)")
                }
                roomMap[room.id] = updatedRoom
                // Update singleton cache
                RoomListCache.updateRoom(updatedRoom)
            } else {
                // New room - check SharedPreferences cache for bridge info
                val cachedBridgeAvatar = appContext?.let { context ->
                    net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
                }
                val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                    cachedBridgeAvatar
                } else {
                    null
                }
                
                val roomWithBridgeInfo = if (cachedBridgeAvatarUrl != null) {
                    room.copy(bridgeProtocolAvatarUrl = cachedBridgeAvatarUrl)
                } else {
                    room
                }
                
                roomMap[room.id] = roomWithBridgeInfo
                // Update singleton cache
                RoomListCache.updateRoom(roomWithBridgeInfo)
                if (BuildConfig.DEBUG) {
                    if (cachedBridgeAvatarUrl != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount}) with cached bridge avatar")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount})")
                    }
                }
            }
        }
        
        // BATTERY OPTIMIZATION: This loop only processes newly joined rooms (typically 0-1 per sync)
        // Not all 588 rooms - only rooms that were just added
        // Add new rooms
        syncResult.newRooms.forEach { room ->
            // Check SharedPreferences cache for bridge info
            val cachedBridgeAvatar = appContext?.let { context ->
                net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
            }
            val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                cachedBridgeAvatar
            } else {
                null
            }
            
            val roomWithBridgeInfo = if (cachedBridgeAvatarUrl != null) {
                room.copy(bridgeProtocolAvatarUrl = cachedBridgeAvatarUrl)
            } else {
                room
            }
            
            roomMap[room.id] = roomWithBridgeInfo
            // Update singleton cache
            RoomListCache.updateRoom(roomWithBridgeInfo)
            
            // CRITICAL FIX: Only mark as "newly joined" if initial sync is complete
            // During initial sync, all rooms are "new" because roomMap is empty, but they're not actually newly joined
            // Only mark as newly joined for real-time updates after initial sync is complete
            if (initialSyncProcessingComplete) {
                // Initial sync is complete - this is a real new room, mark as newly joined
            newlyJoinedRoomIds.add(room.id)
            } else {
                // Initial sync - just add the room without marking as newly joined
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added room during initial sync: ${room.name} (not marking as newly joined)")
            }
            
            
        }
        
        // CRITICAL: If we have newly joined rooms, force immediate sort to show them at the top
        if (syncResult.newRooms.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: New rooms detected - forcing immediate sort to show them at the top")
            scheduleRoomReorder(forceImmediate = true)
        }
        
        // BATTERY OPTIMIZATION: This loop only processes rooms that were removed (typically 0 per sync)
        // Not all 588 rooms - only rooms that were just left/removed
        // Remove left rooms
        var roomsWereRemoved = false
        var invitesWereRemoved = false
        val removedRoomIdsSet = syncResult.removedRoomIds.toSet()
        syncResult.removedRoomIds.forEach { roomId ->
            // CRITICAL: Check if left room is a pending invite first (user refused invite on another client)
            val wasPendingInvite = PendingInvitesCache.getInvite(roomId) != null
            if (wasPendingInvite) {
                PendingInvitesCache.removeInvite(roomId)
                invitesWereRemoved = true
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed refused invite (left_rooms): $roomId")
            }
            
            // Remove from roomMap if user was actually joined (user left room on another client)
            val removedRoom = roomMap.remove(roomId)
            // Remove from singleton cache
            RoomListCache.removeRoom(roomId)
            if (removedRoom != null) {
                roomsWereRemoved = true
                // Remove from newly joined set if it was there
                newlyJoinedRoomIds.remove(roomId)
                
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed room (left_rooms): ${removedRoom.name}")
            }
        }
        
        // CRITICAL: If rooms were removed, immediately filter them out from allRooms and update UI
        if (roomsWereRemoved) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Rooms removed - immediately filtering from allRooms and updating UI")
            // Immediately filter out removed rooms from allRooms
            val filteredRooms = allRooms.filter { it.id !in removedRoomIdsSet }
            allRooms = filteredRooms
            invalidateRoomSectionCache()
            
            // Also update spaces list
            setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = filteredRooms)), skipCounterUpdate = true)
            
            // Trigger immediate UI update (bypass debounce)
            needsRoomListUpdate = true
            roomListUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Immediately updated UI after room removal (roomListUpdateCounter: $roomListUpdateCounter)")
        }
        
        // CRITICAL: If invites were removed (refused on another client), trigger UI update
        if (invitesWereRemoved) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invites removed (refused on another client) - updating UI")
            needsRoomListUpdate = true
            roomListUpdateCounter++
        }
        
        //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount [App visible: $isAppVisible]")
        
        // DETECT INVITES ACCEPTED ON OTHER DEVICES: Remove pending invites for rooms already joined
        if (pendingInvites.isNotEmpty()) {
            val acceptedInvites = pendingInvites.keys.filter { roomMap.containsKey(it) }
            if (acceptedInvites.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Detected ${acceptedInvites.size} invites already joined via sync - removing pending invites")
                
                acceptedInvites.forEach { roomId ->
                    PendingInvitesCache.removeInvite(roomId)
                }
                
                // Invites are in-memory only - no local cleanup needed
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed ${acceptedInvites.size} invites from memory (accepted elsewhere)")
                
                // Trigger UI update to remove invites from RoomListScreen
                needsRoomListUpdate = true
                roomListUpdateCounter++
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room list updated after removing accepted invites (roomListUpdateCounter: $roomListUpdateCounter)")
            }
        }
        
        // NOTE: Invites are loaded from sync_complete at the start of processParsedSyncResult()
        // This ensures invites are always loaded even when there are no room changes
        
        // BATTERY OPTIMIZATION: Disabled timeline event caching - events are always persisted to DB by SyncIngestor
        // We now always load from DB when opening a room (no need for multi-room RAM cache)
        // This saves ~6-26ms CPU per sync_complete and ~15MB RAM
        // cacheTimelineEventsFromSync(syncJson)
        
        // SYNC OPTIMIZATION: Update room data (last message, unread count) without immediate sorting
        // This prevents visual jumping while still showing real-time updates
        val allRoomsUnsorted = roomMap.values.toList()
        
        // BATTERY OPTIMIZATION: Update low priority rooms set only when changed (saves SharedPreferences writes)
        // This function now caches the last hash and only writes when low priority status actually changes
        // Without this optimization, we'd write to SharedPreferences on every sync even if nothing changed
        updateLowPriorityRooms(allRoomsUnsorted)
        
        // Diff-based update: Only update UI if room state actually changed
        // BATTERY OPTIMIZATION: generateRoomStateHash is lightweight (O(n) string operations) but necessary for change detection
        // It allows us to skip expensive UI updates when room state hasn't changed
        val newRoomStateHash = generateRoomStateHash(allRoomsUnsorted)
        val roomStateChanged = newRoomStateHash != lastRoomStateHash
        
        // BATTERY OPTIMIZATION: Skip expensive UI updates when app is in background
        if (isAppVisible) {
            // Trigger timestamp update on sync (only for visible UI)
            triggerTimestampUpdate()
            
            // SYNC OPTIMIZATION: Selective updates - only update what actually changed
            if (roomStateChanged) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state changed, updating data without sorting")
                
                // PERFORMANCE: Update room data in current order (preserves visual stability)
                // If allRooms is empty or this is first sync, initialize with sorted list
                if (allRooms.isEmpty()) {
                    // First sync - initialize with sorted list
                    val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                    allRooms = sortedRooms
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initializing allRooms with ${sortedRooms.size} sorted rooms")
                } else {
                    // Update existing rooms in current order, add new rooms at end
                    // PERFORMANCE: Only create new RoomItem instances when data actually changes
                    val existingRoomIds = allRooms.map { it.id }.toSet()
                    var hasChanges = false
                    val updatedExistingRooms = allRooms.mapIndexed { index, existingRoom ->
                        val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                        // Only create new instance if data actually changed (data class equality check)
                        if (updatedRoom != existingRoom) {
                            hasChanges = true
                            updatedRoom
                        } else {
                            existingRoom // Keep existing instance to avoid recomposition
                        }
                    }
                    
                    // Add any new rooms that appeared in roomMap (at the end, will be sorted later)
                    val newRooms = roomMap.values.filter { it.id !in existingRoomIds }
                    
                    // Only update if there are actual changes (new rooms or updated rooms)
                    if (newRooms.isNotEmpty() || hasChanges) {
                        // Combine existing (in current order) with new rooms (will be sorted on next reorder)
                        allRooms = updatedExistingRooms + newRooms
                        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                        // Mark for batched UI update (for badges/timestamps - no sorting)
                        needsRoomListUpdate = true
                        scheduleUIUpdate("roomList")
                    } else {
                        // No changes - skip cache invalidation and UI updates to avoid recomposition
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room data unchanged, skipping updates")
                    }
                }
                
                // PERFORMANCE: Use debounced room reordering (30 seconds) to prevent "room jumping"
                // This allows real-time badge/timestamp updates while only re-sorting periodically
                scheduleRoomReorder()
                
                lastRoomStateHash = newRoomStateHash
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room data updated (no sorting), debounced sort scheduled")
                
                // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on sync_complete)
                // This drastically reduces shortcut updates. Removed shortcut updates from sync_complete processing.
                
                // BATTERY OPTIMIZATION: Only update persons API if sync_complete has DM changes
                // No need to sort - buildDirectPersonTargets() filters to DMs and doesn't use sorted order
                viewModelScope.launch(Dispatchers.Default) {
                    val syncRooms = (syncResult.updatedRooms + syncResult.newRooms).filter { 
                        it.sortingTimestamp != null && it.sortingTimestamp > 0 
                    }
                    
                    val syncDMs = syncRooms.filter { it.isDirectMessage }
                    if (syncDMs.isNotEmpty()) {
                        // Get all DMs from roomMap (no sorting needed - persons API doesn't care about order)
                        val allDMs = roomMap.values.filter { it.isDirectMessage }
                        personsApi?.updatePersons(buildDirectPersonTargets(allDMs))
                    }
                }
            } else {
                // Room state hash unchanged - check if individual rooms need timestamp updates
                // PERFORMANCE: Only update rooms that actually changed to avoid unnecessary recomposition
                if (allRooms.isNotEmpty()) {
                    var needsUpdate = false
                    val updatedRooms = allRooms.map { existingRoom ->
                        val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                        // Only create new instance if data actually changed (data class equality check)
                        if (updatedRoom != existingRoom) {
                            needsUpdate = true
                            updatedRoom
                        } else {
                            existingRoom // Keep existing instance to avoid recomposition
                        }
                    }
                    
                    // Only update if something actually changed
                    if (needsUpdate) {
                        allRooms = updatedRooms
                        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                        // Trigger UI update for timestamp changes only
                        needsRoomListUpdate = true
                        scheduleUIUpdate("roomList")
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Some room timestamps changed, updating UI")
                    } else {
                        // No changes - skip cache invalidation and UI updates to avoid recomposition
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - No room changes detected, skipping all updates")
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state unchanged, allRooms empty")
                }
            }
            
            // SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
            checkAndUpdateCurrentRoomTimelineOptimized(syncJson)
            
            // Timeline is updated directly from sync_complete events via processSyncEventsArray()
            // No DB persistence or refresh needed - all data is in-memory
            
            // SYNC OPTIMIZATION: Schedule member update if member cache actually changed
            if (memberStateChanged) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Member state changed, scheduling UI update")
                needsMemberUpdate = true
                scheduleUIUpdate("member")
                lastMemberStateHash = newMemberStateHash
            }
        } else {
            // BATTERY OPTIMIZATION: App is in background - minimal processing for battery saving
            // We skip expensive operations like sorting and UI updates since no one is viewing the app
            //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: BATTERY SAVE MODE - App in background, skipping UI updates")
            
            // BATTERY OPTIMIZATION: Keep allRooms unsorted when backgrounded (skip expensive O(n log n) sort)
            // We only need sorted rooms when updating shortcuts (every 10 syncs) or when app becomes visible
            // This saves CPU time since sorting 588 rooms takes ~2-5ms per sync
            allRooms = allRoomsUnsorted // Use unsorted list from roomMap - lightweight operation
            
            // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on sync_complete)
            // This drastically reduces shortcut updates. Removed shortcut updates from sync_complete processing.
            
            // BATTERY OPTIMIZATION: Only update persons API if sync_complete has DM changes
            // No need to sort - buildDirectPersonTargets() filters to DMs and doesn't use sorted order
            viewModelScope.launch(Dispatchers.Default) {
                val syncRooms = (syncResult.updatedRooms + syncResult.newRooms).filter { 
                    it.sortingTimestamp != null && it.sortingTimestamp > 0 
                }
                
                val syncDMs = syncRooms.filter { it.isDirectMessage }
                if (syncDMs.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background: Updating persons (DMs changed in sync_complete)")
                    // Get all DMs from roomMap (no sorting needed - persons API doesn't care about order)
                    val allDMs = roomMap.values.filter { it.isDirectMessage }
                    personsApi?.updatePersons(buildDirectPersonTargets(allDMs))
                }
            }
            // Note: We don't invalidate cache on every sync when backgrounded - saves CPU time
        }
        
        // Set spacesLoaded after 3 sync messages, but don't trigger navigation yet
        // Navigation will be triggered by onInitComplete() after all initialization is done
        if (syncMessageCount >= 3 && !spacesLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting spacesLoaded after $syncMessageCount sync messages")
            spacesLoaded = true
        }
    }
    
    fun onInitComplete() {
        android.util.Log.d("Andromuks", "🟣 onInitComplete: START - initialSyncComplete=$initialSyncComplete, spacesLoaded=$spacesLoaded, initialSyncCompleteQueue.size=${initialSyncCompleteQueue.size}")
        addStartupProgressMessage("Initialization complete - processing ${initialSyncCompleteQueue.size} sync messages")
        
        // CRITICAL FIX: Set initialSyncPhase = true to stop queueing and start processing queued messages
        initialSyncPhase = true
        android.util.Log.d("Andromuks", "🟣 onInitComplete: Set initialSyncPhase=true, will process ${initialSyncCompleteQueue.size} queued initial sync_complete messages")

        // Start WebSocket pinger immediately on init_complete so low-traffic accounts don't wait for first sync_complete
        net.vrkknn.andromuks.WebSocketService.startPingLoopOnInitComplete()
        
        // CRITICAL FIX: Set spacesLoaded immediately so UI doesn't wait unnecessarily
        // This is safe because init_complete means the backend is ready
        spacesLoaded = true
        
        // Process all queued initial sync_complete messages
        viewModelScope.launch(Dispatchers.Default) {
                    try {
                        initialSyncProcessingMutex.withLock {
                            val queuedMessages = synchronized(initialSyncCompleteQueue) {
                                val messages = initialSyncCompleteQueue.toList()
                                val totalCount = messages.size
                                initialSyncCompleteQueue.clear()
                                pendingSyncCompleteCount = totalCount // Set total pending count
                                processedSyncCompleteCount = 0 // Reset processed count
                                messages
                            }
                            
                            if (queuedMessages.isNotEmpty()) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Processing ${queuedMessages.size} queued initial sync_complete messages")
                                }
                                
                                // Collect summary update jobs to monitor them (but don't wait for them)
                                val summaryUpdateJobs = mutableListOf<Job>()
                                
                                // PERFORMANCE FIX: Process queued messages in chunks with yields to prevent ANR
                                // This is critical for reconnect scenarios where hundreds of sync batches may queue up
                                val chunkSize = 10 // Process 10 sync batches before yielding
                                
                                // CRITICAL FIX: Optimize unblocking based on sync_complete message pattern for large accounts:
                                // Message 1: clear_state=true, ~100 rooms, space_edges, top_level_spaces (CRITICAL - has spaces info)
                                // Messages 2-7: ~100 rooms each
                                // Message 8: account_data
                                // Strategy: Unblock after message 1 (has spaces) OR after 2 messages (~200 rooms), whichever comes first
                                // This ensures UI shows immediately with spaces info while remaining rooms load in background
                                var hasUnblockedUI = false
                                
                                for ((index, syncJson) in queuedMessages.withIndex()) {
                                    val requestId = syncJson.optInt("request_id", 0)
                                    if (BuildConfig.DEBUG && (index % 50 == 0 || index == queuedMessages.size - 1)) {
                                        android.util.Log.d("Andromuks", "AppViewModel: Processing queued initial sync_complete message ${index + 1}/${queuedMessages.size} (request_id=$requestId) - FIFO sequential processing")
                                    }
                                    
                                    // Check if this is the first message with clear_state=true (has spaces info)
                                    val data = syncJson.optJSONObject("data")
                                    val isClearState = data?.optBoolean("clear_state") == true
                                    val isFirstMessage = index == 0
                                    val hasSpacesInfo = isFirstMessage && isClearState
                                    
                                    // Add progress message for processing sync_complete
                                    if (isClearState) {
                                        addStartupProgressMessage("Processing spaces and initial rooms...")
                                    } else {
                                        val roomsJson = data?.optJSONObject("rooms")
                                        val roomCount = roomsJson?.length() ?: 0
                                        if (roomCount > 0) {
                                            addStartupProgressMessage("Processing $roomCount rooms...")
                                        } else if (data?.optJSONObject("account_data") != null) {
                                            addStartupProgressMessage("Processing account data...")
                                        }
                                    }
                                    
                                    // CRITICAL FIX: Process messages SEQUENTIALLY to prevent race conditions
                                    // Each message must complete before the next one starts to ensure proper ordering
                                    // This prevents out-of-order processing (e.g., request_id=-894263 completing before request_id=0)
                                    val currentIndex = index // Capture index for lambda
                                    try {
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d("Andromuks", "🟣 onInitComplete: About to process message ${currentIndex + 1}/${queuedMessages.size} (sequential processing)")
                                        }
                                        val job = processInitialSyncComplete(syncJson) {
                                            // Update processed count after actual processing completes (inside the job)
                                            // Use launch to switch to Main dispatcher since we're in a suspend context
                                            viewModelScope.launch(Dispatchers.Main) {
                                                processedSyncCompleteCount = currentIndex + 1
                                                if (BuildConfig.DEBUG) {
                                                    android.util.Log.d("Andromuks", "🟣 onInitComplete: Message ${currentIndex + 1} processing complete callback invoked")
                                                }
                                            }
                                        }
                                        if (job != null) {
                                            summaryUpdateJobs.add(job)
                                            // CRITICAL FIX: Wait for this message to complete before processing the next one
                                            // This ensures strict FIFO ordering and prevents race conditions
                                            try {
                                                kotlinx.coroutines.withTimeoutOrNull(30000L) { // 30 second timeout per message (increased for large accounts)
                                                    job.join() // Wait for this message to complete
                                                } ?: run {
                                                    android.util.Log.w("Andromuks", "🟣 onInitComplete: Message ${currentIndex + 1} timed out after 30 seconds - continuing to next message")
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("Andromuks", "🟣 onInitComplete: Message ${currentIndex + 1} failed: ${e.message}", e)
                                                // Continue to next message even if this one fails
                                            }
                                        } else {
                                            android.util.Log.w("Andromuks", "🟣 onInitComplete: processInitialSyncComplete returned null for message ${currentIndex + 1} (may have crashed)")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Andromuks", "🟣 onInitComplete: CRASH while processing message ${currentIndex + 1}/${queuedMessages.size} - ${e.message}", e)
                                        // Continue processing remaining messages even if one fails
                                    }
                                    
                                    // CRITICAL FIX: Unblock UI strategically based on message content
                                    // 1. If first message has clear_state=true (has spaces info), unblock immediately after processing it
                                    // 2. Otherwise, unblock after processing 2 messages (~200 rooms)
                                    // This ensures UI shows with spaces info ASAP while remaining rooms load in background
                                    if (!hasUnblockedUI) {
                                        val shouldUnblock = when {
                                            hasSpacesInfo -> {
                                                // First message with clear_state=true - has spaces info, unblock immediately
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: First message has clear_state=true with spaces info - will unblock after processing")
                                                true
                                            }
                                            (currentIndex + 1) >= 2 -> {
                                                // Processed 2 messages (~200 rooms) - enough to show UI
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: Processed 2 messages (~200 rooms) - will unblock UI")
                                                true
                                            }
                                            else -> false
                                        }
                                        
                                        if (shouldUnblock) {
                                            hasUnblockedUI = true
                                            // CRITICAL: Don't set initialSyncProcessingComplete=true here - wait for ALL messages to be processed
                                            // This allows UI to show early with partial data, but checkStartupComplete() will wait for all processing
                                            // CRITICAL: Ensure profile is loaded before early unblock
                                            ensureCurrentUserProfileLoaded()
                                            withContext(Dispatchers.Main) {
                                                val roomCount = roomMap.size
                                                val reason = if (hasSpacesInfo) "first message with clear_state=true (spaces info)" else "processed 2 messages (~200 rooms)"
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: EARLY UNBLOCK - $reason, have $roomCount rooms - Setting initialSyncComplete=true to unblock UI (processing continues in background)")
                                                initialSyncComplete = true
                                                spacesLoaded = true
                                                // CRITICAL FIX: Allow commands immediately on early unblock
                                                // Don't wait for all room states - bridge badges can load in background
                                                canSendCommandsToBackend = true
                                                flushPendingCommandsQueue()
                                                // Don't call checkStartupComplete() here - it will wait for initialSyncProcessingComplete
                                                // This ensures UI shows early but room list waits for all processing
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: EARLY UNBLOCK - UI can show early, but room list will wait for all ${queuedMessages.size} messages to process")
                                            }
                                        }
                                    }
                                    
                                    // PERFORMANCE FIX: Yield every chunkSize messages to prevent ANR
                                    // This allows the UI thread to remain responsive during heavy reconnect processing
                                    if ((index + 1) % chunkSize == 0) {
                                        kotlinx.coroutines.yield()
                                    }
                                }
                                
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Finished processing all ${queuedMessages.size} initial sync_complete messages - ${summaryUpdateJobs.size} summary update jobs running in background")
                                }
                                
                                // CRITICAL FIX: Ensure initialSyncComplete is set even if early unblock didn't trigger (e.g., < 3 messages)
                                // This is a safety net to ensure UI is always unblocked
                                if (!hasUnblockedUI) {
                                    // CRITICAL: Ensure profile is loaded before marking processing complete
                                    ensureCurrentUserProfileLoaded()
                                    initialSyncProcessingComplete = true
                                    withContext(Dispatchers.Main) {
                                        android.util.Log.d("Andromuks", "🟣 onInitComplete: Finished processing ${queuedMessages.size} messages (early unblock didn't trigger) - Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true")
                                        initialSyncComplete = true
                                        spacesLoaded = true
                                        // CRITICAL FIX: Allow commands immediately after initial sync completes
                                        // Don't wait for all room states - bridge badges can load in background
                                        canSendCommandsToBackend = true
                                        flushPendingCommandsQueue()
                                        checkStartupComplete() // Check if startup is complete
                                        android.util.Log.d("Andromuks", "🟣 onInitComplete: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null} - UI can now be shown")
                                    }
                                }
                            } else {
                                // Queue was empty - set flags immediately
                                android.util.Log.d("Andromuks", "🟣 onInitComplete: No queued messages - setting initialSyncComplete immediately")
                                // CRITICAL: Ensure profile is loaded before marking processing complete
                                ensureCurrentUserProfileLoaded()
                                initialSyncProcessingComplete = true
                                withContext(Dispatchers.Main) {
                                    android.util.Log.d("Andromuks", "🟣 onInitComplete: Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true (empty queue)")
                                    initialSyncComplete = true
                                    spacesLoaded = true
                                    
                                    // CRITICAL FIX: Allow commands immediately after initial sync completes
                                    // Don't wait for all room states - bridge badges can load in background
                                    canSendCommandsToBackend = true
                                    flushPendingCommandsQueue()
                                    
                                    checkStartupComplete() // Check if startup is complete
                                    android.util.Log.d("Andromuks", "🟣 onInitComplete: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error processing initial sync_complete messages: ${e.message}", e)
                    } finally {
                        // CRITICAL FIX: Mark initial sync processing as complete immediately after processing ALL messages
                        // Don't wait for summary update jobs - they run in background and update UI as they complete
                        // Room data is already parsed and in memory, so UI can be shown immediately
                        // CRITICAL: Ensure profile is loaded before marking processing complete
                        ensureCurrentUserProfileLoaded()
                        withContext(Dispatchers.Main) {
                        // CRITICAL FIX: Set all state flags on Main thread to ensure proper visibility
                        // Set initialSyncProcessingComplete FIRST on Main thread to ensure checkStartupComplete() sees it
                        initialSyncProcessingComplete = true
                        
                        // CRITICAL FIX: If early unblock didn't set initialSyncComplete, set it now
                        // If early unblock already set it, just ensure spacesLoaded is set
                        if (!initialSyncComplete) {
                            android.util.Log.d("Andromuks", "🟣 Initial sync processing: Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true")
                            initialSyncComplete = true
                            spacesLoaded = true
                        } else {
                            // Early unblock already set initialSyncComplete, just ensure spacesLoaded is set
                            if (!spacesLoaded) {
                                spacesLoaded = true
                            }
                            android.util.Log.d("Andromuks", "🟣 Initial sync processing: All messages processed - processingComplete=true (early unblock already set initialSyncComplete)")
                        }
                        
                        // CRITICAL FIX: Allow commands immediately after initial sync completes
                        // Don't wait for all room states - bridge badges can load in background
                        if (!canSendCommandsToBackend) {
                            canSendCommandsToBackend = true
                            flushPendingCommandsQueue()
                        }
                        // This ensures bridge badges are loaded before other commands can be sent
                        
                        // CRITICAL FIX: Call checkStartupComplete() AFTER all state is set on Main thread
                        // This ensures proper visibility and prevents race conditions
                        checkStartupComplete()
                        android.util.Log.d("Andromuks", "🟣 Initial sync processing: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null}, isStartupComplete=$isStartupComplete - Room list can now be shown")
                        }
                    }
                }
        
        // Mark initialization as complete - from now on, all sync_complete messages are real-time updates
        initializationComplete = true
        
        // CRITICAL FIX: Don't allow commands yet - wait for all room states to load first
        // canSendCommandsToBackend will be set after all room states are loaded in loadAllRoomStatesAfterInitComplete()
        
        checkStartupComplete() // Check if startup is complete
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initialization complete - future sync_complete messages will trigger UI updates")
        
        // CRITICAL FIX: Request get_room_state for ALL rooms after init_complete and sync_complete processing
        // This ensures bridge badges are loaded before navigating to RoomListScreen
        // These requests are exempt from the canSendCommandsToBackend blocking
        loadAllRoomStatesAfterInitComplete()
        
        // Reset reconnection state now that init_complete has arrived
        // This is safe to call now because connection is confirmed healthy
        resetReconnectionState()
        
        // PHASE 4.2: Reset DNS failure count on successful connection
        resetDnsFailureCount()
        
        // PHASE 4.3: Reset TLS failure count on successful connection
        resetTlsFailureCount()
        
        // PHASE 4.3: Clear certificate error state on successful connection
        if (getCertificateErrorState()) {
            android.util.Log.i("Andromuks", "AppViewModel: Clearing certificate error state (connection succeeded)")
            setCertificateErrorState(false, null)
        }
        
        // Process all pending notification messages from FIFO buffer
        // WebSocket is now healthy (connected and init_complete received)
        processPendingNotificationMessages()
        
        // Now that all rooms are loaded, populate space edges
        addStartupProgressMessage("Processing space edges...")
        populateSpaceEdges()
        
        // Update ConversationsApi with the real homeserver URL and refresh shortcuts
        // This happens after init_complete when we have all the data we need
        if (realMatrixHomeserverUrl.isNotEmpty() && appContext != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updating ConversationsApi with real homeserver URL after init_complete")
            // Create new ConversationsApi instance with real homeserver URL
            conversationsApi = ConversationsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            personsApi = PersonsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on every sync_complete)
            // However, refresh shortcuts once on startup to ensure they're up-to-date with current room state
            if (!shortcutsRefreshedOnStartup) {
                refreshShortcutsOnStartup()
                shortcutsRefreshedOnStartup = true
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping shortcut update on init_complete - shortcuts already refreshed on startup")
            }
        }
        
        // FCM registration with Gomuks Backend will be triggered via callback when token is ready
        // This ensures we don't try to register before the FCM token is available
        
        // Execute any pending notification actions now that websocket is ready
        executePendingNotificationActions()
        
        // Register FCM on every WebSocket connection to ensure backend has current token
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: onInitComplete - registering FCM to ensure backend has current token")
        registerFCMWithGomuksBackend()
        
        // QUEUE FLUSHING FIX: Flush pending queue after init_complete with stabilization delay
        // This ensures backend is ready and prevents triple-sending
        flushPendingQueueAfterReconnection()
        
        // CRITICAL FIX: Force refresh room list after reconnection to ensure data is up-to-date
        // This handles cases where initial sync queue was empty or rooms weren't properly updated
        viewModelScope.launch(Dispatchers.Default) {
            delay(500) // Small delay to let queued messages process
            
            // Force room list update
            forceRoomListSort()
            
            // Trigger UI update
            withContext(Dispatchers.Main) {
                roomListUpdateCounter++
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Forced room list refresh after init_complete")
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        
        // Only trigger navigation callback once to prevent double navigation
        if (!navigationCallbackTriggered) {
            if (onNavigateToRoomList != null) {
                navigationCallbackTriggered = true
                onNavigateToRoomList?.invoke()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
                pendingNavigation = true
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback already triggered, skipping")
        }
    }
    
    /**
     * Executes any pending notification actions after init_complete
     * 
     * DEDUPLICATION: Removes duplicate actions before executing to prevent duplicate sends
     * when WebSocket was not ready and multiple broadcasts were queued.
     */
    private fun executePendingNotificationActions() {
        if (pendingNotificationActions.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending notification actions to execute")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing ${pendingNotificationActions.size} pending notification actions")
        
        // DEDUPLICATION: Remove duplicate actions before executing
        // For send_message actions, keep only the first occurrence of each unique (roomId, text) pair
        val deduplicatedActions = mutableListOf<PendingNotificationAction>()
        val seenSendMessages = mutableSetOf<String>() // "roomId|text"
        
        for (action in pendingNotificationActions) {
            when (action.type) {
                "send_message" -> {
                    if (action.text != null) {
                        val dedupKey = "${action.roomId}|${action.text}"
                        if (seenSendMessages.add(dedupKey)) {
                            // First occurrence - keep it
                            deduplicatedActions.add(action)
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Keeping queued send_message (roomId: ${action.roomId}, text: '${action.text}')")
                        } else {
                            // Duplicate - skip it
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate queued send_message (roomId: ${action.roomId}, text: '${action.text}')")
                            // Still call completion callback to prevent UI stalling
                            action.onComplete?.invoke()
                        }
                    }
                }
                else -> {
                    // Non-send_message actions - keep all (no deduplication needed)
                    deduplicatedActions.add(action)
                }
            }
        }
        
        val removedCount = pendingNotificationActions.size - deduplicatedActions.size
        if (removedCount > 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed $removedCount duplicate actions from queue")
        }
        
        pendingNotificationActions.clear()
        
        deduplicatedActions.forEach { action ->
            when (action.type) {
                "send_message" -> {
                    if (action.text != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing pending send_message for room ${action.roomId}")
                        // Note: sendMessageFromNotification will also check deduplication, but this prevents
                        // duplicate queue entries from being processed
                        sendMessageFromNotification(action.roomId, action.text, action.onComplete)
                    }
                }
                "mark_read" -> {
                    if (action.eventId != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing pending mark_read for room ${action.roomId}")
                        markRoomAsReadFromNotification(action.roomId, action.eventId, action.onComplete)
                    }
                }
            }
        }
    }
    
    /**
     * Stores space edges for later processing after init_complete
     */
    fun storeSpaceEdges(spaceEdges: JSONObject) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Storing space edges for later processing")
        storedSpaceEdges = spaceEdges
        // CRITICAL: Also update singleton cache so space_edges persist across ViewModel instances
        SpaceListCache.setSpaceEdges(spaceEdges)
        
        // Register edge keys as space IDs so filtering remains accurate even before processing edges.
        val edgeIds = mutableSetOf<String>()
        val edgeKeys = spaceEdges.keys()
        while (edgeKeys.hasNext()) {
            val id = edgeKeys.next()
            if (!id.isNullOrBlank()) {
                edgeIds.add(id)
            }
        }
        if (edgeIds.isNotEmpty()) {
            registerSpaceIds(edgeIds)
        }
        
        // If initialization is already complete (e.g., after a pull-to-refresh reconnection),
        // process edges immediately so Spaces tab gets populated without waiting for another init cycle.
        if (initializationComplete) {
            populateSpaceEdges()
        }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Populates space edges in background after init_complete
     * This prevents 50-100ms blocking during app initialization with nested spaces
     * 
     * Process:
     * 1. Create mock sync data on background thread (JSON operations are expensive)
     * 2. Process space edges in background (parsing and filtering)
     * 3. Update UI on main thread (minimal state change)
     */
    private fun populateSpaceEdges() {
        if (storedSpaceEdges == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No stored space edges to populate")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Starting background space edge processing for ${allSpaces.size} spaces")
        
        // PERFORMANCE: Move JSON creation and processing to background thread
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Create a mock sync data object with the stored space edges (background thread)
                val mockSyncData = JSONObject()
                
                // Create rooms object from allRooms data (expensive JSON operations)
                val roomsObject = JSONObject()
                
                // Add all rooms to the mock sync data
                for (room in allRooms) {
                    val roomData = JSONObject()
                    val meta = JSONObject()
                    meta.put("name", room.name)
                    if (room.avatarUrl != null) {
                        meta.put("avatar", room.avatarUrl)
                    }
                    if (room.unreadCount != null) {
                        meta.put("unread_messages", room.unreadCount)
                    }
                    roomData.put("meta", meta)
                    roomsObject.put(room.id, roomData)
                }
                
                mockSyncData.put("rooms", roomsObject)
                mockSyncData.put("space_edges", storedSpaceEdges)
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Created mock data for ${allRooms.size} rooms")
                
                // Process space edges in background (parsing is expensive)
                net.vrkknn.andromuks.utils.SpaceRoomParser.updateExistingSpacesWithEdges(
                    storedSpaceEdges!!, 
                    mockSyncData, 
                    this@AppViewModel
                )
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Completed processing")
                
                // Switch to main thread for UI update
                withContext(Dispatchers.Main) {
                    // Clear stored space edges on main thread (atomic state change)
                    storedSpaceEdges = null
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Space edge processing complete - UI updated")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error processing space edges in background", e)
                // Clear stored space edges even on error to prevent retry
                withContext(Dispatchers.Main) {
                    storedSpaceEdges = null
                }
            }
        }
    }
    
    // Navigation callback
    var onNavigateToRoomList: (() -> Unit)? = null
    private var pendingNavigation = false
    private var navigationCallbackTriggered = false // Prevent multiple triggers
    
    // Pending room navigation from shortcuts
    private var pendingRoomNavigation: String? = null
    
    // Track if the pending navigation is from a notification (for optimized cache handling)
    private var isPendingNavigationFromNotification: Boolean = false
    
    // OPTIMIZATION #1: Direct room navigation (bypasses pending state)
    private var directRoomNavigation: String? = null
    private var directRoomNavigationTimestamp: Long? = null
    
    // Pending bubble navigation from chat bubbles
    private var pendingBubbleNavigation: String? = null

    // Pending highlight targets (e.g., notification taps)
    private val pendingHighlightEvents = ConcurrentHashMap<String, String>()
    
    // Websocket restart callback
    var onRestartWebSocket: ((String) -> Unit)? = null
    
    // App lifecycle state
    var isAppVisible by mutableStateOf(true)
        private set
    
    // Delayed shutdown job for when app becomes invisible
    private var appInvisibleJob: Job? = null
    
    fun setNavigationCallback(callback: () -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback set")
        onNavigateToRoomList = callback
        
        // If we have a pending navigation, trigger it now
        if (pendingNavigation) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggering pending navigation")
            pendingNavigation = false
            navigationCallbackTriggered = true
            callback()
        }
        // If spaces are already loaded (from cached state), DON'T trigger yet
        // Wait for WebSocket to connect and init_complete to trigger it
        else if (spacesLoaded && !navigationCallbackTriggered) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Spaces already loaded from cache, but waiting for WebSocket connection before navigating")
            // Don't trigger here - let onInitComplete() or WebSocket connection handle it
        }
    }
    
    fun setPendingRoomNavigation(roomId: String, fromNotification: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set pending room navigation to: $roomId (fromNotification: $fromNotification)")
        pendingRoomNavigation = roomId
        isPendingNavigationFromNotification = fromNotification
    }
    
    // OPTIMIZATION #1: Direct navigation method (bypasses pending state)
    fun setDirectRoomNavigation(
        roomId: String,
        notificationTimestamp: Long? = null,
        targetEventId: String? = null
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: OPTIMIZATION #1 - Set direct room navigation to: $roomId (timestamp: $notificationTimestamp, targetEventId: $targetEventId)"
        )
        directRoomNavigation = roomId
        directRoomNavigationTimestamp = notificationTimestamp
        if (!targetEventId.isNullOrBlank()) {
            setPendingHighlightEvent(roomId, targetEventId)
        }
    }
    
    fun getDirectRoomNavigation(): String? {
        val roomId = directRoomNavigation
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #1 - Getting direct room navigation: $roomId")
        }
        return roomId
    }
    
    fun clearDirectRoomNavigation() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #1 - Clearing direct room navigation")
        directRoomNavigation = null
        directRoomNavigationTimestamp = null
    }
    
    fun getDirectRoomNavigationTimestamp(): Long? {
        return directRoomNavigationTimestamp
    }
    
    fun getPendingRoomNavigation(): String? {
        val roomId = pendingRoomNavigation
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Getting pending room navigation: $roomId")
        }
        return roomId
    }
    
    fun clearPendingRoomNavigation() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing pending room navigation")
        pendingRoomNavigation = null
        isPendingNavigationFromNotification = false
    }
    
    fun setPendingBubbleNavigation(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set pending bubble navigation to: $roomId")
        pendingBubbleNavigation = roomId
    }
    
    fun getPendingBubbleNavigation(): String? {
        val roomId = pendingBubbleNavigation
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Getting pending bubble navigation: $roomId")
        }
        return roomId
    }
    
    fun clearPendingBubbleNavigation() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing pending bubble navigation")
        pendingBubbleNavigation = null
    }

    fun setPendingHighlightEvent(roomId: String, eventId: String?) {
        if (eventId.isNullOrBlank()) return
        pendingHighlightEvents[roomId] = eventId
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Stored pending highlight event for $roomId -> $eventId"
        )
    }

    fun consumePendingHighlightEvent(roomId: String): String? {
        val eventId = pendingHighlightEvents.remove(roomId)
        if (eventId != null && BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Consuming pending highlight event for $roomId -> $eventId"
            )
        }
        return eventId
    }
    
    /**
     * Called when app becomes visible (foreground)
     */
    fun onAppBecameVisible() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: App became visible")
        isAppVisible = true
        updateAppVisibilityInPrefs(true)
        
        // BATTERY OPTIMIZATION: Notify batch processor to flush pending messages and process immediately
        syncBatchProcessor.onAppVisibilityChanged(true)
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(true)

        if (currentRoomId.isEmpty()) {
            val roomToRestore = pendingRoomToRestore
            if (!roomToRestore.isNullOrEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restoring current room to $roomToRestore after visibility change")
                updateCurrentRoomIdInPrefs(roomToRestore)
            }
        }
        pendingRoomToRestore = null
        
        // Cancel any pending shutdown
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        // BATTERY OPTIMIZATION: Rush process pending rooms and receipts that were deferred when backgrounded
        // CRITICAL: Set processing flag to prevent RoomListScreen from showing stale data
        processPendingItemsIfNeeded()
        
        // Refresh UI with current state (in case updates happened while app was invisible)
        refreshUIState()
        
        // CRITICAL FIX: Ensure current user profile is loaded when app becomes visible
        // This fixes issues when app starts from notification/shortcut and profile wasn't loaded yet
        ensureCurrentUserProfileLoaded()
        
        // If a room is currently open, trigger timeline refresh to show new events from cache
        if (currentRoomId.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh")
            timelineRefreshTrigger++
        }
        
        // WebSocket service maintains connection
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: App visible, refreshing UI with current state")
    }
    
    /**
     * Process pending items if any exist. This ensures RoomListScreen shows up-to-date data.
     * Called when app becomes visible or on startup.
     */
    private fun processPendingItemsIfNeeded() {
        // CRITICAL: Only process if syncIngestor is initialized
        // Don't set flag to true if syncIngestor doesn't exist - no need to block UI
        val syncIngestorInstance = syncIngestor ?: run {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: syncIngestor not initialized, skipping pending items check")
            // Ensure flag is false if syncIngestor doesn't exist
            isProcessingPendingItems = false
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // DEPRECATED: hasPendingItems and rushProcessPendingItems are deprecated - all data is in-memory only
                // No pending items to process - UI can show immediately
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending items to process (deprecated - all data is in-memory only) - not blocking UI")
                isProcessingPendingItems = false
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error in pending items check: ${e.message}", e)
            } finally {
                // Clear processing flag after completion (or error) - CRITICAL: Always clear!
                isProcessingPendingItems = false
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared isProcessingPendingItems flag (finally block)")
            }
        }
    }
    
    /**
     * Ensure current user profile is loaded. Tries cache first, then requests from server.
     * Called when app becomes visible or on startup.
     * Made public so RoomListScreen can call it directly on cold start.
     */
    fun ensureCurrentUserProfileLoaded() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: START - currentUserProfile=${currentUserProfile != null}, currentUserId=$currentUserId")
        }
        if (currentUserProfile == null && currentUserId.isNotBlank()) {
            // CRITICAL FIX: Check ProfileCache singleton first before requesting from server
            // This ensures currentUserProfile is populated even if profile was loaded in another AppViewModel instance
            val cachedProfile = ProfileCache.getGlobalProfileProfile(currentUserId)
            if (cachedProfile != null) {
                // Profile exists in singleton cache - populate currentUserProfile from cache
                currentUserProfile = UserProfile(
                    userId = currentUserId,
                    displayName = cachedProfile.displayName,
                    avatarUrl = cachedProfile.avatarUrl
                )
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Populated from cache - userId: $currentUserId, displayName: ${cachedProfile.displayName}")
                }
                // Trigger checkStartupComplete since profile is now loaded
                checkStartupComplete()
                return
            }
            
            // Profile not in cache - request from server
            if (appContext != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Profile missing from cache, requesting from server - userId: $currentUserId")
                }
                requestUserProfile(currentUserId)
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "🟣 ensureCurrentUserProfileLoaded: appContext is null, cannot request profile")
                }
            }
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Profile already loaded or userId is blank - currentUserProfile=${currentUserProfile != null}, currentUserId=$currentUserId")
            }
        }
    }
    
    /**
     * Lightweight version for chat bubbles - sets visibility without expensive UI refresh
     * Bubbles don't need to update shortcuts or refresh the room list
     */
    fun setBubbleVisible(visible: Boolean) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Bubble visibility set to $visible (lightweight)")
        
        if (visible) {
            if (currentRoomId.isEmpty()) {
                val roomToRestore = pendingRoomToRestore
                if (!roomToRestore.isNullOrEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restoring bubble room to $roomToRestore after becoming visible")
                    updateCurrentRoomIdInPrefs(roomToRestore)
                }
            }
            pendingRoomToRestore = null
            // Cancel any pending shutdown
            appInvisibleJob?.cancel()
            appInvisibleJob = null
            
            // If a room is currently open, trigger timeline refresh to show new events from cache
            if (currentRoomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh for bubble")
                timelineRefreshTrigger++
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Bubble hidden - notifications remain enabled for $currentRoomId"
            )
        }
        // Don't call refreshUIState() - bubbles don't need room list updates or shortcut updates
    }
    
    fun attachToExistingWebSocketIfAvailable() {
        val existingWebSocket = WebSocketService.getWebSocket()
        if (existingWebSocket != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Attaching $viewModelId to existing WebSocket")
            logActivity("Attached to Existing WebSocket", null)
            // REFACTORING: Service owns WebSocket - just register callbacks, no local storage needed
            WebSocketService.registerReceiveCallback(viewModelId, this)
            
            // STEP 2.2: Check if this ViewModel was promoted to primary while it wasn't attached
            val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
            if (currentPrimaryId == viewModelId && instanceRole != InstanceRole.PRIMARY) {
                android.util.Log.i("Andromuks", "AppViewModel: STEP 2.2 - ViewModel $viewModelId was promoted to primary while detached - handling promotion now")
                onPromotedToPrimary()
            } else {
                // STEP 2.1: Register this ViewModel with service (as secondary, if not already primary)
                if (instanceRole != InstanceRole.PRIMARY) {
                    WebSocketService.registerViewModel(viewModelId, isPrimary = false)
                }
            }
            
            // CRITICAL FIX: If WebSocket is connected (CONNECTED state), it means init_complete was already received
            // Mark initial sync as complete immediately to prevent UI from waiting indefinitely
            // This fixes the "Loading rooms..." stall when opening from notification or app icon
            // when the app/foreground service has been running for a while
            if (isWebSocketConnected()) {
                // WebSocket is in CONNECTED state, which means init_complete was already received
                // (connectionState is only set to CONNECTED in onInitCompleteReceived)
                // Mark initialization and initial sync as complete - don't wait for these flags
                // because this might be a new AppViewModel instance that doesn't have these flags set
                // CRITICAL: When attaching to existing WebSocket, all processing is already complete
                // Ensure profile is loaded before marking processing complete
                ensureCurrentUserProfileLoaded()
                initialSyncPhase = true
                initialSyncProcessingComplete = true
                android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Setting initializationComplete=true, initialSyncComplete=true, processingComplete=true (already-initialized WebSocket)")
                initializationComplete = true  // CRITICAL: init_complete was already received by primary instance
                initialSyncComplete = true
                android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: initializationComplete=$initializationComplete, initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, profile=${currentUserProfile != null}")
                
                // CRITICAL FIX: Populate roomMap from cache when attaching to existing WebSocket
                // This ensures the new AppViewModel instance has room data from previous instances
                populateRoomMapFromCache()
                populateSpacesFromCache()
                
                // CRITICAL FIX: Set spacesLoaded if we have rooms (populateRoomMapFromCache already does this, but ensure it's set)
                if (roomMap.isNotEmpty() && !spacesLoaded) {
                    spacesLoaded = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set spacesLoaded=true (have ${roomMap.size} rooms)")
                }
                
                // CRITICAL FIX: When attaching to existing WebSocket, room states have already been loaded by the primary instance
                // Set allRoomStatesLoaded = true to allow startup completion
                if (!allRoomStatesLoaded) {
                    allRoomStatesLoaded = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set allRoomStatesLoaded=true (room states already loaded by primary instance)")
                }
                
                // CRITICAL FIX: Allow commands to be sent when attaching to existing WebSocket
                // The WebSocket is already connected and initialized, so commands should be allowed
                if (!canSendCommandsToBackend) {
                    canSendCommandsToBackend = true
                    flushPendingCommandsQueue()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set canSendCommandsToBackend=true (WebSocket already initialized)")
                }
                
                // CRITICAL FIX: Ensure requestIdCounter is synchronized with the primary instance
                // The primary instance may have incremented requestIdCounter, so we should sync it
                // However, we can't easily get the current counter from the service, so we'll just ensure
                // we start from a reasonable value (the service manages the actual counter)
                // Note: requestIdCounter is per-ViewModel, so this is fine
                
                // CRITICAL FIX: Check if startup is complete now that we have room data
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Checking startup complete - initializationComplete=$initializationComplete, initialSyncComplete=$initialSyncComplete, spacesLoaded=$spacesLoaded, allRoomStatesLoaded=$allRoomStatesLoaded, canSendCommands=$canSendCommandsToBackend, roomMap.size=${roomMap.size}")
                checkStartupComplete()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: After checkStartupComplete - isStartupComplete=$isStartupComplete")
                
                // If spaces are loaded, trigger navigation immediately
                if (spacesLoaded && !navigationCallbackTriggered) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Spaces loaded and WebSocket connected - triggering navigation callback immediately")
                navigationCallbackTriggered = true
                onNavigateToRoomList?.invoke()
            }
            }
            // CRITICAL FIX: Don't set initialSyncComplete = true if WebSocket is not connected
            // Even if spacesLoaded is true, we should wait for the connection to be established
            // and receive init_complete. Setting it to true here would cause a flash:
            // rooms show briefly, then setWebSocket() resets it to false, then "Loading rooms..." appears
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No existing WebSocket to attach for $viewModelId")
            
            // STEP 2.1: Still register ViewModel even if WebSocket doesn't exist (for tracking)
            if (instanceRole != InstanceRole.PRIMARY) {
                WebSocketService.registerViewModel(viewModelId, isPrimary = false)
            }
            
            // CRITICAL FIX: Don't set initialSyncComplete = true here, even if spacesLoaded is true
            // On cold start, a new WebSocket will be created, which will reset initialSyncComplete to false
            // Setting it to true here would cause a flash: rooms show briefly, then "Loading rooms..." appears
            // We should only set initialSyncComplete = true when attaching to an existing, already-initialized WebSocket
            // For cold starts, wait for the new WebSocket to connect and receive init_complete
        }
    }
    
    /**
     * Check for pending items on app startup and process them.
     * Called from MainActivity.onCreate to ensure fresh data before showing RoomListScreen.
     */
    fun checkAndProcessPendingItemsOnStartup(context: Context) {
        // Initialize syncIngestor if not already initialized (with LRU cache listener)
        ensureSyncIngestor()
        // Process pending items if any exist (async - won't block)
        processPendingItemsIfNeeded()
    }
    
    /**
     * Refreshes UI state when app becomes visible
     * This updates the UI with any changes that happened while app was in background
     */
    private fun refreshUIState() {
        // CRITICAL FIX: Don't rebuild allRooms from roomMap - it may have stale unread counts.
        // We now treat in-memory state as the single source of truth.
        
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI with ${sortedRooms.size} rooms")
        
        
        // PERFORMANCE: Use debounced reordering for UI refresh too
        scheduleRoomReorder()
        allRooms = sortedRooms
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        
        // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not in refreshUIState)
        // This drastically reduces shortcut updates. Removed shortcut updates from refreshUIState.
        
        // Always update persons API (needed for conversation bubbles)
        personsApi?.updatePersons(buildDirectPersonTargets(sortedRooms))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: UI refreshed, roomListUpdateCounter: $roomListUpdateCounter")
    }
    
    /**
     * Lightweight refresh of the UI from cached data without restarting WebSocket
     * This should be used when app comes to foreground to update the room list from
     * any cached sync events received while the app was in background
     */
    fun refreshUIFromCache() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI from cached data")
        refreshUIState()
    }
    
    /**
     * Lightweight timeline refresh that triggers UI update from cached timeline data
     * This should be used when app comes to foreground to refresh the timeline view
     * without making new network requests
     */
    fun refreshTimelineUI() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline UI from cached data")
        timelineRefreshTrigger++
    }
    
    /**
     * Called when app becomes invisible (background/standby)
     */
    fun onAppBecameInvisible() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: App became invisible")
        isAppVisible = false
        updateAppVisibilityInPrefs(false)
        
        // BATTERY OPTIMIZATION: Notify batch processor to start batching messages
        syncBatchProcessor.onAppVisibilityChanged(false)
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(false)
        if (currentRoomId.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing current room ($currentRoomId) while app invisible to allow notifications")
            clearCurrentRoomId(shouldRestoreOnVisible = true)
        }
        
        // Save state to storage for crash recovery (preserves run_id and last_received_sync_id)
        // This allows seamless resumption if app is killed by system
        appContext?.let { context ->
            saveStateToStorage(context)
        }
        
        // Cancel any existing shutdown job (no shutdown needed - service maintains connection)
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        // WebSocket service maintains connection in background
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: App invisible, WebSocket service continues maintaining connection")
    }
    
    /**
     * Manually triggers app suspension (for back button from room list).
     * 
     * This function is called when the user presses the back button from the room list screen.
     * With the foreground service, we just save state but keep the WebSocket open.
     */
    fun suspendApp() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: App manually suspended, WebSocket service continues")
        onAppBecameInvisible() // This will save state for crash recovery
    }
    
    override fun onCleared() {
        super.onCleared()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: onCleared - cleaning up resources for $viewModelId")
        
        // STEP 2.1: Unregister this ViewModel from service lifecycle tracking
        WebSocketService.unregisterViewModel(viewModelId)
        
        // PHASE 4: Unregister this ViewModel from receiving WebSocket messages
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unregistering $viewModelId from WebSocket callbacks")
        WebSocketService.unregisterReceiveCallback(viewModelId)
        
        // PHASE 1.3: Clear primary callbacks if this is the primary instance
        if (instanceRole == InstanceRole.PRIMARY) {
            // PHASE 1.3: Validate that this instance is actually the primary before clearing
            val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
            val isActuallyPrimary = WebSocketService.isPrimaryInstance(viewModelId)
            
            if (isActuallyPrimary) {
                // This instance is confirmed as primary - safe to clear
                val cleared = WebSocketService.clearPrimaryCallbacks(viewModelId)
                if (cleared) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared primary callbacks for $viewModelId")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to clear primary callbacks for $viewModelId (unexpected failure)")
                }
            } else {
                // This instance thinks it's primary but WebSocketService says otherwise
                if (currentPrimaryId != null) {
                    android.util.Log.w("Andromuks", "AppViewModel: Instance $viewModelId marked as PRIMARY but WebSocketService reports $currentPrimaryId as primary. State mismatch detected.")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Instance $viewModelId marked as PRIMARY but no primary instance registered in WebSocketService. State mismatch detected.")
                }
                // Attempt to clear anyway (defensive cleanup)
                val cleared = WebSocketService.clearPrimaryCallbacks(viewModelId)
                if (!cleared && BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Clear attempt failed as expected (instance was not primary)")
                }
            }
        } else {
            // Not a primary instance - verify we're not accidentally registered as primary
            if (WebSocketService.isPrimaryInstance(viewModelId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Instance $viewModelId is not marked as PRIMARY but is registered as primary in WebSocketService. Clearing to prevent state corruption.")
                WebSocketService.clearPrimaryCallbacks(viewModelId)
            }
        }
        
        // Cancel any pending jobs
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        if (instanceRole == InstanceRole.PRIMARY) {
            // CRITICAL FIX: Don't clear WebSocket when Activity is swiped away
            // The foreground service should maintain the connection independently
            // Only clear WebSocket if we're actually shutting down (e.g., on logout)
            // Check if service is still running - if so, don't clear the connection
            val serviceStillRunning = WebSocketService.isServiceRunning()
            if (serviceStillRunning) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Service still running - NOT clearing WebSocket (foreground service maintains connection)")
                // Just cancel reconnection, but keep the connection alive
                WebSocketService.cancelReconnection()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Service not running - clearing WebSocket (app shutdown)")
                WebSocketService.cancelReconnection()
                clearWebSocket("ViewModel cleared - service not running")
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping global WebSocket teardown for role=$instanceRole")
        }
    }

    fun getRoomById(roomId: String): RoomItem? {
        return roomMap[roomId]
    }
    
    // Room timeline state
    var currentRoomId by mutableStateOf("")
        private set
    private var pendingRoomToRestore: String? = null
    /**
     * Helper function to set currentRoomId and save it to SharedPreferences.
     * This allows notification services to check if a room is currently open.
     */
    private fun updateCurrentRoomIdInPrefs(roomId: String) {
        if (roomId.isNotEmpty()) {
            pendingRoomToRestore = null
        }
        
        // Update typing users for the new room when switching
        val previousRoomId = currentRoomId
        currentRoomId = roomId
        
        // Update typingUsers to show typing users for the new room
        typingUsers = getTypingUsersForRoom(roomId)

        val shouldPersistForNotifications = instanceRole != InstanceRole.BUBBLE
        if (!shouldPersistForNotifications) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Skipping SharedPreferences current room update for bubble instance (roomId=$roomId)"
            )
            return
        }

        // Save to SharedPreferences so notification service can check if room is open
        // Use commit() instead of apply() to ensure immediate write (critical for notification suppression)
        appContext?.applicationContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            if (roomId.isNotEmpty()) {
                editor.putString("current_open_room_id", roomId)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saving current open room ID to SharedPreferences: $roomId")
            } else {
                editor.remove("current_open_room_id")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing current open room ID from SharedPreferences")
            }
            // Use commit() for synchronous write - critical to prevent race condition with notifications
            val success = editor.commit()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for room ID: $roomId")
        }
    }
    
    private fun updateAppVisibilityInPrefs(visible: Boolean) {
        appContext?.applicationContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            editor.putBoolean("app_is_visible", visible)
            // Use commit() for synchronous write - critical for FCM notification suppression
            val success = editor.commit()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for app visibility: $visible (currentRoomId=$currentRoomId)"
            )
            
            // Verify the write was successful
            val verifyValue = sharedPrefs.getBoolean("app_is_visible", !visible)
            if (verifyValue != visible) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: WARNING - App visibility write verification failed! Expected: $visible, Got: $verifyValue"
                )
            }
        } ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot update app visibility - appContext is null")
        }
    }
    
    /**
     * Sets the current room ID when a timeline screen opens.
     * This should be called by RoomTimelineScreen when it opens to ensure state is consistent
     * across all navigation paths (RoomListScreen, notifications, shortcuts).
     * Also tracks the room as opened (exempt from cache clearing on WebSocket reconnect).
     */
    fun setCurrentRoomIdForTimeline(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setCurrentRoomIdForTimeline called for room: $roomId (current: $currentRoomId)")
        if (currentRoomId != roomId) {
            updateCurrentRoomIdInPrefs(roomId)
            // Add to opened rooms (exempt from cache clearing on reconnect)
            RoomTimelineCache.addOpenedRoom(roomId)
        }
    }
    
    /**
     * Clears the current room ID when user navigates back to room list.
     * This allows notifications to resume for rooms that were previously open.
     * 
     * PERFORMANCE FIX: Also clears in-memory timeline cache to free RAM.
     * Timeline will be rebuilt from cache or server when room is opened again.
     */
    fun clearCurrentRoomId(shouldRestoreOnVisible: Boolean = false, saveToCacheForRoomTimeline: Boolean = true) {
        val previousRoomId = currentRoomId
        if (shouldRestoreOnVisible && currentRoomId.isNotEmpty()) {
            pendingRoomToRestore = currentRoomId
        } else if (!shouldRestoreOnVisible) {
            pendingRoomToRestore = null
            // LRU CACHE: Save current room to cache before clearing (for RoomTimelineScreen quick-switch)
            // BubbleTimelineScreen passes saveToCacheForRoomTimeline=false since it manages its own cache
            if (saveToCacheForRoomTimeline && currentRoomId.isNotEmpty()) {
                saveToLruCache(currentRoomId)
            }
            clearTimelineCache()
            // Remove from opened rooms (no longer exempt from cache clearing)
            if (previousRoomId.isNotEmpty()) {
                RoomTimelineCache.removeOpenedRoom(previousRoomId)
            }
        }
        updateCurrentRoomIdInPrefs("")
    }
    
    /**
     * Clears in-memory timeline cache to free RAM.
     * Called when user leaves a room (navigates back to room list).
     */
    /**
     * Clear all timeline caches and mark all rooms as needing pagination.
     * Called on WebSocket connect/reconnect to ensure all caches are stale.
     */
    fun clearAllTimelineCaches() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing all timeline caches (WebSocket connect/reconnect)")
        
        // Clear RoomTimelineCache (includes actively cached rooms tracking)
        RoomTimelineCache.clearAll()
        
        // Clear internal LRU cache
        // Processed timeline state is cleared by RoomTimelineCache.clearAll()
        
        // Clear tracked oldest rowIds since caches are cleared
        oldestRowIdPerRoom.clear()
        
        // Clear pending paginate tracking since caches are cleared
        roomsWithPendingPaginate.clear()
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: All timeline caches cleared - all rooms marked as needing pagination")
    }
    
    private fun clearTimelineCache() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing timeline cache (${timelineEvents.size} events, ${eventChainMap.size} chain entries)")
        timelineEvents = emptyList()
        eventChainMap.clear()
        editEventsMap.clear()
        // Note: We don't clear messageReactions as those are keyed by eventId and can be reused
    }
    
    var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
        private set
    var isTimelineLoading by mutableStateOf(false)
        private set
    
    // Processed timeline state is now stored in RoomTimelineCache singleton
    // No need for per-instance timelineLruCache - all processed state is shared
    
    /**
     * Get the set of room IDs that are actively cached and should receive events from sync_complete.
     * Used by SyncIngestor to determine if incoming events should trigger cache updates.
     * Returns rooms that are actively cached (have been opened and paginated).
     */
    fun getCachedRoomIds(): Set<String> {
        // Use RoomTimelineCache's proactive tracking instead of just LRU cache
        return RoomTimelineCache.getActivelyCachedRoomIds()
    }
    
    /**
     * Save current timeline state to RoomTimelineCache before switching rooms.
     * Called when leaving a room (but not closing it entirely).
     * Processed state (eventChainMap, editEventsMap, redactionEventsMap) is stored in singleton cache.
     */
    private fun saveToLruCache(roomId: String) {
        if (roomId.isBlank() || timelineEvents.isEmpty()) return
        
        // Build redaction events map and mapping from redactionCache
        val redactionEventsMap = mutableMapOf<String, TimelineEvent>()
        val redactionMapping = mutableMapOf<String, String>()
        
        redactionCache.forEach { (originalEventId, redactionEvent) ->
            redactionEventsMap[redactionEvent.eventId] = redactionEvent
            redactionMapping[originalEventId] = redactionEvent.eventId
        }
        
        // Save processed timeline state to singleton cache (including redactions)
        RoomTimelineCache.saveProcessedTimelineState(
            roomId = roomId,
            eventChainMap = eventChainMap.toMap(),
            editEventsMap = editEventsMap.toMap(),
            redactionEventsMap = redactionEventsMap,
            redactionMapping = redactionMapping
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved processed timeline state for room $roomId (${eventChainMap.size} chains, ${editEventsMap.size} edits, ${redactionEventsMap.size} redactions)")
    }
    
    /**
     * Restore timeline state from RoomTimelineCache if available.
     * Returns true if cache hit, false if miss.
     */
    private fun restoreFromLruCache(roomId: String): Boolean {
        // Get cached events from RoomTimelineCache
        val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: return false
        
        // Get processed timeline state (eventChainMap, editEventsMap, redactionEventsMap, redactionMapping)
        val processedState = RoomTimelineCache.getProcessedTimelineState(roomId)
        
        // Restore events
        timelineEvents = cachedEvents
        
        // Restore processed state if available
        if (processedState != null) {
            eventChainMap.clear()
            eventChainMap.putAll(processedState.eventChainMap)
            editEventsMap.clear()
            editEventsMap.putAll(processedState.editEventsMap)
            
            // Restore redaction events to MessageVersionsCache
            // This ensures deleted messages can be displayed
            processedState.redactionEventsMap.forEach { (redactionEventId, redactionEvent) ->
                // Find the original event ID from the mapping
                val originalEventId = processedState.redactionMapping.entries
                    .find { it.value == redactionEventId }?.key
                
                if (originalEventId != null) {
                    // Update MessageVersionsCache with redaction info
                    val versioned = messageVersions[originalEventId]
                    if (versioned != null) {
                        MessageVersionsCache.updateVersion(originalEventId, versioned.copy(
                            redactedBy = redactionEvent.sender,
                            redactionEvent = redactionEvent
                        ))
                    } else {
                        // Redaction came before original - try to find original event in cached events
                        val originalEvent = timelineEvents.find { it.eventId == originalEventId }
                            ?: RoomTimelineCache.getCachedEvents(roomId)?.find { it.eventId == originalEventId }
                        
                        if (originalEvent != null) {
                            // Found original event - create VersionedMessage with it
                            MessageVersionsCache.updateVersion(originalEventId, VersionedMessage(
                                originalEventId = originalEventId,
                                originalEvent = originalEvent,
                                versions = emptyList(),
                                redactedBy = redactionEvent.sender,
                                redactionEvent = redactionEvent
                            ))
                        } else {
                            // Original event not found - skip for now, will be handled when original arrives via processVersionedMessages
                            // This is OK - processVersionedMessages will handle it when the original event arrives
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Redaction event ${redactionEvent.eventId} for original $originalEventId but original not found - will be handled when original arrives")
                        }
                    }
                }
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored room $roomId from cache (${timelineEvents.size} events, ${processedState.eventChainMap.size} chains, ${processedState.editEventsMap.size} edits, ${processedState.redactionEventsMap.size} redactions)")
        } else {
            // No processed state - will be rebuilt from events
            eventChainMap.clear()
            editEventsMap.clear()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored room $roomId events from cache (${timelineEvents.size} events), but no processed state - will rebuild")
        }
        
        return true
    }
    
    /**
     * Append new events to a cached room's timeline (for simple message appends).
     * Called by SyncIngestor when new events arrive for a cached room.
     * Returns true if events were appended, false if room not cached or needs full re-render.
     */
    fun appendEventsToCachedRoom(roomId: String, newEvents: List<TimelineEvent>): Boolean {
        if (newEvents.isEmpty()) return true
        
        // Update RoomTimelineCache with new events from SyncIngestor
        RoomTimelineCache.mergePaginatedEvents(roomId, newEvents)
        RoomTimelineCache.markRoomAccessed(roomId)
        
        // Check if any event requires full re-render (edits, redactions, reactions)
        val requiresFullRerender = newEvents.any { event ->
            val relationType = event.relationType ?: event.content?.optJSONObject("m.relates_to")?.optString("rel_type")
            relationType == "m.replace" || // Edit
            relationType == "m.annotation" || // Reaction
            event.type == "m.room.redaction"
        }
        
        if (requiresFullRerender) {
            // Invalidate processed state for this room - will be rebuilt on next open
            RoomTimelineCache.clearProcessedTimelineState(roomId)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invalidated processed timeline state for $roomId (edit/redaction/reaction detected)")
            return false
        }
        
        // Check if room has cached events (raw events are in RoomTimelineCache)
        val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: return false
        
        // Append new events and re-sort
        val existingEventIds = cachedEvents.map { it.eventId }.toSet()
        val trulyNewEvents = newEvents.filter { it.eventId !in existingEventIds }
        
        if (trulyNewEvents.isEmpty()) return true
        
        // CRITICAL FIX: Also add events to eventChainMap if this is the currently open room
        // This ensures events aren't lost when handlePaginationMerge rebuilds from eventChainMap
        if (currentRoomId == roomId) {
            for (event in trulyNewEvents) {
                val isEdit = isEditEvent(event)
                if (isEdit) {
                    editEventsMap[event.eventId] = event
                } else {
                    val existingEntry = eventChainMap[event.eventId]
                    if (existingEntry == null) {
                        eventChainMap[event.eventId] = EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp
                        )
                    } else if (event.timestamp > existingEntry.originalTimestamp) {
                        // Update with newer version
                        eventChainMap[event.eventId] = existingEntry.copy(
                            ourBubble = event,
                            originalTimestamp = event.timestamp
                        )
                    }
                }
            }
            // Process edit relationships for newly added events
            processEditRelationships()
            
            // Update processed state in cache
            RoomTimelineCache.saveProcessedTimelineState(
                roomId = roomId,
                eventChainMap = eventChainMap.toMap(),
                editEventsMap = editEventsMap.toMap()
            )
            
            // Rebuild timeline from eventChainMap to ensure all events are included
            buildTimelineFromChain()
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Appended ${trulyNewEvents.size} events to cached room $roomId")
        
        // Check for missing m.in_reply_to targets after adding events from sync_complete
        // If a message references a reply target that's not in the cache, fetch it via get_event
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Re-check cache after adding new events to ensure we have the latest state
                val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                val cachedEventIds = cachedEvents.map { it.eventId }.toSet()
                val missingReplyTargets = mutableSetOf<Pair<String, String>>() // (roomId, eventId)
                
                // Check all newly added events for m.in_reply_to references
                for (event in newEvents) {
                    // Check for m.in_reply_to in both content and decrypted
                    val replyToEventId = event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                        ?: event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                    
                    if (replyToEventId != null && replyToEventId.isNotBlank()) {
                        // Check if the reply target is in the cache
                        if (!cachedEventIds.contains(replyToEventId)) {
                            missingReplyTargets.add(Pair(event.roomId, replyToEventId))
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Missing reply target event_id=$replyToEventId for event ${event.eventId} in room ${event.roomId} (from sync_complete)")
                        }
                    }
                }
                
                // Fetch missing reply targets via get_event
                if (missingReplyTargets.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetching ${missingReplyTargets.size} missing reply target events via get_event (from sync_complete)")
                    
                    for ((targetRoomId, targetEventId) in missingReplyTargets) {
                        // Use a suspend function to fetch the event
                        val deferred = CompletableDeferred<TimelineEvent?>()
                        withContext(Dispatchers.Main) {
                            getEvent(targetRoomId, targetEventId) { event ->
                                deferred.complete(event)
                            }
                        }
                        
                        val fetchedEvent = withTimeoutOrNull(5000L) {
                            deferred.await()
                        }
                        
                        if (fetchedEvent != null) {
                            // Add the fetched event to the cache
                            val memberMap = RoomMemberCache.getRoomMembers(targetRoomId)
                            val eventsJsonArray = org.json.JSONArray()
                            eventsJsonArray.put(fetchedEvent.toRawJsonObject())
                            RoomTimelineCache.addEventsFromSync(targetRoomId, eventsJsonArray, memberMap)
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetched and cached missing reply target event_id=$targetEventId for room $targetRoomId (from sync_complete)")
                        } else {
                            android.util.Log.w("Andromuks", "AppViewModel: Failed to fetch missing reply target event_id=$targetEventId for room $targetRoomId (timeout or error, from sync_complete)")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error checking for missing reply targets from sync_complete", e)
            }
        }
        
        return true
    }
    
    /**
     * Invalidate processed timeline state for a specific room (e.g., when edits/redactions arrive).
     */
    fun invalidateCachedRoom(roomId: String) {
        RoomTimelineCache.clearProcessedTimelineState(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invalidated processed timeline state for room $roomId")
    }
    
    // Trigger for timeline refresh when app resumes (incremented when app becomes visible)
    var timelineRefreshTrigger by mutableStateOf(0)
        private set
    
    // Edit chain tracking system
    data class EventChainEntry(
        val eventId: String,
        var ourBubble: TimelineEvent?,
        var replacedBy: String?,
        val originalTimestamp: Long
    )
    
    private val eventChainMap = mutableMapOf<String, EventChainEntry>()
    private val editEventsMap = mutableMapOf<String, TimelineEvent>() // Store edit events separately
    private val roomsPaginatedOnce = Collections.synchronizedSet(mutableSetOf<String>())

    private fun hasInitialPaginate(roomId: String): Boolean = roomsPaginatedOnce.contains(roomId)

    private fun markInitialPaginate(roomId: String, reason: String) {
        val added = roomsPaginatedOnce.add(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Recorded initial paginate for $roomId (reason=$reason, added=$added)"
        )
        setAutoPaginationEnabled(false, "paginate_lock_$roomId")
    }

    private fun logSkippedPaginate(roomId: String, reason: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Skipping paginate for $roomId ($reason) - already paginated once this session"
        )
    }
    private val roomSnapshotAwaiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()
    
    // Made public to allow access for RoomJoiner WebSocket operations
    var requestIdCounter = 1
        private set
    
    fun getAndIncrementRequestId(): Int {
        val id = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request ID: $id (counter now: $requestIdCounter)")
        return id
    }
    
    private fun registerRoomSnapshotAwaiter(roomId: String, deferred: CompletableDeferred<Unit>) {
        synchronized(roomSnapshotAwaiters) {
            val list = roomSnapshotAwaiters[roomId] ?: mutableListOf<CompletableDeferred<Unit>>().also {
                roomSnapshotAwaiters[roomId] = it
            }
            list.add(deferred)
        }
    }
    
    private fun unregisterRoomSnapshotAwaiter(roomId: String, deferred: CompletableDeferred<Unit>) {
        synchronized(roomSnapshotAwaiters) {
            val list = roomSnapshotAwaiters[roomId] ?: return
            list.remove(deferred)
            if (list.isEmpty()) {
                roomSnapshotAwaiters.remove(roomId)
            }
        }
    }
    
    private fun signalRoomSnapshotReady(roomId: String) {
        val awaiters = synchronized(roomSnapshotAwaiters) {
            roomSnapshotAwaiters.remove(roomId)?.toList()
        } ?: return
        
        for (awaiter in awaiters) {
            if (!awaiter.isCompleted && !awaiter.isCancelled) {
                awaiter.complete(Unit)
            }
        }
    }
    private val timelineRequests = mutableMapOf<Int, String>() // requestId -> roomId
    // Track rooms with pending initial paginate requests to prevent duplicates
    private val roomsWithPendingPaginate = Collections.synchronizedSet(mutableSetOf<String>())
    private val profileRequestRooms = mutableMapOf<Int, String>() // requestId -> roomId (for profile requests initiated from a specific room)
    private val roomStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val messageRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
    // PERFORMANCE: Track pending room state requests to prevent duplicate WebSocket commands
    private val pendingRoomStateRequests = mutableSetOf<String>() // roomId that have pending state requests
    private val reactionRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val markReadRequests = mutableMapOf<Int, String>() // requestId -> roomId
    // Track last sent mark_read command per room to prevent duplicates
    // Key: roomId, Value: eventId that was last sent
    private val lastMarkReadSent = mutableMapOf<String, String>() // roomId -> eventId
    private val readReceipts = mutableMapOf<String, MutableList<ReadReceipt>>() // eventId -> list of read receipts
    private val readReceiptsLock = Any() // Synchronization lock for readReceipts access
    private val roomsWithLoadedReceipts = mutableSetOf<String>() // Track rooms with receipts loaded from cache
    private val roomsWithLoadedReactions = mutableSetOf<String>() // Track rooms with reactions loaded from cache
    // Track receipt movements for animation - userId -> (previousEventId, currentEventId, timestamp)
    // THREAD SAFETY: Protected by readReceiptsLock since it's accessed from background threads
    private val receiptMovements = mutableMapOf<String, Triple<String?, String, Long>>()
    var receiptAnimationTrigger by mutableStateOf(0L)
        private set
    
        // PERFORMANCE: Track new messages for sound notifications only (animations removed)
    // Use ConcurrentHashMap for thread-safe access (modified from background threads, read from UI thread)
    private val newMessageAnimations = ConcurrentHashMap<String, Long>() // eventId -> timestamp
    
    // CRITICAL: Track when each room was opened (in milliseconds, Matrix timestamp format)
    // Only messages with timestamp NEWER than this will animate
    // This ensures paginated (old) messages don't animate, only truly new messages do
    private var roomOpenTimestamps = mutableMapOf<String, Long>() // roomId -> openTimestamp
    // Now using singleton PendingInvitesCache
    private val pendingInvites: Map<String, RoomInvite>
        get() = PendingInvitesCache.getAllInvites()
    
    private fun removePendingInvite(roomId: String) {
        PendingInvitesCache.removeInvite(roomId)
    }
    
    private fun clearPendingInvites() {
        PendingInvitesCache.clear()
    }
    private val roomSummaryRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val joinRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val leaveRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val outgoingRequests = mutableMapOf<Int, String>() // requestId -> roomId (for all outgoing requests)
    private val fcmRegistrationRequests = mutableMapOf<Int, String>() // requestId -> "fcm_registration"
    private var lastFCMRegistrationTime: Long = 0 // Track last registration to prevent duplicates
    private val eventRequests = mutableMapOf<Int, Pair<String, (TimelineEvent?) -> Unit>>() // requestId -> (roomId, callback)
    private val paginateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for pagination)
    private val paginateRequestMaxTimelineIds = mutableMapOf<Int, Long>() // requestId -> max_timeline_id used in request (for progress detection)
    private val backgroundPrefetchRequests = mutableMapOf<Int, String>() // requestId -> roomId (for background prefetch)
    private val freshnessCheckRequests = mutableMapOf<Int, String>() // requestId -> roomId (for single-event freshness checks)
    private val roomStateWithMembersRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit>() // requestId -> callback
    private val userEncryptionInfoRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val mutualRoomsRequests = mutableMapOf<Int, (List<String>?, String?) -> Unit>() // requestId -> callback
    private val trackDevicesRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val resolveAliasRequests = mutableMapOf<Int, (Pair<String, List<String>>?) -> Unit>() // requestId -> callback
    private val getRoomSummaryRequests = mutableMapOf<Int, (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit>() // requestId -> callback
    private val joinRoomCallbacks = mutableMapOf<Int, (Pair<String?, String?>?) -> Unit>() // requestId -> callback
    private val roomSpecificStateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_specific_room_state requests)
    private val roomSpecificProfileCallbacks = mutableMapOf<Int, (String?, String?) -> Unit>() // requestId -> (displayName, avatarUrl) callback
    private val fullMemberListRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_room_state with include_members requests)
    private val mentionsRequests = mutableMapOf<Int, Unit>() // requestId -> Unit (for get_mentions requests)
    private val mentionEventRequests = mutableMapOf<Int, Pair<String, String>>() // requestId -> (roomId, eventId) for fetching reply targets
    
    // PERFORMANCE: Track pending full member list requests to prevent duplicate WebSocket commands
    private val pendingFullMemberListRequests = mutableSetOf<String>() // roomId that have pending full member list requests

    // Element Call widget command tracking (requestId -> deferred response)
    private val widgetCommandRequests = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    
    // OPPORTUNISTIC PROFILE LOADING: Track pending on-demand profile requests
    private val pendingProfileRequests = mutableSetOf<String>() // "roomId:userId" keys for pending profile requests
    private val profileRequests = mutableMapOf<Int, String>() // requestId -> "roomId:userId" for on-demand profile requests
    
    // CRITICAL FIX: Track profile request metadata for timeout handling and cleanup
    private data class ProfileRequestMetadata(
        val requestId: Int,
        val timestamp: Long,
        val userId: String,
        val roomId: String
    )
    private val profileRequestMetadata = mutableMapOf<String, ProfileRequestMetadata>() // "roomId:userId" -> metadata
    
    // Local echoes removed: status/error helpers no longer used.

    // PERFORMANCE: Throttle profile requests to prevent animation-blocking bursts
    // Tracks recent profile request timestamps to skip rapid re-requests during animation window
    private val recentProfileRequestTimes = mutableMapOf<String, Long>() // "roomId:userId" -> timestamp
    private val PROFILE_REQUEST_THROTTLE_MS = 5000L // Skip if requested within last 5 seconds
    private val REACTION_BACKFILL_ON_OPEN_ENABLED = false
    private val AUTO_PAGINATION_ENABLED = false
    
    // Pagination state
    private var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    // Track the oldest timelineRowId from each pagination response per room
    // The oldest event will have the lowest (smallest) timelineRowId (always positive)
    // This is used for the next pull-to-refresh to know where to start paginating from
    private val oldestRowIdPerRoom = mutableMapOf<String, Long>()
    var isPaginating by mutableStateOf(false)
        private set
    var hasMoreMessages by mutableStateOf(true) // Whether there are more messages to load
        private set
    var autoPaginationEnabled by mutableStateOf(false)
        private set
    
    fun setAutoPaginationEnabled(enabled: Boolean, reason: String? = null) {
        if (autoPaginationEnabled != enabled) {
            val reasonText = reason?.let { " ($it)" } ?: ""
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Auto-pagination ${if (enabled) "ENABLED" else "DISABLED"}$reasonText"
            )
            autoPaginationEnabled = enabled
        }
    }
    
    fun hasPendingTimelineRequest(roomId: String): Boolean {
        return timelineRequests.values.any { it == roomId }
    }
    
    
    // REFACTORING: WebSocket is now owned by WebSocketService - no local storage needed
    // All WebSocket operations delegate to WebSocketService.getWebSocket()
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    // NOTE: We no longer track last_received_id - all timeline caches are cleared on connect/reconnect
    private var lastSyncTimestamp: Long = 0 // Timestamp of last sync_complete received
    private var currentRunId: String = "" // Unique connection ID from gomuks backend
    private var vapidKey: String = "" // VAPID key for push notifications
    private var hasHadInitialConnection = false // Track if we've had an initial connection to only vibrate on reconnections

    // NETWORK OPTIMIZATION: Offline caching and connection state
    // STEP 1.2: Made internal so callbacks can update state without capturing AppViewModel
    internal var isOfflineMode = false
        private set
    internal var lastNetworkState = true // true = online, false = offline
        private set
    
    // STEP 1.2: Helper methods to update offline state (used by callbacks)
    internal fun setOfflineMode(isOffline: Boolean) {
        isOfflineMode = isOffline
        lastNetworkState = !isOffline
    }
    private val offlineCacheExpiry = 24 * 60 * 60 * 1000L // 24 hours
    
    // WebSocket reconnection log
    data class ActivityLogEntry(
        val timestamp: Long,
        val event: String,
        val networkType: String? = null
    ) {
        fun toJson(): org.json.JSONObject {
            val json = org.json.JSONObject()
            json.put("timestamp", timestamp)
            json.put("event", event)
            networkType?.let { json.put("networkType", it) }
            return json
        }
        
        companion object {
            fun fromJson(json: org.json.JSONObject): ActivityLogEntry {
                return ActivityLogEntry(
                    timestamp = json.getLong("timestamp"),
                    event = json.getString("event"),
                    networkType = json.optString("networkType").takeIf { it.isNotEmpty() }
                )
            }
        }
    }
    
    private val activityLog = mutableListOf<ActivityLogEntry>()
    private val activityLogLock = Any() // Synchronization lock for activityLog access
    private val maxLogEntries = 100 // Keep last 100 entries
    
    /**
     * Load activity log from SharedPreferences
     */
    private fun loadActivityLogFromStorage(context: android.content.Context? = null) {
        val ctx = context ?: appContext
        ctx?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val logJson = prefs.getString("activity_log", null)
                
                if (logJson != null) {
                    val logArray = org.json.JSONArray(logJson)
                    synchronized(activityLogLock) {
                        activityLog.clear()
                        
                        for (i in 0 until logArray.length()) {
                            val entryJson = logArray.getJSONObject(i)
                            activityLog.add(ActivityLogEntry.fromJson(entryJson))
                        }
                        
                        // Keep only the last maxLogEntries entries
                        if (activityLog.size > maxLogEntries) {
                            val entriesToKeep = activityLog.takeLast(maxLogEntries)
                            activityLog.clear()
                            activityLog.addAll(entriesToKeep)
                        }
                        
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded ${activityLog.size} activity log entries from storage")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to load activity log from storage", e)
            }
        }
    }
    
    /**
     * Save activity log to SharedPreferences
     */
    private fun saveActivityLogToStorage() {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val logArray = org.json.JSONArray()
                
                // Take a snapshot under lock to avoid ConcurrentModificationException
                val entriesToSave: List<ActivityLogEntry> = synchronized(activityLogLock) {
                    if (activityLog.size > maxLogEntries) {
                        activityLog.takeLast(maxLogEntries).toList()
                    } else {
                        activityLog.toList()
                    }
                }
                
                entriesToSave.forEach { entry ->
                    logArray.put(entry.toJson())
                }
                
                prefs.edit()
                    .putString("activity_log", logArray.toString())
                    .apply()
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved ${entriesToSave.size} activity log entries to storage")
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to save activity log to storage", e)
            }
        }
    }
    
    /**
     * Log an activity event (app started, websocket connected, disconnected, etc.)
     */
    fun logActivity(event: String, networkType: String? = null) {
        val entry = ActivityLogEntry(
            timestamp = System.currentTimeMillis(),
            event = event,
            networkType = networkType
        )
        synchronized(activityLogLock) {
            activityLog.add(entry)
            // Keep only the last maxLogEntries entries
            if (activityLog.size > maxLogEntries) {
                activityLog.removeAt(0)
            }
        }
        
        // Persist to storage
        saveActivityLogToStorage()
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Logged activity - $event")
    }
    
    /**
     * Get the activity log for display
     */
    fun getActivityLog(): List<ActivityLogEntry> {
        return synchronized(activityLogLock) { activityLog.toList() }
    }

    // Backwards compatibility - keep old reconnection log methods
    data class ReconnectionLogEntry(
        val timestamp: Long,
        val reason: String
    )
    
    private val reconnectionLog = mutableListOf<ReconnectionLogEntry>()
    
    fun setWebSocket(webSocket: WebSocket) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setWebSocket() called for $viewModelId")
        addStartupProgressMessage("Connected to WebSocket")
        
        // REFACTORING: Service now owns WebSocket - no need to store locally
        // The service already has the reference, we just need to set up callbacks
        
        // Reset requestIdCounter on WebSocket (re)connect to keep IDs manageable
        requestIdCounter = 1
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Reset requestIdCounter to 1 on WebSocket (re)connect")
        
        // CRITICAL FIX: Initialize initial sync phase when WebSocket connects (both initial connection and reconnection)
        // Set initialSyncPhase = false to start tracking sync_complete messages before init_complete
        // This ensures we queue all initial sync_complete messages and process them after init_complete
        // On reconnection, the backend sends new sync_complete messages before init_complete, so we need to queue them again
        initialSyncPhase = false
        synchronized(initialSyncCompleteQueue) {
            initialSyncCompleteQueue.clear()
        }
        initialSyncProcessingComplete = false
        initialSyncComplete = false // Reset so UI waits for initial sync to complete again
        // Reset sync progress counters
        pendingSyncCompleteCount = 0
        processedSyncCompleteCount = 0
        
        // CRITICAL FIX: Block commands on initial connection only (not reconnections with last_received_event)
        // On reconnections with last_received_event, backend doesn't send init_complete, so we can't block
        val isReconnecting = WebSocketService.isReconnectingWithLastReceivedEvent()
        canSendCommandsToBackend = isReconnecting // Allow commands immediately on reconnection
        synchronized(pendingCommandsQueue) {
            pendingCommandsQueue.clear()
        }
        
        // Reset room state loading state
        allRoomStatesRequested = false
        allRoomStatesLoaded = isReconnecting // On reconnection, mark as loaded (we skip loading)
        totalRoomStateRequests = 0
        completedRoomStateRequests = 0
        synchronized(pendingRoomStateResponses) {
            pendingRoomStateResponses.clear()
        }
        
        if (BuildConfig.DEBUG) {
            if (isReconnecting) {
                android.util.Log.d("Andromuks", "AppViewModel: Reconnection detected - allowing commands immediately (no init_complete will be sent)")
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Initial connection - blocking commands until init_complete + all sync_complete + all room states processed")
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initial sync phase started - will queue sync_complete messages until init_complete")
        
        // CRITICAL FIX: Process deferred emoji pack requests when WebSocket is set
        // This ensures custom emojis are loaded even if account_data was processed before WebSocket connected
        if (deferredEmojiPackRequests.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${deferredEmojiPackRequests.size} deferred emoji pack requests after WebSocket connection")
            val requests = deferredEmojiPackRequests.toList()
            deferredEmojiPackRequests.clear()
            for ((roomId, packName) in requests) {
                requestEmojiPackData(roomId, packName)
            }
        }
        
        // PHASE 4: Register this ViewModel to receive WebSocket messages
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registering $viewModelId to receive WebSocket messages")
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // Set up service callbacks for ping/pong (using deprecated method for now)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting up service callbacks")
        @Suppress("DEPRECATION")
        WebSocketService.setWebSocketSendCallback { command, requestId, data ->
            sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
        }
        
        // PHASE 1.4 FIX: Primary callbacks are now registered in markAsPrimaryInstance()
        // This ensures callbacks are available before WebSocket connection is established
        // If callbacks weren't registered yet (shouldn't happen), register them now as fallback
        if (instanceRole == InstanceRole.PRIMARY) {
            val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
            if (currentPrimaryId != viewModelId) {
                // Callbacks weren't registered yet - register them now as fallback
                android.util.Log.w("Andromuks", "AppViewModel: Primary callbacks not registered yet in setWebSocket() - registering now as fallback")
                registerPrimaryCallbacks()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Primary callbacks already registered for $viewModelId")
            }
        }
        
        // Delegate WebSocket management to service
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Calling WebSocketService.setWebSocket()")
        WebSocketService.setWebSocket(webSocket)
        
        // FCM registration will happen in onInitComplete() after WebSocket is fully ready
        // This prevents duplicate registrations from setWebSocket() and onInitComplete()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setWebSocket - FCM registration will happen in onInitComplete()")
        
        // Broadcast that socket connection is available
        android.util.Log.i("Andromuks", "AppViewModel: WebSocket connection established - ${pendingWebSocketOperations.size} pending operations will be flushed after init_complete")
        
        // Track if we've had an initial connection (no longer needed for vibration)
        if (!hasHadInitialConnection) {
            hasHadInitialConnection = true
        }
        
        // QUEUE FLUSHING FIX: Don't flush queue here - wait for init_complete
        // This prevents triple-sending and ensures backend is ready before retrying
    }
    
    /**
     * RECONNECTION: Reset reconnection state after successful connection
     */
    fun resetReconnectionState() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Resetting reconnection state (successful connection)")
        // Delegate to service
        WebSocketService.resetReconnectionState()
    }
    
    /**
     * RECONNECTION: Schedule WebSocket reconnection with exponential backoff
     * 
     * This method implements exponential backoff to avoid overwhelming the server
     * during outages and to be more battery efficient.
     * 
     * @param reason Human-readable reason for reconnection (for logging)
     */
    fun scheduleReconnection(reason: String) {
        // PHASE 4.3: Don't reconnect if there's a certificate error (security issue)
        if (getCertificateErrorState()) {
            android.util.Log.w("Andromuks", "AppViewModel: Blocking reconnection attempt - certificate error state is active")
            logActivity("Reconnection Blocked - Certificate Error", null)
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Delegating reconnection scheduling to service")
        // Delegate to service
        WebSocketService.scheduleReconnection(reason)
    }
    
    fun isWebSocketConnected(): Boolean {
        return WebSocketService.isWebSocketConnected()
    }

    fun isInitializationComplete(): Boolean {
        return initializationComplete
    }

    fun clearWebSocket(reason: String = "Unknown", closeCode: Int? = null, closeReason: String? = null) {
        // REFACTORING: Service now owns WebSocket - no need to clear local reference
        
        // Reset initialization flag on disconnect - will be set again when init_complete arrives
        if (initializationComplete) {
            initializationComplete = false
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket cleared - resetting initializationComplete flag (reason: $reason)")
        }
        
        // CRITICAL FIX: Reset initial sync state on disconnect to prepare for reconnection
        // On reconnection, we'll receive new sync_complete messages before init_complete
        // We need to queue them again, so reset the state here
        initialSyncPhase = false
        synchronized(initialSyncCompleteQueue) {
            initialSyncCompleteQueue.clear()
        }
        initialSyncProcessingComplete = false
        // Reset sync progress counters
        pendingSyncCompleteCount = 0
        processedSyncCompleteCount = 0
        // Don't reset initialSyncComplete here - let setWebSocket handle it when reconnecting
        // This prevents UI flicker if the app is already showing the room list
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket cleared - reset initial sync state (reason: $reason, queue cleared)")
        }
        
        // PHASE 4.1: Delegate WebSocket clearing to service with close code information
        WebSocketService.clearWebSocket(reason, closeCode, closeReason)
    }
    
    /**
     * PHASE 4.1: Handle WebSocket close with code and reason
     * Determines reconnection strategy based on close code
     */
    fun handleWebSocketClose(code: Int, reason: String) {
        android.util.Log.i("Andromuks", "AppViewModel: WebSocket closed with code $code: $reason")
        logActivity("WebSocket Closed - Code $code: $reason", null)
        
        // Clear WebSocket connection
        clearWebSocket("WebSocket closed ($code)", code, reason)
        
        // PHASE 4.1: Determine reconnection strategy based on close code
        when (code) {
            1000 -> {
                // Normal closure - don't reconnect immediately, wait for ping timeout
                android.util.Log.i("Andromuks", "AppViewModel: Normal WebSocket closure (1000) - not reconnecting immediately")
                // Don't schedule reconnection - let ping/pong detect if connection is truly lost
            }
            1001 -> {
                // Going Away - server restarting, reconnect with short delay
                android.util.Log.w("Andromuks", "AppViewModel: Server going away (1001) - scheduling reconnection with delay")
                viewModelScope.launch {
                    delay(2000L) // 2 second delay for server restart
                    scheduleReconnection("Server going away (1001)")
                }
            }
            1006 -> {
                // Abnormal closure (no close frame) - connection lost, reconnect immediately
                android.util.Log.e("Andromuks", "AppViewModel: Abnormal WebSocket closure (1006) - reconnecting immediately")
                scheduleReconnection("Abnormal closure (1006)")
            }
            1012 -> {
                // Service Restart - backend restarting, reconnect with delay
                android.util.Log.w("Andromuks", "AppViewModel: Service restart (1012) - scheduling reconnection with delay")
                viewModelScope.launch {
                    delay(3000L) // 3 second delay for backend restart
                    scheduleReconnection("Service restart (1012)")
                }
            }
            in 4000..4999 -> {
                // Application-specific codes - log and reconnect
                android.util.Log.w("Andromuks", "AppViewModel: Application close code $code - reconnecting")
                scheduleReconnection("Application close code $code: $reason")
            }
            else -> {
                // Other close codes - reconnect with standard strategy
                android.util.Log.w("Andromuks", "AppViewModel: WebSocket closed with code $code - reconnecting")
                scheduleReconnection("Close code $code: $reason")
            }
        }
    }
    
    /**
     * PHASE 4.2: Handle connection failure with error-specific strategies
     * 
     * @param errorType Type of error: "DNS_FAILURE", "NETWORK_UNREACHABLE", or "GENERIC_ERROR"
     * @param error The original exception
     * @param reason Human-readable failure reason
     */
    fun handleConnectionFailure(errorType: String, error: Throwable, reason: String) {
        android.util.Log.w("Andromuks", "AppViewModel: Connection failure - Type: $errorType, Reason: $reason")
        logActivity("Connection Failure - $errorType: ${error.message}", null)
        
        // CRITICAL FIX: Check if network is available before scheduling reconnection
        // This prevents reconnection attempts when network is lost (WiFi turned off, etc.)
        val networkType = WebSocketService.getCurrentNetworkType()
        if (networkType == WebSocketService.NetworkType.NONE) {
            android.util.Log.w("Andromuks", "AppViewModel: Connection failure but no network available - not scheduling reconnection (reason: $reason)")
            logActivity("Connection Failure - No Network Available", null)
            return
        }
        
        when (errorType) {
            "DNS_FAILURE" -> {
                // DNS failures are often persistent - use exponential backoff with longer delays
                android.util.Log.w("Andromuks", "AppViewModel: DNS resolution failure detected - using exponential backoff")
                
                // Track DNS failure count for exponential backoff
                val dnsFailureCount = getDnsFailureCount()
                val nextDnsFailureCount = dnsFailureCount + 1
                setDnsFailureCount(nextDnsFailureCount)
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s, 64s (max)
                val delayMs = minOf(2000L * (1L shl (nextDnsFailureCount - 1)), 64000L)
                
                android.util.Log.i("Andromuks", "AppViewModel: DNS failure #$nextDnsFailureCount - scheduling reconnection in ${delayMs}ms")
                logActivity("DNS Failure #$nextDnsFailureCount - Retry in ${delayMs}ms", null)
                
                viewModelScope.launch {
                    delay(delayMs)
                    // CRITICAL FIX: Check network again before executing reconnection
                    // Network might have been lost during the delay
                    val currentNetworkType = WebSocketService.getCurrentNetworkType()
                    if (currentNetworkType == WebSocketService.NetworkType.NONE) {
                        android.util.Log.w("Andromuks", "AppViewModel: DNS retry delayed but network now unavailable - cancelling reconnection")
                        return@launch
                    }
                    // Reset DNS failure count on successful reconnection attempt
                    // (will be reset when connection succeeds)
                    scheduleReconnection("DNS resolution failure (attempt $nextDnsFailureCount)")
                }
            }
            "NETWORK_UNREACHABLE" -> {
                // Network unreachable - wait for network availability before retrying
                // WebSocketService's NetworkMonitor will trigger reconnection when network becomes available
                android.util.Log.w("Andromuks", "AppViewModel: Network unreachable - waiting for network availability")
                logActivity("Network Unreachable - Waiting for Network", null)
                
                // Don't retry immediately - NetworkMonitor in WebSocketService will handle reconnection
                // when network becomes available. This prevents battery drain from rapid retries.
                // We still schedule a delayed reconnection as a fallback (in case NetworkMonitor misses the event)
                viewModelScope.launch {
                    // Wait 10 seconds - if network is still unavailable, NetworkMonitor will handle it
                    delay(10000L)
                    // CRITICAL FIX: Check network again before executing reconnection
                    val currentNetworkType = WebSocketService.getCurrentNetworkType()
                    if (currentNetworkType == WebSocketService.NetworkType.NONE) {
                        android.util.Log.w("Andromuks", "AppViewModel: Network still unavailable after 10s - not scheduling fallback reconnection")
                        return@launch
                    }
                    // Only schedule if still disconnected (NetworkMonitor may have already reconnected)
                    if (!isWebSocketConnected()) {
                        android.util.Log.i("Andromuks", "AppViewModel: Network available after 10s - scheduling fallback reconnection")
                        scheduleReconnection("Network unreachable (fallback retry)")
                    }
                }
            }
            else -> {
                // Generic error - use standard reconnection strategy
                android.util.Log.w("Andromuks", "AppViewModel: Generic connection error - using standard reconnection")
                scheduleReconnection(reason)
            }
        }
    }
    
    // PHASE 4.2: DNS failure tracking (stored in SharedPreferences)
    private fun getDnsFailureCount(): Int {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getInt("dns_failure_count", 0) ?: 0
    }
    
    private fun setDnsFailureCount(count: Int) {
        appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("dns_failure_count", count)
                .apply()
        }
    }
    
    /**
     * PHASE 4.2: Reset DNS failure count when connection succeeds
     * Should be called when WebSocket connection is successfully established
     */
    private fun resetDnsFailureCount() {
        val currentCount = getDnsFailureCount()
        if (currentCount > 0) {
            android.util.Log.i("Andromuks", "AppViewModel: Resetting DNS failure count (was: $currentCount)")
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("dns_failure_count", 0)
                    .apply()
            }
        }
    }
    
    /**
     * PHASE 4.3: Handle TLS/SSL errors with appropriate strategies
     * 
     * @param errorType Type of TLS error: "CERTIFICATE_ERROR" or "TLS_ERROR"
     * @param error The original exception
     * @param reason Human-readable failure reason
     */
    fun handleTlsError(errorType: String, error: Throwable, reason: String) {
        android.util.Log.e("Andromuks", "AppViewModel: TLS/SSL error - Type: $errorType, Reason: $reason")
        
        when (errorType) {
            "CERTIFICATE_ERROR" -> {
                // Certificate errors are security issues - don't reconnect automatically
                android.util.Log.e("Andromuks", "AppViewModel: Certificate validation error detected - NOT reconnecting automatically (security issue)")
                logActivity("Certificate Error - Connection Blocked", null)
                
                // Store certificate error state to prevent automatic reconnection
                setCertificateErrorState(true, reason)
                
                // Log error details (but not full certificate for security)
                val errorDetails = when {
                    error is java.security.cert.CertificateException -> error.message ?: "Certificate validation failed"
                    error.cause is java.security.cert.CertificateException -> (error.cause as java.security.cert.CertificateException).message ?: "Certificate validation failed"
                    else -> "Certificate error: ${error.message}"
                }
                android.util.Log.e("Andromuks", "AppViewModel: Certificate error details: $errorDetails")
                
                // TODO: In the future, we could show a user notification here
                // For now, we just prevent reconnection and log the error
                // The user will see "Disconnected" state and can manually retry
            }
            "TLS_ERROR" -> {
                // Other TLS errors (handshake failures, protocol errors) - reconnect with exponential backoff
                android.util.Log.w("Andromuks", "AppViewModel: TLS error detected (non-certificate) - reconnecting with exponential backoff")
                logActivity("TLS Error - Reconnecting", null)
                
                // Track TLS failure count for exponential backoff
                val tlsFailureCount = getTlsFailureCount()
                val nextTlsFailureCount = tlsFailureCount + 1
                setTlsFailureCount(nextTlsFailureCount)
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s, 64s (max)
                val delayMs = minOf(2000L * (1L shl (nextTlsFailureCount - 1)), 64000L)
                
                android.util.Log.i("Andromuks", "AppViewModel: TLS failure #$nextTlsFailureCount - scheduling reconnection in ${delayMs}ms")
                logActivity("TLS Error #$nextTlsFailureCount - Retry in ${delayMs}ms", null)
                
                viewModelScope.launch {
                    delay(delayMs)
                    // Reset TLS failure count on successful reconnection attempt
                    scheduleReconnection("TLS error (attempt $nextTlsFailureCount)")
                }
            }
            else -> {
                android.util.Log.w("Andromuks", "AppViewModel: Unknown TLS error type: $errorType - using standard reconnection")
                scheduleReconnection(reason)
            }
        }
    }
    
    // PHASE 4.3: Certificate error state tracking (stored in SharedPreferences)
    private fun setCertificateErrorState(hasError: Boolean, reason: String?) {
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("certificate_error_state", hasError)
                .putString("certificate_error_reason", reason)
                .putLong("certificate_error_timestamp", if (hasError) System.currentTimeMillis() else 0L)
                .apply()
        }
    }
    
    private fun getCertificateErrorState(): Boolean {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getBoolean("certificate_error_state", false) ?: false
    }
    
    // PHASE 4.3: TLS failure tracking (stored in SharedPreferences)
    private fun getTlsFailureCount(): Int {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getInt("tls_failure_count", 0) ?: 0
    }
    
    private fun setTlsFailureCount(count: Int) {
        appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("tls_failure_count", count)
                .apply()
        }
    }
    
    /**
     * PHASE 4.3: Reset TLS failure count when connection succeeds
     * Should be called when WebSocket connection is successfully established
     */
    private fun resetTlsFailureCount() {
        val currentCount = getTlsFailureCount()
        if (currentCount > 0) {
            android.util.Log.i("Andromuks", "AppViewModel: Resetting TLS failure count (was: $currentCount)")
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("tls_failure_count", 0)
                    .apply()
            }
        }
    }
    
    /**
     * PHASE 5.1: Add operation to queue with size limits and persistence
     */
    private fun addPendingOperation(operation: PendingWebSocketOperation, saveToStorage: Boolean = false): Boolean {
        // PHASE 5.1: Enforce queue size limit (remove oldest if at limit)
        synchronized(pendingOperationsLock) {
        if (pendingWebSocketOperations.size >= MAX_QUEUE_SIZE) {
                // Remove oldest operation (by timestamp)
                val oldest = pendingWebSocketOperations.minByOrNull { it.timestamp }
            if (oldest != null) {
                pendingWebSocketOperations.remove(oldest)
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Queue full (${MAX_QUEUE_SIZE}), removed oldest operation: ${oldest.type}"
                    )
            }
        }
        
        pendingWebSocketOperations.add(operation)
        }
        // CRITICAL FIX: Only save to storage when explicitly needed (disconnected commands, retries)
        // When WebSocket is connected and command is sent successfully, we only need in-memory tracking
        // Storage is only needed for persistence across app restarts or when disconnected
        // This prevents unnecessary storage writes on every command when WebSocket is connected
        if (saveToStorage) {
            savePendingOperationsToStorage()
        }
        return true
    }
    
    /**
     * PHASE 5.1: Save pending WebSocket operations to SharedPreferences
     * Called whenever operations are added or modified
     */
    private fun savePendingOperationsToStorage() {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                val operationsArray = JSONArray()
                
                // CRASH FIX: Create a snapshot to avoid ConcurrentModificationException
                // Multiple threads may modify pendingWebSocketOperations while we're iterating
                val operationsSnapshot = synchronized(pendingOperationsLock) {
                    pendingWebSocketOperations.toList() // Create immutable copy
                }
                
                // Convert operations to JSON
                operationsSnapshot.forEach { operation ->
                    val operationJson = JSONObject(operation.toJsonMap())
                    operationsArray.put(operationJson)
                }
                
                prefs.edit()
                    .putString("pending_websocket_operations", operationsArray.toString())
                    .apply()
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved ${operationsSnapshot.size} pending WebSocket operations to storage")
            } catch (e: ConcurrentModificationException) {
                // Retry once with a fresh snapshot if we still get CME
                android.util.Log.w("Andromuks", "AppViewModel: ConcurrentModificationException in savePendingOperationsToStorage, retrying", e)
                try {
                    val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    val operationsArray = JSONArray()
                    val operationsSnapshot = synchronized(pendingOperationsLock) {
                        pendingWebSocketOperations.toList()
                    }
                    operationsSnapshot.forEach { operation ->
                        val operationJson = JSONObject(operation.toJsonMap())
                        operationsArray.put(operationJson)
                    }
                    prefs.edit()
                        .putString("pending_websocket_operations", operationsArray.toString())
                        .apply()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved ${operationsSnapshot.size} pending WebSocket operations to storage (retry succeeded)")
                } catch (e2: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to save pending WebSocket operations to storage (retry also failed)", e2)
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to save pending WebSocket operations to storage", e)
            }
        }
    }
    
    /**
     * PHASE 5.1: Load pending WebSocket operations from SharedPreferences
     * Called on app startup
     */
    private fun loadPendingOperationsFromStorage() {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                val operationsJson = prefs.getString("pending_websocket_operations", null)
                
                if (operationsJson == null || operationsJson.isEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending WebSocket operations found in storage")
                    return
                }
                
                val operationsArray = JSONArray(operationsJson)
                val loadedOperations = mutableListOf<PendingWebSocketOperation>()
                val currentTime = System.currentTimeMillis()
                
                // Parse operations and filter out old ones (>24 hours)
                for (i in 0 until operationsArray.length()) {
                    val operationJson = operationsArray.getJSONObject(i)
                    val operationMap = mutableMapOf<String, Any>()
                    
                    // Convert JSONObject to Map
                    val keys = operationJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        operationMap[key] = operationJson.get(key)
                    }
                    
                    val operation = PendingWebSocketOperation.fromJsonMap(operationMap)
                    if (operation != null) {
                        // PHASE 5.1: Remove old messages (>24 hours)
                        val age = currentTime - operation.timestamp
                        if (age <= MAX_MESSAGE_AGE_MS) {
                            loadedOperations.add(operation)
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed old pending operation (age: ${age / 1000 / 60} minutes): ${operation.type}")
                        }
                    }
                }
                
                // PHASE 5.1: Limit queue size (keep most recent)
                val operationsToLoad = if (loadedOperations.size > MAX_QUEUE_SIZE) {
                    loadedOperations.sortedByDescending { it.timestamp }.take(MAX_QUEUE_SIZE)
                } else {
                    loadedOperations
                }
                
                synchronized(pendingOperationsLock) {
                pendingWebSocketOperations.clear()
                pendingWebSocketOperations.addAll(operationsToLoad)
                }
                
                if (operationsToLoad.isNotEmpty()) {
                    android.util.Log.i("Andromuks", "AppViewModel: Loaded ${operationsToLoad.size} pending WebSocket operations from storage (removed ${loadedOperations.size - operationsToLoad.size} old/over-limit)")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No valid pending WebSocket operations to load")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to load pending WebSocket operations from storage", e)
            }
        }
    }
    
    /**
     * Retry pending unacknowledged operations after reconnection with stabilization delay
     * Webmuks handles out-of-order messages, so we can retry in background without blocking new commands
     */
    private fun flushPendingQueueAfterReconnection() {
        val pendingSize = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
        if (pendingSize == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending operations to retry after reconnection")
            return
        }
        
        val unacknowledgedCount = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.count { !it.acknowledged }
        }
        if (unacknowledgedCount == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: All pending operations already acknowledged, no retry needed")
            return
        }
        
        android.util.Log.i("Andromuks", "AppViewModel: Scheduling retry of $unacknowledgedCount unacknowledged operations after stabilization delay")
        lastReconnectionTime = System.currentTimeMillis()
        
        viewModelScope.launch {
            // Wait 2 seconds after init_complete for backend to stabilize before retrying
            // This prevents overwhelming the backend immediately after reconnection
            delay(2000L)
            
            android.util.Log.i("Andromuks", "AppViewModel: Starting retry of pending operations (new commands can be sent immediately)")
            retryPendingWebSocketOperations()
            
            // Wait a bit more to ensure all retries are processed
            delay(500L)
            
            val remaining = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
            android.util.Log.i("Andromuks", "AppViewModel: Retry complete, $remaining operations remain in queue")
        }
    }
    
    /**
     * PHASE 5.4: Retry pending WebSocket operations with smart queue management
     * Only retries unacknowledged messages, respects timeouts, orders by timestamp, limits batch size
     * QUEUE FLUSHING FIX: Now respects stabilization period after reconnection
     */
    private fun retryPendingWebSocketOperations() {
        val pendingSize = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
        if (pendingSize == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending WebSocket operations to retry")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        // PHASE 5.4: Filter operations to retry:
        // 1. Only unacknowledged messages
        // 2. Only if acknowledgmentTimeout has passed
        // 3. Sort by timestamp (oldest first)
        val operationsToRetry = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations
            .filter { !it.acknowledged && currentTime >= it.acknowledgmentTimeout }
            .sortedBy { it.timestamp }
            .take(10) // PHASE 5.4: Limit batch size to 10 at a time
        }
        
        if (operationsToRetry.isEmpty()) {
            val (acknowledgedCount, waitingCount, total) = synchronized(pendingOperationsLock) {
                val ack = pendingWebSocketOperations.count { it.acknowledged }
                val waiting = pendingWebSocketOperations.count { currentTime < it.acknowledgmentTimeout }
                Triple(ack, waiting, pendingWebSocketOperations.size)
            }
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: No operations ready to retry (acknowledged: $acknowledgedCount, waiting for timeout: $waitingCount, total: $total)"
            )
            return
        }
        
        android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.4 - Retrying ${operationsToRetry.size} pending WebSocket operations (oldest first, batch limited to 10)")
        
        // Remove operations from queue before retrying (will be re-added if they fail)
        synchronized(pendingOperationsLock) {
        operationsToRetry.forEach { pendingWebSocketOperations.remove(it) }
        }
        savePendingOperationsToStorage()
        
        // PHASE 5.4: Retry operations with delay between retries to avoid rate limiting
        viewModelScope.launch {
            operationsToRetry.forEachIndexed { index, operation ->
                try {
                    // PHASE 5.4: Add delay between retries (100ms) to avoid rate limiting
                    if (index > 0) {
                        delay(100L)
                    }
                    
                    when (operation.type) {
                        "sendMessage" -> {
                            val roomId = operation.data["roomId"] as String?
                            val text = operation.data["text"] as String?
                            if (roomId != null && text != null) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrying sendMessage for room $roomId (attempt ${operation.retryCount + 1})")
                                val result = sendMessageInternal(roomId, text)
                                if (result != WebSocketResult.SUCCESS && operation.retryCount < maxRetryAttempts) {
                                    // Re-queue if still failing
                                    addPendingOperation(operation.copy(retryCount = operation.retryCount + 1), saveToStorage = true)
                                }
                            }
                        }
                        "sendReply" -> {
                            // Note: SendReply operations would need the originalEvent, which is complex to serialize
                            // For now, we'll skip retrying sendReply operations as they're less critical
                            android.util.Log.w("Andromuks", "AppViewModel: Skipping retry of sendReply operation (complex to serialize)")
                        }
                        "markRoomAsRead" -> {
                            val roomId = operation.data["roomId"] as String?
                            val eventId = operation.data["eventId"] as String?
                            if (roomId != null && eventId != null) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrying markRoomAsRead for room $roomId (attempt ${operation.retryCount + 1})")
                                markRoomAsReadInternal(roomId, eventId)
                            }
                        }
                        else -> {
                            // PHASE 5.4: Handle generic commands and offline retry commands
                            if (operation.type.startsWith("offline_")) {
                                val command = operation.data["command"] as String?
                                val requestId = operation.data["requestId"] as Int?
                                @Suppress("UNCHECKED_CAST")
                                val data = operation.data["data"] as? Map<String, Any>
                                
                                if (command != null && requestId != null && data != null) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Retrying offline command: $command (attempt ${operation.retryCount + 1})")
                                    sendWebSocketCommand(command, requestId, data)
                                }
                            } else if (operation.type.startsWith("command_")) {
                                // PHASE 5.4: Retry generic commands
                                val command = operation.type.removePrefix("command_")
                                val requestId = operation.data["requestId"] as? Int
                                @Suppress("UNCHECKED_CAST")
                                val data = operation.data["data"] as? Map<String, Any>
                                
                                if (requestId != null && data != null) {
                                    // SAFETY GUARD: prevent duplicate mark_read storms when acknowledgments never arrive
                                    if (command == "mark_read") {
                                        val roomId = data["room_id"] as? String
                                        val eventId = data["event_id"] as? String
                                        if (roomId != null && eventId != null) {
                                            val lastSent = lastMarkReadSent[roomId]
                                            if (lastSent == eventId) {
                                                android.util.Log.w(
                                                    "Andromuks",
                                                    "AppViewModel: Skipping retry for duplicate mark_read for room $roomId event $eventId"
                                                )
                                                return@forEachIndexed
                                            }
                                            lastMarkReadSent[roomId] = eventId
                                        }
                                    }
                                    // Generate new request_id for retry (can't reuse old one)
                                    val newRequestId = requestIdCounter++
                                    android.util.Log.w("Andromuks", "AppViewModel: Retrying command '$command' with new request_id: $newRequestId (was: $requestId, attempt ${operation.retryCount + 1})")
                                    sendWebSocketCommand(command, newRequestId, data)
                                }
                            } else {
                                android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error retrying operation ${operation.type}: ${e.message}")
                    if (operation.retryCount < maxRetryAttempts) {
                        addPendingOperation(operation.copy(retryCount = operation.retryCount + 1), saveToStorage = true)
                    }
                }
            }
            
            // PHASE 5.1: Save queue after retry operations complete
            savePendingOperationsToStorage()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.4 - Finished retrying ${operationsToRetry.size} operations, ${pendingWebSocketOperations.size} remain queued")
        }
    }
    

    fun noteIncomingRequestId(requestId: Int) {
        if (requestId != 0) {
            // Track ALL incoming request_ids for general purposes (pong detection, etc.)
            lastReceivedRequestId = requestId
            // NOTE: We no longer track negative request_ids for reconnection - all caches are cleared on connect/reconnect
            // Pong handling is now done directly in NetworkUtils
        }
    }
    
    /**
     * Stores the run_id received from the gomuks backend.
     * RUSH TO HEALTHY: Store run_id in SharedPreferences immediately - always use same run_id for device
     * vapid_key is not used (we use FCM)
     */
    fun handleRunId(runId: String, vapidKey: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleRunId called with runId='$runId'")
        
        // Store run_id in SharedPreferences (persistent storage)
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val existingRunId = prefs.getString("ws_run_id", "") ?: ""
                
                if (existingRunId.isEmpty()) {
                    // First time - store run_id permanently
                    prefs.edit().putString("ws_run_id", runId).apply()
                    android.util.Log.i("Andromuks", "AppViewModel: Stored run_id in SharedPreferences: $runId")
                } else if (existingRunId != runId) {
                    // Run ID changed - update it (shouldn't happen, but handle it)
                    android.util.Log.w("Andromuks", "AppViewModel: Run ID changed from '$existingRunId' to '$runId' - updating")
                    prefs.edit().putString("ws_run_id", runId).apply()
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Run ID matches existing value: $runId")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to store run_id in SharedPreferences", e)
            }
        }
        
        // Update in-memory cache for backward compatibility
        currentRunId = runId
        this.vapidKey = vapidKey // Keep for backward compatibility, but not used
        
        // NOTE: We no longer update last_received_sync_id - all caches are cleared on connect/reconnect
        
        // CRITICAL FIX: Process deferred emoji pack requests when WebSocket is ready
        // This ensures custom emojis are loaded even if account_data was processed before WebSocket connected
        if (deferredEmojiPackRequests.isNotEmpty() && isWebSocketConnected()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${deferredEmojiPackRequests.size} deferred emoji pack requests")
            val requests = deferredEmojiPackRequests.toList()
            deferredEmojiPackRequests.clear()
            for ((roomId, packName) in requests) {
                requestEmojiPackData(roomId, packName)
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Run ID stored and service updated")
    }
    
    /**
     * Gets the current run_id for reconnection
     * RUSH TO HEALTHY: Always read from SharedPreferences (single source of truth)
     */
    fun getCurrentRunId(): String {
        return appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val runId = prefs.getString("ws_run_id", "") ?: ""
                // Update in-memory cache for backward compatibility
                if (runId.isNotEmpty()) {
                    currentRunId = runId
                }
                runId
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to read run_id from SharedPreferences", e)
                currentRunId // Fallback to in-memory cache
            }
        } ?: currentRunId // Fallback to in-memory cache if context is null
    }
    
    /**
     * Gets the current VAPID key for push notifications
     */
    fun getVapidKey(): String = vapidKey
    
    /**
     * Gets the current request ID counter (next ID to be used)
     */
    fun getCurrentRequestId(): Int = requestIdCounter
    
    /**
     * Gets the last received request ID from the server
     */
    fun getLastReceivedRequestId(): Int = lastReceivedRequestId
    
    /**
     * Check if an event is pinned in the current room
     */
    
    /**
     * Saves the current WebSocket state and room data to persistent storage.
     * This allows the app to resume quickly on restart by loading cached data
     * and reconnecting with run_id and last_received_id.
     * 
     * Note: With foreground service maintaining connection, this primarily serves as
     * crash recovery - preserving run_id and sync state for seamless resumption if
     * the app process is killed by the system.
     */
    fun saveStateToStorage(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Save WebSocket connection state
            editor.putString("ws_run_id", currentRunId)
            editor.putString("ws_vapid_key", vapidKey)
            
            // Save room data as JSON
            val roomsArray = JSONArray()
            for (room in allRooms) {
                val roomJson = JSONObject()
                roomJson.put("id", room.id)
                roomJson.put("name", room.name)
                roomJson.put("avatarUrl", room.avatarUrl ?: "")
                roomJson.put("messagePreview", room.messagePreview ?: "")
                roomJson.put("messageSender", room.messageSender ?: "")
                roomJson.put("sortingTimestamp", room.sortingTimestamp ?: 0L)
                roomJson.put("unreadCount", room.unreadCount ?: 0)
                roomJson.put("highlightCount", room.highlightCount ?: 0)
                roomJson.put("isDirectMessage", room.isDirectMessage)
                roomsArray.put(roomJson)
            }
            editor.putString("cached_rooms", roomsArray.toString())
            
            // Save timestamp of when state was saved
            editor.putLong("state_saved_timestamp", System.currentTimeMillis())
            
            editor.apply()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved state to storage - run_id: $currentRunId, rooms: ${allRooms.size}")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to save state to storage", e)
        }
    }
    /**
     * Loads the previously saved WebSocket state and room data from persistent storage.
     * Returns true if cached data was loaded, false otherwise.
     */
    /**
     * Load minimal state from SharedPreferences (run_id and vapid_key only).
     * All room/space/account data comes from sync_complete on reconnect.
     */
    fun loadStateFromStorage(context: android.content.Context): Boolean {
        try {
            // Load activity log from storage first
            loadActivityLogFromStorage(context)
            
            // Load only run_id and vapid_key from SharedPreferences
            // All room/space/account data comes from sync_complete on reconnect (clear_state: true)
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val runId = prefs.getString("ws_run_id", "") ?: ""
            val savedVapidKey = prefs.getString("ws_vapid_key", "") ?: ""
            
            if (runId.isNotEmpty()) {
                currentRunId = runId
                vapidKey = savedVapidKey
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored WebSocket state from SharedPreferences - run_id: $runId")
                return true
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No run_id found in SharedPreferences")
            return false
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load state from storage", e)
            return false
        }
    }
    
    /**
     * Handles 401 Unauthorized error by clearing all credentials and triggering login navigation.
     * This should be called when WebSocket connection fails with 401 (invalid/expired token).
     */
    fun handleUnauthorizedError() {
        android.util.Log.e("Andromuks", "AppViewModel: Handling 401 Unauthorized error - clearing credentials and navigating to login")
        logActivity("401 Unauthorized - Clearing Credentials", null)
        
        val context = appContext ?: run {
            android.util.Log.e("Andromuks", "AppViewModel: Cannot handle 401 error - appContext is null")
            return
        }
        
        try {
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Clear auth token (this will cause AuthCheck to navigate to login)
            editor.remove("gomuks_auth_token")
            editor.remove("homeserver_url")
            
            // Clear run_id and related WebSocket state
            editor.remove("ws_run_id")
            editor.remove("ws_vapid_key")
            editor.remove("cached_rooms")
            editor.remove("state_saved_timestamp")
            
            // CRITICAL FIX: Use commit() instead of apply() to ensure credentials are cleared synchronously
            // This prevents credential loss if the app crashes/terminates right after 401 error
            // commit() blocks until the write completes, ensuring persistence even if app is killed
            val commitSuccess = editor.commit()
            if (!commitSuccess) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to commit credential clearing - retrying with apply()")
                // Fallback to apply() if commit() fails (shouldn't happen, but be defensive)
                editor.apply()
            }
            
            // Clear in-memory state
            currentRunId = ""
            vapidKey = ""
            navigationCallbackTriggered = false
            
            // Clear WebSocket connection
            clearWebSocket("401 Unauthorized - credentials cleared")
            WebSocketService.clearWebSocket("401 Unauthorized - credentials cleared")
            WebSocketService.clearReconnectionState()
            
            // Stop WebSocket service
            WebSocketService.stopService()
            
            android.util.Log.i("Andromuks", "AppViewModel: Credentials cleared due to 401 Unauthorized - app will navigate to login on next AuthCheck (commit success: $commitSuccess)")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to clear credentials on 401 error", e)
            logActivity("401 Error Handling Failed - ${e.message}", null)
        }
    }

    
    private fun restartWebSocket(reason: String = "Unknown reason") {
        // INFINITE LOOP FIX: Prevent rapid-fire restarts
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRestart = currentTime - lastRestartTime
        
        if (isRestarting) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket already in progress - ignoring duplicate call (reason: $reason)")
            return
        }
        
        if (timeSinceLastRestart < RESTART_COOLDOWN_MS) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket called too soon (${timeSinceLastRestart}ms ago, cooldown: ${RESTART_COOLDOWN_MS}ms) - ignoring (reason: $reason)")
            return
        }
        
        isRestarting = true
        lastRestartTime = currentTime
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: restartWebSocket invoked - reason: $reason")
        logActivity("Restarting WebSocket - $reason", null)
        
        // Only show toast for important reconnection reasons, not every attempt
        val shouldShowToast = when {
            reason.contains("Manual reconnection") -> true
            reason.contains("Full refresh") -> true
            reason.contains("attempt #1") && reason.contains("Network type changed") -> true // Only first network change attempt
            else -> false // Hide "Network restored" and other spam
        }

        // Only show toasts in debug builds to avoid UX disruption in production
        if (shouldShowToast && BuildConfig.DEBUG) {
            appContext?.let { context ->
                viewModelScope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "WS: $reason",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val restartCallback = onRestartWebSocket

        if (restartCallback != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Using direct reconnect callback for reason: $reason")
            // Cancel any pending reconnection jobs in the service to avoid duplicate attempts
            WebSocketService.cancelReconnection()
            // Ensure the service state is reset before establishing a new connection
            WebSocketService.clearWebSocket(reason)
            
            // INFINITE LOOP FIX: Clear restart flag after a delay to allow connection to complete
            viewModelScope.launch {
                try {
                    restartCallback.invoke(reason)
                    // Wait a bit before clearing the flag to prevent immediate re-triggers
                    delay(2000L)
                } finally {
                    isRestarting = false
                }
            }
            return
        }

        // PHASE 1.4 FIX: When called from reconnection callback (e.g., "Service restarted - reconnecting"),
        // we should actually connect to WebSocket, not call WebSocketService.restartWebSocket() which would create a loop
        // Check if we're being called from the service's reconnection callback
        if (reason.contains("Service restarted")) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket called from service callback - connecting WebSocket directly to avoid loop")
            // Clear the WebSocket first
            WebSocketService.clearWebSocket(reason)
            // Then trigger actual connection via the normal flow
            // This should be handled by the code that normally connects (e.g., AuthCheckScreen)
            // For now, just log and return - the service restart check will handle it properly
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Service restart reconnection - connection should be handled by app startup flow")
            isRestarting = false
            return
        }
        
        // INFINITE LOOP FIX: Don't call WebSocketService.restartWebSocket() from reconnection callback
        // This creates an infinite loop because it calls the callback again
        // Instead, we should directly trigger connection via initializeWebSocketConnection()
        android.util.Log.w("Andromuks", "AppViewModel: onRestartWebSocket callback not set - cannot restart (would create loop)")
        android.util.Log.w("Andromuks", "AppViewModel: Connection should be handled by initializeWebSocketConnection() or app startup flow")
        
        // Clear restart flag after a delay
        viewModelScope.launch {
            delay(2000L)
            isRestarting = false
        }
    }

    fun requestUserProfile(userId: String, roomId: String? = null) {
        // UNIFIED PENDING CHECK: Check both global and room-specifc pending requests
        val requestKey = if (roomId != null) "$roomId:$userId" else userId
        val globalRequestKey = userId
        
        // PERFORMANCE: Prevent duplicate profile requests for the same user
        if (pendingProfileRequests.contains(globalRequestKey) || (roomId != null && pendingProfileRequests.contains(requestKey))) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId (global or room-specific), skipping duplicate")
            return
        }
        
        // Check if already in cache (quick check) to avoid unnecessary requests
        // Use getUserProfile which correctly prioritizes room-specific profiles
        val profile = getUserProfile(userId, roomId)
        if (profile != null && !profile.displayName.isNullOrBlank()) {
            // Profile already exists and has a valid display name - skip request
            return
        }
        
        // CRITICAL FIX: Don't request profile if WebSocket is not ready
        // sendWebSocketCommand will queue it, but we should wait for WebSocket to be ready
        // This prevents race conditions where profile is requested before init_complete
        if (!canSendCommandsToBackend) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Profile request for $userId deferred - WebSocket not ready (will be queued)")
            }
            // sendWebSocketCommand will queue the command, so it's safe to continue
            // The command will be sent when WebSocket is ready
        }
        
        val reqId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(userId)
        profileRequests[reqId] = userId
        if (roomId != null) {
            profileRequestRooms[reqId] = roomId
        }
        
        // REFACTORING: Use WebSocketService.sendCommand() instead of direct ws.send()
        // This will queue the command if WebSocket is not ready
        sendWebSocketCommand("get_profile", reqId, mapOf("user_id" to userId))
    }
    
    /**
     * Validates and requests missing user profiles for a room.
     * 
     * This function checks all timeline events and identifies users with missing
     * display names or avatars. It then requests their profiles from the server
     * to ensure complete user information is available for the UI.
     * 
     * @param roomId The ID of the room to validate profiles for
     * @param timelineEvents List of timeline events to check for missing user data
     */
    fun validateAndRequestMissingProfiles(roomId: String, timelineEvents: List<TimelineEvent>) {
        val usersToRequest = mutableSetOf<String>()
        
        // Check each timeline event for missing user profile data
        for (event in timelineEvents) {
            val sender = event.sender
            
            // Use getUserProfile to check all caches (flattened, legacy room cache, global cache)
            val profile = getUserProfile(sender, roomId)
            
            // Check if we have incomplete profile data
            val hasDisplayName = !profile?.displayName.isNullOrBlank()
            val hasAvatar = !profile?.avatarUrl.isNullOrBlank()
            
            if (!hasDisplayName || !hasAvatar) {
                // UNIFIED PENDING CHECK: Check both global and room-specifc pending requests
                val isPending = pendingProfileRequests.contains(sender) || pendingProfileRequests.contains("$roomId:$sender")
                
                if (!isPending) {
                    usersToRequest.add(sender)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Missing profile data for $sender - displayName: $hasDisplayName, avatar: $hasAvatar")
                }
            }
        }
        
        // Request profiles for users with missing data
        for (userId in usersToRequest) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting missing profile for $userId")
            requestUserProfile(userId, roomId)
        }
    }
    
    // Track outgoing requests for timeline processing
    fun trackOutgoingRequest(requestId: Int, roomId: String) {
        outgoingRequests[requestId] = roomId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracking outgoing request $requestId for room $roomId")
    }
    
    // Send a message and track the response
    fun sendMessage(roomId: String, text: String, mentions: List<String> = emptyList()) {
        val reqId = requestIdCounter++
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId=$roomId, text='$text', reqId=$reqId")
        
        // Track this outgoing request
        trackOutgoingRequest(reqId, roomId)
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct ws.send()
        val mentionsData = mapOf(
            "user_ids" to mentions,
            "room" to false
        )
        sendWebSocketCommand("send_message", reqId, mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mentionsData,
            "url_previews" to emptyList<Any>()
        ))

    }
    
   
    /**
     * Helper function to process cached events and display them
     */
    private fun processCachedEvents(
        roomId: String,
        cachedEvents: List<TimelineEvent>,
        openingFromNotification: Boolean,
        skipNetworkRequests: Boolean = false
    ) {
        val ownMessagesInCache = cachedEvents.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
        val cacheType = if (openingFromNotification && cachedEvents.size < 100) "notification-optimized" else "standard"
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✓ CACHE HIT ($cacheType) - Instant room opening: ${cachedEvents.size} events (including $ownMessagesInCache of your own messages)")
        if (ownMessagesInCache > 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ★ Cache contains $ownMessagesInCache messages from YOU")
        }
        if (BuildConfig.DEBUG) {
            val firstCached = cachedEvents.firstOrNull()
            val lastCached = cachedEvents.lastOrNull()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Cached snapshot for $roomId -> first=${firstCached?.eventId}@${firstCached?.timestamp}, " +
                    "last=${lastCached?.eventId}@${lastCached?.timestamp}"
            )
        }
        
        // Ensure loading is false BEFORE processing to prevent loading flash
        isTimelineLoading = false
        
        // Clear and rebuild internal structures (but don't clear timelineEvents yet)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing eventChainMap (had ${eventChainMap.size} entries) before processing ${cachedEvents.size} cached events")
        eventChainMap.clear()
        editEventsMap.clear()
        MessageVersionsCache.clear()
        // editToOriginal is computed from messageVersions, no need to clear separately
        // redactionCache is computed from messageVersions, no need to clear separately
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        roomsWithLoadedReceipts.remove(roomId)
        roomsWithLoadedReactions.remove(roomId)
        
        // Reset pagination state
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        // Ensure member cache exists for this room (singleton cache handles this automatically)
        
        // Populate edit chain mapping from cached events
        // Process synchronously to ensure all events are added before building timeline
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${cachedEvents.size} cached events into eventChainMap")
        var regularEventCount = 0
        var editEventCount = 0
        
        for (event in cachedEvents) {
            val isEditEvent = isEditEvent(event)
            
            if (isEditEvent) {
                editEventsMap[event.eventId] = event
                editEventCount++
            } else {
                eventChainMap[event.eventId] = EventChainEntry(
                    eventId = event.eventId,
                    ourBubble = event,
                    replacedBy = null,
                    originalTimestamp = event.timestamp
                )
                regularEventCount++
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added ${regularEventCount} regular events and ${editEventCount} edit events to maps")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: eventChainMap now has ${eventChainMap.size} entries (expected ${cachedEvents.size - editEventCount} regular events)")
        
        // SAFETY: Remove any edit events that might have been incorrectly added to eventChainMap
        // (This shouldn't happen with the fix, but clean up any existing bad state)
        val editEventIds = editEventsMap.keys.toSet()
        val editEventsInChain = eventChainMap.keys.filter { editEventIds.contains(it) }
        if (editEventsInChain.isNotEmpty()) {
            android.util.Log.w("Andromuks", "AppViewModel: Removing ${editEventsInChain.size} edit events incorrectly added to eventChainMap")
            editEventsInChain.forEach { eventChainMap.remove(it) }
        }
        
        // DIAGNOSTIC: Verify all events were added
        if (eventChainMap.size != regularEventCount) {
            android.util.Log.w("Andromuks", "AppViewModel: ⚠️ MISMATCH - eventChainMap has ${eventChainMap.size} entries but we added $regularEventCount regular events!")
        }
        
        // Process cached events to establish edit relationships
        // All events (including edit events) are already in the cache - no need to load from DB
        // Edit events from both paginate and sync_complete are in the cache
        val editEventsInCache = cachedEvents.filter { isEditEvent(it) }
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Processing cached events - ${cachedEvents.size} total, ${editEventsInCache.size} edit events in cache")
            
            // Debug: Log edit events in cache to verify they're being identified
            if (editEventsInCache.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Edit events in cache: ${editEventsInCache.map { "${it.eventId} -> ${it.content?.optJSONObject("m.relates_to")?.optString("event_id") ?: it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")}" }.joinToString(", ")}")
            }
        }
        
        // Process ALL cached events together to establish version relationships
        // This ensures edit history is available when the timeline is built
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Processing ${cachedEvents.size} cached events to establish version relationships")
        }
        processVersionedMessages(cachedEvents)
        
        // DEBUG: Verify version relationships were established
        if (BuildConfig.DEBUG) {
            val eventsWithEdits = cachedEvents.filter { !isEditEvent(it) && messageVersions[it.eventId]?.versions?.size ?: 0 > 1 }
            if (eventsWithEdits.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: ✅ Established edit relationships for ${eventsWithEdits.size} messages after processing cached events")
            } else if (editEventsInCache.isNotEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: ⚠️ Found ${editEventsInCache.size} edit events in cache but no version relationships established - this indicates a problem")
            }
        }
        
        // Process edit relationships
        processEditRelationships()
        
        // Build timeline from chain (this updates timelineEvents)
        // CRITICAL: This must be called AFTER all events are added to eventChainMap
        val eventChainSizeBeforeBuild = eventChainMap.size
        buildTimelineFromChain()
        
        // Persist a render-ready projection so UI can avoid re-walking chains on room open.
        // This runs asynchronously and does not block the UI.
        persistRenderableEvents(roomId, timelineEvents)

        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Built timeline with ${timelineEvents.size} events from ${cachedEvents.size} cached events " +
            "(eventChainMap had $eventChainSizeBeforeBuild entries before build)"
        )
        
        // DIAGNOSTIC: Check for significant event loss
        val expectedMinEvents = cachedEvents.size - editEventCount // All regular events should be in timeline
        if (timelineEvents.size < expectedMinEvents * 0.9) { // Allow 10% margin for filtering
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: ⚠️ SIGNIFICANT EVENT LOSS - Timeline has ${timelineEvents.size} events " +
                "but expected at least ${expectedMinEvents} (from ${cachedEvents.size} cached events, " +
                "$editEventCount edits filtered)"
            )
        }
        val latestTimelineEvent = timelineEvents.lastOrNull()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Timeline latest event=${latestTimelineEvent?.eventId} timelineRowId=${latestTimelineEvent?.timelineRowid} ts=${latestTimelineEvent?.timestamp}")
        if (latestTimelineEvent != null && BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Timeline latest eventId=${latestTimelineEvent.eventId} timelineRowId=${latestTimelineEvent.timelineRowid} ts=${latestTimelineEvent.timestamp}"
            )
        }
        
        // Read receipts come from sync_complete - no DB loading needed
        loadReactionsForRoom(roomId, cachedEvents)
        applyAggregatedReactionsFromEvents(cachedEvents, "cache")
        
        // Update room state from timeline events (name/avatar) if present
        updateRoomStateFromTimelineEvents(roomId, cachedEvents)
        
        // Update member profiles from timeline m.room.member events if present
        updateMemberProfilesFromTimelineEvents(roomId, cachedEvents)
        
        // Set smallest rowId from cached events for pagination
        val smallestCached = cachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
        if (smallestCached > 0) {
            smallestRowId = smallestCached
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✅ Room opened INSTANTLY with ${timelineEvents.size} cached events (no loading flash)")
        
        // Clear notification flag since we've successfully loaded the room
        if (openingFromNotification) {
            isPendingNavigationFromNotification = false
        }
        
        // NAVIGATION PERFORMANCE: Only request missing data for cached room opening
        val currentNavigationState = getRoomNavigationState(roomId)
        
        // Only request essential room state if not already loaded
        if (!skipNetworkRequests && currentNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
            requestRoomState(roomId)
        }
        
        // OPPORTUNISTIC PROFILE LOADING: Skip member data loading to prevent OOM
        // Member profiles will be loaded on-demand when actually needed for rendering
        if (currentNavigationState?.memberDataLoaded != true) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SKIPPING member data loading (using opportunistic loading)")
            // Mark as loaded to prevent repeated attempts
            navigationCache[roomId] = currentNavigationState?.copy(memberDataLoaded = true) ?: RoomNavigationState(roomId, memberDataLoaded = true)
        }
        
        // Mark as read only if room is actually visible (not just a minimized bubble)
        // Check if this is a bubble and if it's visible
        val shouldMarkAsRead = if (BubbleTracker.isBubbleOpen(roomId)) {
            // Bubble exists - only mark as read if it's visible/maximized
            BubbleTracker.isBubbleVisible(roomId)
        } else {
            // Not a bubble - mark as read (normal room view)
            true
        }
        
        if (shouldMarkAsRead) {
            val mostRecentEvent = cachedEvents.maxByOrNull { it.timestamp }
            if (mostRecentEvent != null) {
                markRoomAsRead(roomId, mostRecentEvent.eventId)
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)")
        }
        
        // IMPORTANT: Request historical reactions even when using cache
        // The cache filters out reaction events, so we need a paginate request to load them
        if (!skipNetworkRequests && REACTION_BACKFILL_ON_OPEN_ENABLED) {
            requestHistoricalReactions(roomId, smallestCached)
        }

        // No forward paginate on open; all additional history must be user-triggered
    }

    /**
     * Convert resolved TimelineEvents into render-ready rows and persist to the renderable_events table.
     * This avoids recomputing relations/edits/redactions at UI time.
     */
    private fun persistRenderableEvents(roomId: String, events: List<TimelineEvent>) {
        if (events.isEmpty()) return
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping renderable event persistence (in-memory cache only)")
        }
    }

    /**
     * Insert a local echo into timeline/renderable cache for immediate UI display.
     */
    // Local echo rendering removed.

    /**
     * Load an original event (and minimal context) from on-disk DB for deleted-content preview.
     * Returns the event with redaction cleared so it renders as originally sent.
     */
    /**
     * Update room state (name/avatar) from timeline events if they're more recent than current state.
     * This ensures room state stays in sync when m.room.name or m.room.avatar events appear in the timeline.
     */
    private fun updateRoomStateFromTimelineEvents(roomId: String, events: List<TimelineEvent>) {
        if (roomId.isEmpty() || events.isEmpty()) {
            return
        }
        
        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot update room state for $roomId - appContext is null")
            return
        }
        
        // Find the most recent m.room.name and m.room.avatar events
        val nameEvent = events
            .filter { it.type == "m.room.name" && it.timestamp > 0 }
            .maxByOrNull { it.timestamp }
        
        val avatarEvent = events
            .filter { it.type == "m.room.avatar" && it.timestamp > 0 }
            .maxByOrNull { it.timestamp }
        
        if (nameEvent == null && avatarEvent == null) {
            // No room state events in this batch
            return
        }
        
        val eventName = nameEvent?.content?.optString("name")?.takeIf { it.isNotBlank() }
        val eventAvatarUrl = avatarEvent?.content?.optString("url")?.takeIf { it.isNotBlank() }

        var updated = false
        val currentRoomItem = roomMap[roomId]
        var updatedRoomItem = currentRoomItem
        if (currentRoomItem != null) {
            if (eventName != null && eventName != currentRoomItem.name) {
                updatedRoomItem = updatedRoomItem?.copy(name = eventName)
                updated = true
            }
            if (eventAvatarUrl != null && eventAvatarUrl != currentRoomItem.avatarUrl) {
                updatedRoomItem = updatedRoomItem?.copy(avatarUrl = eventAvatarUrl)
                updated = true
            }
            if (updated && updatedRoomItem != null) {
                roomMap[roomId] = updatedRoomItem
            }
        }

        if (currentRoomId == roomId && currentRoomState != null) {
            currentRoomState = currentRoomState?.copy(
                name = eventName ?: currentRoomState?.name,
                avatarUrl = eventAvatarUrl ?: currentRoomState?.avatarUrl
            )
        }

        if (updated) {
            scheduleRoomReorder()
            roomListUpdateCounter++
        }
    }
    
    /**
     * Update member profiles from timeline m.room.member events in a batch.
     * This ensures member profiles stay in sync when profile changes appear in the timeline.
     */
    private fun updateMemberProfilesFromTimelineEvents(roomId: String, events: List<TimelineEvent>) {
        if (roomId.isEmpty() || events.isEmpty()) {
            return
        }
        
        // Find all m.room.member events with timelineRowid >= 0 (timeline events, not state events)
        val memberEvents = events.filter { 
            it.type == "m.room.member" && it.timelineRowid >= 0L 
        }
        
        if (memberEvents.isEmpty()) {
            return
        }
        
        // Process each member event
        for (event in memberEvents) {
            updateMemberProfileFromTimelineEvent(roomId, event)
        }
    }
    
    /**
     * Update member profile from a timeline m.room.member event if display name or avatar changed.
     * This ensures member profiles stay in sync when profile changes appear in the timeline.
     * Handles both additions/changes and removals (empty values).
     */
    private fun updateMemberProfileFromTimelineEvent(roomId: String, event: TimelineEvent) {
        if (roomId.isEmpty() || event.type != "m.room.member") {
            return
        }
        
        val userId = event.stateKey ?: event.sender
        if (userId.isEmpty()) {
            return
        }
        
        val content = event.content ?: return
        val membership = content.optString("membership")?.takeIf { it.isNotBlank() }
        
        // Only update profile for joined members (membership == "join")
        // Leave/ban events don't have profile info to extract
        if (membership != "join") {
            return
        }
        
        // Extract displayname and avatar_url - allow empty strings to handle removals
        // Empty string means the field was explicitly removed, null means it wasn't present
        val displayNameRaw = if (content.has("displayname")) content.optString("displayname") else null
        val displayName = displayNameRaw?.takeIf { it.isNotBlank() }
        val avatarUrlRaw = if (content.has("avatar_url")) content.optString("avatar_url") else null
        val avatarUrl = avatarUrlRaw?.takeIf { it.isNotBlank() }
        
        // Always update profile, even if both are null (removal case)
        // This ensures the profile cache reflects the current state
        val newProfile = MemberProfile(displayName, avatarUrl)
        
        // Check if this is actually a profile change (not just initial join)
        val existingProfile = getMemberProfile(roomId, userId)
        val isProfileChange = existingProfile != null && 
            (existingProfile.displayName != displayName || existingProfile.avatarUrl != avatarUrl)
        
        if (isProfileChange || existingProfile == null) {
            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
            storeMemberProfile(roomId, userId, newProfile)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated member profile for $userId in $roomId from timeline event (displayName=${displayName != null}, avatarUrl=${avatarUrl != null})")
        }
    }
    
    private fun loadReactionsForRoom(roomId: String, cachedEvents: List<TimelineEvent>, forceReload: Boolean = false) {
        if (cachedEvents.isEmpty()) return

        // Allow reloading reactions when new events arrive (e.g., from paginate response)
        if (!forceReload && !roomsWithLoadedReactions.add(roomId)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Reactions for room $roomId already loaded from cache, skipping")
            return
        }

        // CRITICAL FIX: Process reaction events from cache to restore reactions when reopening a room
        // Reaction events are stored separately in the cache (filtered from timeline events)
        val reactionEvents = RoomTimelineCache.getCachedReactionEvents(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: loadReactionsForRoom($roomId) - found ${reactionEvents.size} cached reaction events")
        if (reactionEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${reactionEvents.size} reaction events from cache for room $roomId")
            var processedCount = 0
            for (reactionEvent in reactionEvents) {
                // Process each reaction event to rebuild messageReactions
                if (processReactionFromTimeline(reactionEvent)) {
                    processedCount++
                }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored reactions from ${processedCount}/${reactionEvents.size} cached reaction events for room $roomId")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No cached reaction events found for room $roomId")
        }
    }

    private fun applyAggregatedReactionsFromEvents(events: List<TimelineEvent>, source: String) {
        if (events.isEmpty()) return

        val aggregatedByEvent = mutableMapOf<String, List<MessageReaction>>()
        for (event in events) {
            val reactionsObject = event.aggregatedReactions ?: continue
            
            // CRASH FIX: Wrap reaction processing in try-catch to handle malformed JSON gracefully
            try {
            val reactionList = mutableListOf<MessageReaction>()
            val keys = reactionsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                    try {
                var count = 0
                when (val value = reactionsObject.opt(key)) {
                    is Number -> count = value.toInt()
                    is JSONObject -> count = value.optInt("count", 0)
                    else -> {
                        // Attempt fallback to optInt
                        count = reactionsObject.optInt(key, 0)
                    }
                }
                if (count > 0 && !key.isNullOrBlank()) {
                    reactionList.add(
                        MessageReaction(
                            emoji = key,
                            count = count,
                            users = emptyList()
                        )
                    )
                        }
                    } catch (e: Exception) {
                        // Skip this reaction key if there's an error processing it
                        android.util.Log.w("Andromuks", "AppViewModel: Error processing reaction key '$key' for event ${event.eventId}: ${e.message}")
                }
            }
            if (reactionList.isNotEmpty()) {
                    // Sort reactions by emoji - handle any potential comparison errors
                    try {
                aggregatedByEvent[event.eventId] = reactionList.sortedBy { it.emoji }
                    } catch (e: Exception) {
                        // If sorting fails, use unsorted list
                        android.util.Log.w("Andromuks", "AppViewModel: Error sorting reactions for event ${event.eventId}, using unsorted: ${e.message}")
                        aggregatedByEvent[event.eventId] = reactionList
                    }
                }
            } catch (e: Exception) {
                // Skip this event's reactions if there's an error iterating over them
                android.util.Log.w("Andromuks", "AppViewModel: Error processing aggregated reactions for event ${event.eventId} from $source: ${e.message}", e)
            }
        }

        if (aggregatedByEvent.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: applyAggregatedReactionsFromEvents($source) - no aggregated reactions found")
            return
        }

        val updated = messageReactions.toMutableMap()
        var changed = false

        for ((eventId, reactions) in aggregatedByEvent) {
            val existing = updated[eventId]
            // CRITICAL FIX: Merge aggregated reactions with existing reactions instead of only setting if empty
            // This ensures reactions from paginate (aggregated) are combined with reactions from sync_complete (individual events)
            if (existing == null || existing.isEmpty()) {
                // No existing reactions - use aggregated reactions directly
                updated[eventId] = reactions
                changed = true
            } else {
                // Merge aggregated reactions with existing reactions
                // Create a map of emoji -> count from aggregated reactions
                val aggregatedMap = reactions.associate { it.emoji to it.count }
                val mergedReactions = existing.map { existingReaction ->
                    val aggregatedCount = aggregatedMap[existingReaction.emoji] ?: existingReaction.count
                    // Use the larger count (aggregated reactions from paginate are authoritative for counts)
                    if (aggregatedCount > existingReaction.count) {
                        existingReaction.copy(count = aggregatedCount)
                    } else {
                        existingReaction
                    }
                }.toMutableList()
                
                // Add any reactions from aggregated that don't exist in current
                val existingEmojis = existing.map { it.emoji }.toSet()
                reactions.forEach { aggregatedReaction ->
                    if (aggregatedReaction.emoji !in existingEmojis) {
                        mergedReactions.add(aggregatedReaction)
                    }
                }
                
                updated[eventId] = mergedReactions
                changed = true
            }
        }

        if (changed) {
            messageReactions = updated
            reactionUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Applied aggregated reactions from $source for ${aggregatedByEvent.size} events"
            )
            // PERFORMANCE FIX: Removed persistRenderableEvents() - UI now uses timelineEvents directly
        }
    }

    /**
     * Ensure timeline cache is fresh (cache-only approach, no DB loading)
     * If cache is empty or room is not actively cached, triggers paginate request
     */
    suspend fun ensureTimelineCacheIsFresh(roomId: String, limit: Int = 100, isBackground: Boolean = false) {
        val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
        val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
        
        // If cache has events and room is actively cached, cache is fresh
        if (cachedEvents != null && cachedEvents.isNotEmpty() && isActivelyCached) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: ensureTimelineCacheIsFresh($roomId) - cache is fresh (${cachedEvents.size} events, actively cached)"
            )
            return
        }
        
        // Cache is empty or room not actively cached - trigger paginate request
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: ensureTimelineCacheIsFresh($roomId) - cache empty or not actively cached, triggering paginate (background=$isBackground)"
        )
        
        // Issue paginate request to fill cache
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: ensureTimelineCacheIsFresh($roomId) - WebSocket not connected, cannot paginate")
            return
        }
        
        withContext(Dispatchers.Main) {
            val paginateRequestId = requestIdCounter++
            // CRITICAL FIX: Use backgroundPrefetchRequests for background pagination to prevent timeline rebuild
            // Background pagination should only update cache, not rebuild the timeline for the currently open room
            if (isBackground) {
                backgroundPrefetchRequests[paginateRequestId] = roomId
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ensureTimelineCacheIsFresh($roomId) - using backgroundPrefetchRequests for silent cache update"
                )
            } else {
                timelineRequests[paginateRequestId] = roomId
            }
            val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0, // Fetch latest events
                "limit" to limit,
                "reset" to false
            ))
            
            if (result == WebSocketResult.SUCCESS) {
                // Mark room as actively cached so SyncIngestor knows to update it
                RoomTimelineCache.markRoomAsCached(roomId)
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ensureTimelineCacheIsFresh($roomId) - paginate request sent, room marked as actively cached (background=$isBackground)"
                )
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: ensureTimelineCacheIsFresh($roomId) - failed to send paginate request: $result")
            }
        }
    }
    
    /**
     * Trigger preemptive pagination for a room when a notification is generated.
     * This ensures the room timeline is cached before the user taps the notification.
     * Called from broadcast receiver when notification is generated.
     */
    fun triggerPreemptivePagination(roomId: String) {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: triggerPreemptivePagination called for room: $roomId")
            
            // Check if room is already in cache
            val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
            val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
            
            if (cachedEventCount >= 50 && isActivelyCached) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room $roomId already in cache ($cachedEventCount events >= 50, actively cached), skipping preemptive pagination")
                return@launch
            }
            
            // CRITICAL FIX: Use background pagination to prevent timeline rebuild for currently open room
            // Background pagination only updates cache, doesn't rebuild timeline
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggering preemptive pagination for room: $roomId (cached: $cachedEventCount events, actively cached: $isActivelyCached) - using background mode")
            ensureTimelineCacheIsFresh(roomId, limit = INITIAL_ROOM_PAGINATE_LIMIT, isBackground = true)
        }
    }

    fun requestRoomTimeline(roomId: String, useLruCache: Boolean = true) {
        android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: START - roomId=$roomId, useLruCache=$useLruCache, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading, isPaginating=$isPaginating")
        
        // Check if we're refreshing the same room before updating currentRoomId
        val isRefreshingSameRoom = currentRoomId == roomId && timelineEvents.isNotEmpty()
        
        // LRU CACHE: Try to restore from cache first (instant room switch)
        if (useLruCache && !isRefreshingSameRoom && restoreFromLruCache(roomId)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✅ INSTANT room switch from LRU cache for $roomId (${timelineEvents.size} events)")
            updateCurrentRoomIdInPrefs(roomId)
            isTimelineLoading = false
            // Mark as actively cached so sync_complete updates keep this cache fresh.
            RoomTimelineCache.markRoomAsCached(roomId)
            RoomTimelineCache.markRoomAccessed(roomId)
            
            // Store room open timestamp for animation purposes
            val openTimestamp = System.currentTimeMillis()
            roomOpenTimestamps[roomId] = openTimestamp
            
            // Still request room state in background for any updates
            if (isWebSocketConnected() && !pendingRoomStateRequests.contains(roomId)) {
                val stateRequestId = requestIdCounter++
                roomStateRequests[stateRequestId] = roomId
                pendingRoomStateRequests.add(roomId)
                sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                    "room_id" to roomId,
                    "include_members" to false,
                    "fetch_members" to false,
                    "refetch" to false
                ))
            }
            return
        }
        
        // CRITICAL: Store room open timestamp when opening a room (not when refreshing the same room)
        // This timestamp will be used to determine which messages should animate
        // Only messages with timestamp NEWER than this will animate
        if (!isRefreshingSameRoom || !roomOpenTimestamps.containsKey(roomId)) {
            val openTimestamp = System.currentTimeMillis()
            roomOpenTimestamps[roomId] = openTimestamp
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Stored room open timestamp for $roomId: $openTimestamp (only messages newer than this will animate)")
        }
        
        updateCurrentRoomIdInPrefs(roomId)
        
        // OPPORTUNISTIC PROFILE LOADING: Only request room state without members to prevent OOM
        // Member profiles will be loaded on-demand when actually needed for rendering
        if (isWebSocketConnected() && !pendingRoomStateRequests.contains(roomId)) {
            val stateRequestId = requestIdCounter++
            roomStateRequests[stateRequestId] = roomId
            pendingRoomStateRequests.add(roomId)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting room state WITHOUT members to prevent OOM (reqId: $stateRequestId)")
            sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                "room_id" to roomId,
                "include_members" to false,  // CRITICAL: Don't load all members
                "fetch_members" to false,
                "refetch" to false
            ))
        }
        
        // NAVIGATION PERFORMANCE: Check cached navigation state and use partial loading
        val navigationState = getRoomNavigationState(roomId)
        if (navigationState != null && navigationState.essentialDataLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Using cached navigation state for: $roomId")
            
            // Load additional details in background if needed
            loadRoomDetails(roomId, navigationState)
        }
        
        // Check if we're opening from a notification (for optimized cache handling)
        val openingFromNotification = isPendingNavigationFromNotification && pendingRoomNavigation == roomId
        
        // PROACTIVE CACHE MANAGEMENT: Check if room is actively cached
        val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
        val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
        
        // CRITICAL FIX: Removed isActivelyCached requirement from instant cache hit
        // If we have events in RAM, we should ALWAYS show them immediately.
        // If not actively cached, we will activate it and paginate in the background.
        if (cachedEvents != null && cachedEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✅ Found ${cachedEvents.size} events in RoomTimelineCache for $roomId (activelyCached=$isActivelyCached)")
            
            // CRITICAL FIX: Mark as actively cached so sync_complete updates start being applied immediately
            // This is essential if we're opening a room that was pre-cached (e.g. via preemptive pagination)
            // or if the WebSocket reconnected after being idle (clearing activelyCachedRooms but keeping events).
            if (!isActivelyCached) {
                RoomTimelineCache.markRoomAsCached(roomId)
            }
            
            // BACKFILL: Seed renderable cache from cache snapshot so UI can avoid recomputing relations on open.
            persistRenderableEvents(roomId, cachedEvents)
            
            // Process events through chain processing (builds timeline structure)
            processCachedEvents(roomId, cachedEvents, openingFromNotification = false)
            
            // Mark room as accessed in RoomTimelineCache for LRU eviction
            RoomTimelineCache.markRoomAccessed(roomId)
            
            // Still send paginate request to fetch any newer events from server
            // Only send when opening a new room (not refreshing the same room)
            // GUARD: Check if a paginate request is already pending for this room (atomic check-and-set)
            val wasAdded = roomsWithPendingPaginate.add(roomId)
            
            if (!isRefreshingSameRoom && isWebSocketConnected() && INITIAL_ROOM_PAGINATE_LIMIT > 0 && wasAdded) {
                val paginateRequestId = requestIdCounter++
                timelineRequests[paginateRequestId] = roomId
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending paginate request to fetch newer events for $roomId (limit=$INITIAL_ROOM_PAGINATE_LIMIT, reqId=$paginateRequestId)")
                val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                    "room_id" to roomId,
                    "max_timeline_id" to 0, // Fetch latest events
                    "limit" to INITIAL_ROOM_PAGINATE_LIMIT,
                    "reset" to false
                ))
                
                if (result != WebSocketResult.SUCCESS) {
                    // Remove from tracking if send failed
                    timelineRequests.remove(paginateRequestId)
                    roomsWithPendingPaginate.remove(roomId)
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to send paginate request for newer events for $roomId: $result")
                }
            } else if (!wasAdded && BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Skipping paginate request for newer events for $roomId - already have a pending paginate request")
            } else if (!wasAdded) {
                // Remove if we didn't add it (already pending)
                roomsWithPendingPaginate.remove(roomId)
            }
            
            return
        }
        
        // CACHE EMPTY OR NOT ACTIVELY CACHED: Issue paginate command to fill the cache
        android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: Cache empty/missing - roomId=$roomId, cachedEvents=${cachedEvents?.size ?: 0}, isActivelyCached=$isActivelyCached, isWebSocketConnected=${isWebSocketConnected()}")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "🟢 requestRoomTimeline: WebSocket not connected - roomId=$roomId, setting loading=true and clearing timeline")
            // Set loading state and clear timeline
            timelineEvents = emptyList()
            isTimelineLoading = true
            android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: EXIT (WebSocket not connected) - roomId=$roomId, isTimelineLoading=$isTimelineLoading")
            return
        }
        
        // CRITICAL FIX: When cache is insufficient (< 50 events, which is half of paginate limit), always paginate when opening a room
        // This ensures rooms with evicted cache or minimal cache still get populated
        // AUTO_PAGINATION_ENABLED only controls automatic pagination for loading more history, not initial pagination
        // We request 100 events via paginate, so if we have less than half (50), we should paginate
        val currentCachedCount = RoomTimelineCache.getCachedEventCount(roomId)
        val cacheInsufficient = currentCachedCount < 50
        
        if (cacheInsufficient && !isRefreshingSameRoom) {
            // Cache is insufficient - send paginate request to populate it
            // GUARD: Check if a paginate request is already pending for this room (atomic check-and-set)
            val wasAdded = roomsWithPendingPaginate.add(roomId)
            if (!wasAdded) {
                // Room already has a pending paginate request
                android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: Paginate already pending - roomId=$roomId")
                timelineEvents = emptyList()
                isTimelineLoading = true
                return
            }
            
            val paginateRequestId = requestIdCounter++
            timelineRequests[paginateRequestId] = roomId
            android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: Cache insufficient ($currentCachedCount < 50) - sending paginate - roomId=$roomId, requestId=$paginateRequestId, limit=$INITIAL_ROOM_PAGINATE_LIMIT")
            
            // Set loading state BEFORE sending command
            timelineEvents = emptyList()
            isTimelineLoading = true
            
            val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0, // Fetch latest events
                "limit" to INITIAL_ROOM_PAGINATE_LIMIT,
                "reset" to false
            ))
            
            if (result == WebSocketResult.SUCCESS) {
                // PROACTIVE CACHE MANAGEMENT: Mark room as actively cached so SyncIngestor knows to update it
                RoomTimelineCache.markRoomAsCached(roomId)
                markInitialPaginate(roomId, "cache_insufficient")
                android.util.Log.d("Andromuks", "🟢 requestRoomTimeline: Paginate sent successfully - roomId=$roomId, requestId=$paginateRequestId, marked as actively cached, waiting for response...")
            } else {
                android.util.Log.w("Andromuks", "🟢 requestRoomTimeline: FAILED to send paginate - roomId=$roomId, requestId=$paginateRequestId, result=$result, removing from tracking")
                timelineRequests.remove(paginateRequestId)
                roomsWithPendingPaginate.remove(roomId)
                isTimelineLoading = false
            }
            
            // Reset pagination state for new room
            smallestRowId = -1L
            isPaginating = false
            hasMoreMessages = true
            
            // Clear edit chain mapping when opening a new room
            eventChainMap.clear()
            editEventsMap.clear()
            MessageVersionsCache.clear()
            MessageReactionsCache.clear()
            messageReactions = emptyMap()
            roomsWithLoadedReactions.remove(roomId)
            
            // Load essential data
            val missNavigationState = getRoomNavigationState(roomId)
            if (missNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
                requestRoomState(roomId)
            }
            
            return
        }
        
        // Cache is sufficient OR we're refreshing the same room - handle accordingly
        if (isRefreshingSameRoom) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preserving existing timeline on resume (${timelineEvents.size} events)")
            isTimelineLoading = false
        } else {
            // CRITICAL FIX: If we reached here with events in cache, we MUST call processCachedEvents
            // This happens if currentCachedCount was sufficient but cachedEvents was somehow missing in the first check
            // or other race conditions.
            val eventsToProcess = cachedEvents ?: RoomTimelineCache.getCachedEvents(roomId)
            if (eventsToProcess != null && eventsToProcess.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cache has $currentCachedCount events (>= 50) - processing them now")
                processCachedEvents(roomId, eventsToProcess, false)
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cache reported sufficient count ($currentCachedCount) but no events found - setting loading false anyway")
                isTimelineLoading = false
            }
        }
    }
    /**
     * Fully refreshes the room timeline by resetting in-memory state and fetching a clean snapshot.
     * Steps:
     * 1. Marks the room as the current timeline so downstream handlers know which room is active
     * 2. Clears all RAM caches and timeline bookkeeping for the room
     * 3. Resets pagination flags
     * 4. Requests fresh room state
     * 5. Sends a paginate command for up to 100 events (ingest pipeline updates cache)
     */
    fun fullRefreshRoomTimeline(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full refresh for room: $roomId (resetting caches and requesting fresh snapshot)")
        setAutoPaginationEnabled(false, "manual_refresh_$roomId")
        
        // For manual refresh, clear the pagination flag to allow pagination to proceed
        // Force removal to ensure we can always request fresh events
        roomsPaginatedOnce.remove(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared pagination flag for manual refresh of room: $roomId")
        
        // REMOVED: Skip check - manual refresh should always request fresh events from server
        // Even if the room was paginated before, we want to force a new paginate request
        
        // 1. Mark room as current so sync handlers and pagination know which timeline is active
        updateCurrentRoomIdInPrefs(roomId)
        
        // 2. Wipe in-memory cache/state for this room
        RoomTimelineCache.clearRoomCache(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        timelineEvents = emptyList()
        // DISABLED: wipe - keeping cache entries to preserve timeline data
        isTimelineLoading = true
        
        // 3. Reset pagination flags and bookkeeping
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        eventChainMap.clear()
        editEventsMap.clear()
        MessageVersionsCache.clear()
        // editToOriginal is computed from messageVersions, no need to clear separately
        // redactionCache is computed from messageVersions, no need to clear separately
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        
        // Clear new message tracking and room-open timestamp
        newMessageAnimations.clear()
        roomOpenTimestamps.remove(roomId)
        
        // Reset member update counter to avoid stale diffs
        memberUpdateCounter = 0
        
        // 4. Request fresh room state
        requestRoomState(roomId)
        
        // 5. Request up to 100 events from the backend; ingest path will update the cache
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        val result = sendWebSocketCommand(
            "paginate",
            paginateRequestId,
            mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 100,
            "reset" to false
            )
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent paginate request for room: $roomId (100 events) - awaiting response to rebuild timeline")
        if (result == WebSocketResult.SUCCESS) {
            markInitialPaginate(roomId, "full_refresh")
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Failed to send full refresh paginate for $roomId (result=$result)"
            )
        }
    }
    
    suspend fun prefetchRoomSnapshot(roomId: String, limit: Int = 100, timeoutMs: Long = 6000L): Boolean {
        if (!AUTO_PAGINATION_ENABLED) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Prefetch snapshot disabled (AUTO_PAGINATION_ENABLED=false) for $roomId")
            return false
        }
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "prefetch_snapshot")
            return true
        }

        val ws = WebSocketService.getWebSocket() ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot prefetch snapshot for $roomId - WebSocket not connected")
            return false
        }
        
        val deferred = CompletableDeferred<Unit>()
        registerRoomSnapshotAwaiter(roomId, deferred)
        
        val requestId = requestIdCounter++
        backgroundPrefetchRequests[requestId] = roomId
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Prefetching snapshot for room $roomId (requestId=$requestId, limit=$limit, timeout=${timeoutMs}ms)"
        )
        
        val commandResult = sendWebSocketCommand(
            "paginate",
            requestId,
            mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0,
                "limit" to limit,
                "reset" to false
            )
        )
        
        if (commandResult != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Prefetch paginate for $roomId failed to send (result=$commandResult)"
            )
            backgroundPrefetchRequests.remove(requestId)
            unregisterRoomSnapshotAwaiter(roomId, deferred)
            return false
        }
        
        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Prefetch snapshot complete for room $roomId")
            markInitialPaginate(roomId, "prefetch_snapshot")
            true
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Prefetch snapshot timed out for room $roomId after ${timeoutMs}ms"
            )
            false
        } finally {
            unregisterRoomSnapshotAwaiter(roomId, deferred)
            backgroundPrefetchRequests.remove(requestId)
        }
    }
    
    // OPTIMIZATION #4: Cache-first navigation method
    fun navigateToRoomWithCache(roomId: String, notificationTimestamp: Long? = null) {
        android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: START - roomId=$roomId, notificationTimestamp=$notificationTimestamp, isProcessingPendingItems=$isProcessingPendingItems, initialSyncComplete=$initialSyncComplete")
        updateCurrentRoomIdInPrefs(roomId)
        // Add to opened rooms (exempt from cache clearing on reconnect)
        RoomTimelineCache.addOpenedRoom(roomId)
        
        // CRITICAL FIX: Set isTimelineLoading = true immediately to ensure UI shows loading state
        // and awaitRoomDataReadiness waits for processing to begin.
        isTimelineLoading = true
        
        // CRITICAL FIX: Always load from DB and hydrate RAM cache BEFORE processing events
        // This ensures we always have the latest events from DB before rendering
        // CACHE-ONLY APPROACH: No DB loading - rely on cache or paginate
        // Cache is populated from sync_complete messages or paginate responses only
        viewModelScope.launch {
            val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
            android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Cache check - roomId=$roomId, cachedEventCount=$cachedEventCount, isActivelyCached=${RoomTimelineCache.isRoomActivelyCached(roomId)}")
            
            // OPTIMIZATION #4: Use the exact same logic as requestRoomTimeline for consistency
            // We request 100 events via paginate, so if we have >= 50 (half), use cache
            if (cachedEventCount >= 50) {
                android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Using cache (>=50 events) - roomId=$roomId, cachedEventCount=$cachedEventCount")
                // OPTIMIZATION #4: Use cached data immediately (same threshold as requestRoomTimeline)
                val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
                
                if (cachedEvents != null) {
                    android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Cache hit - roomId=$roomId, cachedEvents.size=${cachedEvents.size}, building timeline from cache")
                    
                    // REFACTORED: Use centralized processCachedEvents instead of manual duplication
                    processCachedEvents(roomId, cachedEvents, openingFromNotification = notificationTimestamp != null)
                    
                    // CRITICAL FIX: Mark as actively cached in singleton cache
                    // This ensures sync_complete updates keep this cache fresh
                    RoomTimelineCache.markRoomAsCached(roomId)
                    RoomTimelineCache.markRoomAccessed(roomId)
                    
                    // Store room open timestamp for animation purposes
                    roomOpenTimestamps[roomId] = System.currentTimeMillis()
                    
                    android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: SUCCESS - roomId=$roomId, timeline built from cache (${cachedEvents.size} events), isTimelineLoading=$isTimelineLoading")
                    return@launch // Exit early - room is already rendered from cache
                } else {
                    android.util.Log.w("Andromuks", "🔵 navigateToRoomWithCache: Cache miss - roomId=$roomId, cachedEventCount=$cachedEventCount but getCachedEvents returned null")
                }
            } else {
                android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Cache insufficient - roomId=$roomId, cachedEventCount=$cachedEventCount (<50), will request timeline")
            }
            
            // OPTIMIZATION #4: Fallback to regular requestRoomTimeline if no cache
            // This happens if cache is empty or has < 10 events
            // CRITICAL FIX: Wait for pending items to finish processing before requesting timeline
            // This prevents race condition where timeline request is sent while sync_complete is still processing
            // which can cause the timeline to wait indefinitely for a response
            if (isProcessingPendingItems) {
                android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Waiting for pending items - roomId=$roomId, isProcessingPendingItems=true")
                val waitStart = System.currentTimeMillis()
                // Wait for pending items to finish (with timeout to avoid infinite wait)
                withTimeoutOrNull(10_000L) {
                    while (isProcessingPendingItems) {
                        delay(100L)
                    }
                }
                val waitDuration = System.currentTimeMillis() - waitStart
                android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Pending items finished - roomId=$roomId, waitDuration=${waitDuration}ms, isProcessingPendingItems=$isProcessingPendingItems")
            }
            
            // REMOVED: Queue flush waiting - no longer needed since we removed queue blocking
            // Commands can be sent immediately even while retries are happening
            // Webmuks handles out-of-order messages and responses are matched by request_id
            
            android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: Calling requestRoomTimeline - roomId=$roomId, isTimelineLoading=$isTimelineLoading")
            requestRoomTimeline(roomId)
            android.util.Log.d("Andromuks", "🔵 navigateToRoomWithCache: requestRoomTimeline returned - roomId=$roomId, isTimelineLoading=$isTimelineLoading")
        }
    }

    private fun requestHistoricalReactions(roomId: String, smallestCached: Long) {
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "historical_reactions")
            return
        }
        val reactionRequestId = requestIdCounter++
        backgroundPrefetchRequests[reactionRequestId] = roomId
        val effectiveMaxTimelineId = if (smallestCached > 0) smallestCached else 0L
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send reaction request - currentRoomId: $currentRoomId, roomId=$roomId, smallestCached=$smallestCached, effectiveMaxTimelineId=$effectiveMaxTimelineId")
        val result = sendWebSocketCommand(
            "paginate",
            reactionRequestId,
            mapOf(
                "room_id" to roomId,
                "max_timeline_id" to effectiveMaxTimelineId,
                "limit" to 100,
                "reset" to false
            )
        )
        if (result == WebSocketResult.SUCCESS) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✅ Sent reaction request for cached room: $roomId (requestId: $reactionRequestId)")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Reaction request for $roomId (requestId: $reactionRequestId) could not be sent immediately (result=$result)")
        }
    }
    
    fun requestRoomState(roomId: String) {
        // PERFORMANCE: Prevent duplicate room state requests for the same room
        if (pendingRoomStateRequests.contains(roomId)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room state request already pending for $roomId, skipping duplicate")
            return
        }
        
        val stateRequestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingRoomStateRequests.add(roomId)
        roomStateRequests[stateRequestId] = roomId
        
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to false,
            "fetch_members" to false,
            "refetch" to false
        ))
    }
    
    /**
     * Requests complete room state including member list
     * Used by the Room Info screen to display detailed room information
     */
    fun requestRoomStateWithMembers(roomId: String, callback: (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting room state with members for room: $roomId")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with error, health monitor will handle reconnection")
            callback(null, "WebSocket not connected")
            return
        }
        
        val stateRequestId = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_room_state with members: $stateRequestId")
        
        // Store the callback to handle the response
        roomStateWithMembersRequests[stateRequestId] = callback
        
        // IMPORTANT: Request room state with include_members: true to get the full member list
        // This ensures RoomInfo screen displays the actual member list from the server
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,  // CRITICAL: Include members in response for RoomInfo screen
            "fetch_members" to false,
            "refetch" to false
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $stateRequestId (include_members=true)")
    }
    
    /**
     * OPPORTUNISTIC PROFILE LOADING: Request profile for a single user only when needed for rendering.
     * This prevents loading 15,000+ profiles upfront and only loads what's actually displayed.
     */
    private fun estimateProfileEntryBytes(key: String, profile: MemberProfile): Long {
        var bytes = (key.length * 2).toLong()
        profile.displayName?.let { bytes += it.length * 2L }
        profile.avatarUrl?.let { bytes += it.length * 2L }
        // overhead fudge factor
        bytes += 64
        return bytes
    }

    fun requestUserProfileOnDemand(userId: String, roomId: String) {
        // CRITICAL FIX: Check if we have a valid profile (not just non-null)
        // A profile with blank displayName should still be requested to get the actual name
        val existingProfile = getUserProfile(userId, roomId)
        if (existingProfile != null && !existingProfile.displayName.isNullOrBlank()) {
            // Profile exists and has a valid display name - skip request
            //android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
            return
        }
        
        val requestKey = "$roomId:$userId"
        
        fun enqueueNetworkRequest() {
            // Avoid duplicate network requests
        if (pendingProfileRequests.contains(requestKey)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId, skipping duplicate")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = recentProfileRequestTimes[requestKey]
        // CRITICAL FIX: Only throttle if we have a recent successful request
        // If the last request failed (no response), don't throttle to allow retry
        // We check if the request is still pending - if it's not pending but was recent, it succeeded
        val wasRecentRequest = lastRequestTime != null && (currentTime - lastRequestTime) < PROFILE_REQUEST_THROTTLE_MS
        val isStillPending = pendingProfileRequests.contains(requestKey)
        if (wasRecentRequest && !isStillPending) {
            // Recent successful request - throttle
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile request throttled for $userId (requested ${currentTime - lastRequestTime}ms ago)")
            return
        }
        
        // Clean up old throttle entries (older than throttle window) to prevent memory leaks
        val cutoffTime = currentTime - PROFILE_REQUEST_THROTTLE_MS
        recentProfileRequestTimes.entries.removeAll { (_, timestamp) -> timestamp < cutoffTime }
        
        // CRITICAL FIX: Clean up stale pending requests (older than 30 seconds)
        // This handles cases where requests failed silently or responses were lost
        val staleCutoffTime = currentTime - 30000L // 30 seconds
        val staleRequests = pendingProfileRequests.filter { key ->
            val requestTime = recentProfileRequestTimes[key] ?: 0L
            requestTime > 0 && requestTime < staleCutoffTime
        }
        staleRequests.forEach { key ->
            pendingProfileRequests.remove(key)
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up stale profile request for $key (older than 30s)")
        }
        
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting profile on-demand (network) for $userId in room $roomId")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping on-demand profile request")
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(requestKey)
        recentProfileRequestTimes[requestKey] = currentTime // Record request time for throttling
        roomSpecificStateRequests[requestId] = roomId  // Use roomSpecificStateRequests for get_specific_room_state responses
        
        // CRITICAL FIX: Store request metadata for timeout handling
        profileRequestMetadata[requestKey] = ProfileRequestMetadata(
            requestId = requestId,
            timestamp = currentTime,
            userId = userId,
            roomId = roomId
        )
        
        // Request specific room state for this user
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent on-demand profile request with ID $requestId for $userId")
        }
        
        // Call the network request function
        enqueueNetworkRequest()
    }
    /**
     * Request room-specific user profile from backend using get_specific_room_state.
     * This fetches the most up-to-date room-specific display name and avatar for a user.
     * The response will automatically update the room member cache via handleRoomSpecificStateResponse.
     */
    fun requestRoomSpecificUserProfile(roomId: String, userId: String) {
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot request room-specific profile")
            return
        }
        
        // Check if we already have a recent request for this user in this room
        val requestKey = "$roomId:$userId"
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = recentProfileRequestTimes[requestKey] ?: 0L
        // CRITICAL FIX: Only throttle if we have a recent successful request (not still pending)
        val wasRecentRequest = currentTime - lastRequestTime < PROFILE_REQUEST_THROTTLE_MS
        val isStillPending = pendingProfileRequests.contains(requestKey)
        if (wasRecentRequest && !isStillPending) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping room-specific profile request for $userId in $roomId (throttled)")
            return
        }
        
        // Check if request is already pending
        if (pendingProfileRequests.contains(requestKey)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room-specific profile request already pending for $userId in $roomId")
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(requestKey)
        recentProfileRequestTimes[requestKey] = currentTime
        roomSpecificStateRequests[requestId] = roomId
        
        // CRITICAL FIX: Store request metadata for timeout handling
        profileRequestMetadata[requestKey] = ProfileRequestMetadata(
            requestId = requestId,
            timestamp = currentTime,
            userId = userId,
            roomId = roomId
        )
        
        // Request specific room state for this user
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent room-specific profile request with ID $requestId for $userId in room $roomId")
    }
    
    /**
     * Request per-room member state for a user with callback.
     * This is used by UserInfoScreen to get room-specific display name and avatar.
     */
    fun requestPerRoomMemberState(
        roomId: String,
        userId: String,
        callback: (displayName: String?, avatarUrl: String?) -> Unit
    ) {
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot request per-room member state")
            callback(null, null)
            return
        }
        
        val requestId = requestIdCounter++
        roomSpecificStateRequests[requestId] = roomId
        roomSpecificProfileCallbacks[requestId] = callback
        
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent per-room member state request with ID $requestId for $userId in room $roomId")
    }
    
    /**
     * Requests updated profile information for users in a room using get_specific_room_state.
     * This is used to refresh stale profile cache data when opening a room.
     * The room will render immediately with cached data, then update as fresh data arrives.
     */
    fun requestUpdatedRoomProfiles(roomId: String, timelineEvents: List<TimelineEvent>) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting updated room profiles for room: $roomId")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping profile refresh")
            return
        }
        
        // Extract unique user IDs from timeline events
        val userIds = timelineEvents
            .map { it.sender }
            .distinct()
            .filter { !it.isBlank() && it != currentUserId } // Exclude current user and blanks
        
        if (userIds.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No users found in timeline events, skipping profile refresh")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting profile updates for ${userIds.size} users: $userIds")
        
        // Build the keys array for get_specific_room_state
        val keys = userIds.map { userId ->
            mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            )
        }
        
        val requestId = requestIdCounter++
        roomSpecificStateRequests[requestId] = roomId
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct ws.send()
        val keysList = keys.map { key ->
            mapOf(
                "room_id" to (key["room_id"] as? String ?: ""),
                "type" to (key["type"] as? String ?: ""),
                "state_key" to (key["state_key"] as? String ?: "")
            )
        }
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf("keys" to keysList))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent get_specific_room_state request with ID $requestId for ${keys.size} members")
    }
    
    /**
     * Request emoji pack data from a room using get_specific_room_state
     */
    private fun requestEmojiPackData(roomId: String, packName: String) {
        if (!isWebSocketConnected()) {
            // CRITICAL FIX: Queue emoji pack requests when WebSocket isn't ready
            // They will be processed when WebSocket connects
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket not connected, queuing emoji pack request for $packName in $roomId")
            deferredEmojiPackRequests.add(Pair(roomId, packName))
            return
        }
        
        val requestId = requestIdCounter++
        emojiPackRequests[requestId] = Pair(roomId, packName)
        roomSpecificStateRequests[requestId] = roomId
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct ws.send()
        val keysList = listOf(mapOf(
            "room_id" to roomId,
            "type" to "im.ponies.room_emotes",
            "state_key" to packName
        ))
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf("keys" to keysList))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent emoji pack request with ID $requestId for pack $packName in room $roomId")
    }
    
    /**
     * Requests the full member list for a room using get_room_state with include_members=true.
     * This populates the complete room member cache, ensuring we have accurate member information.
     * Should be called when opening a room if the member cache is empty or incomplete.
     */
    fun requestFullMemberList(roomId: String) {
        // PERFORMANCE: Prevent duplicate full member list requests for the same room
        if (pendingFullMemberListRequests.contains(roomId)) {
            return
        }
        
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingFullMemberListRequests.add(roomId)
        fullMemberListRequests[requestId] = roomId
        
        sendWebSocketCommand("get_room_state", requestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,
            "fetch_members" to false,
            "refetch" to false
        ))
        
    }
    
    fun sendTyping(roomId: String) {
        // PERFORMANCE: Rate limit typing indicators to reduce WebSocket traffic
        val currentTime = System.currentTimeMillis()
        val lastSent = lastTypingSent[roomId] ?: 0L
        if (currentTime - lastSent < TYPING_SEND_INTERVAL) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Typing indicator rate limited for room: $roomId (last sent ${currentTime - lastSent}ms ago)")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        // PHASE 5.3: set_typing uses positive request_id and expects a response
        val typingRequestId = requestIdCounter++
        val result = sendWebSocketCommand("set_typing", typingRequestId, mapOf(
            "room_id" to roomId,
            "timeout" to 10000
        ))
        
        if (result == WebSocketResult.SUCCESS) {
            lastTypingSent[roomId] = currentTime
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Typing indicator failed (result: $result), skipping")
        }
    }
    
    fun sendMessage(roomId: String, text: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId: '$roomId', text: '$text'")
        
        // Try to send the message immediately
        val result = sendMessageInternal(roomId, text)
        
        // If WebSocket is not available, queue the operation for retry when connection is restored
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: sendMessage failed with result: $result - queuing for retry when connection is restored")
            addPendingOperation(
                PendingWebSocketOperation(
                    type = "sendMessage",
                    data = mapOf(
                        "roomId" to roomId,
                        "text" to text
                    )
                ),
                saveToStorage = true // Save to storage when WebSocket is disconnected
            )
        }
    }
    private fun sendMessageInternal(roomId: String, text: String): WebSocketResult {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMessageInternal called")
        val messageRequestId = requestIdCounter++
        
        // PHASE 5.2: sendWebSocketCommand() now automatically tracks all commands with positive request_id
        // No need to manually add to queue here - it's handled in sendWebSocketCommand()
        val result = sendWebSocketCommand("send_message", messageRequestId, mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        ))
        
        if (result == WebSocketResult.SUCCESS) {
            messageRequests[messageRequestId] = roomId
            pendingSendCount++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Message send queued with request_id: $messageRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send message, result: $result")
        }
        
        return result
    }

    /**
     * Sends a message from a notification action.
     * This handles websocket connection state and schedules auto-shutdown if needed.
     * 
     * DEDUPLICATION: Prevents duplicate sends from notification replies within a 5-second window.
     * This fixes the issue where ordered broadcasts can be received multiple times.
     */
    fun sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMessageFromNotification called for room $roomId, text: '$text'")
        
        val now = System.currentTimeMillis()
        
        // Add to FIFO buffer - allows duplicates, only notification replies can add
        synchronized(pendingNotificationMessagesLock) {
            val pendingMessage = PendingNotificationMessage(
                roomId = roomId,
                text = text,
                timestamp = now,
                onComplete = onComplete
            )
            pendingNotificationMessages.add(pendingMessage)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added message to FIFO buffer (queue size: ${pendingNotificationMessages.size})")
        }
        
        // Check WebSocket health - if healthy, process immediately; if unhealthy, store in buffer
        if (isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket is healthy, processing message immediately")
            processNextPendingNotificationMessage()
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket is unhealthy, message stored in buffer (will process when healthy)")
            // Call completion callback immediately to prevent UI stalling
            onComplete?.invoke()
        }
    }
    
    /**
     * Check if WebSocket is healthy (connected and initialized)
     */
    private fun isWebSocketHealthy(): Boolean {
        return isWebSocketConnected() && spacesLoaded && canSendCommandsToBackend
    }
    
    /**
     * Process the next pending notification message from the FIFO buffer
     * Removes the message from the buffer when sent to WebSocket
     */
    private fun processNextPendingNotificationMessage() {
        if (!isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket not healthy, skipping message processing")
            return
        }
        
        val message = synchronized(pendingNotificationMessagesLock) {
            if (pendingNotificationMessages.isEmpty()) {
                null
            } else {
                pendingNotificationMessages.removeAt(0) // FIFO: remove oldest
            }
        }
        
        if (message == null) {
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing pending notification message (roomId: ${message.roomId}, text: '${message.text}', queue size: ${pendingNotificationMessages.size})")
        
        // Send message to WebSocket
        val messageRequestId = requestIdCounter++
        messageRequests[messageRequestId] = message.roomId
        pendingSendCount++
        beginNotificationAction()
        
        val completionWrapper: () -> Unit = {
            message.onComplete?.invoke()
            endNotificationAction()
        }
        notificationActionCompletionCallbacks[messageRequestId] = completionWrapper
        
        // Set up timeout
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = if (isAppVisible) 30000L else 10000L
            delay(timeoutMs)
            withContext(Dispatchers.Main) {
                if (notificationActionCompletionCallbacks.containsKey(messageRequestId)) {
                    android.util.Log.w("Andromuks", "AppViewModel: Message send timeout after ${timeoutMs}ms for requestId=$messageRequestId")
                    notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
                    messageRequests.remove(messageRequestId)
                    if (pendingSendCount > 0) {
                        pendingSendCount--
                    }
                }
            }
        }
        
        val commandData = mapOf(
            "room_id" to message.roomId,
            "text" to message.text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        val result = sendWebSocketCommand("send_message", messageRequestId, commandData)
        
        // Handle immediate failure - message already removed from buffer, so just log
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send pending notification message, result: $result")
            
            // If WebSocket is not connected, re-add message to buffer for retry when healthy
            if (result == WebSocketResult.NOT_CONNECTED) {
                synchronized(pendingNotificationMessagesLock) {
                    // Re-add to front of queue (FIFO) so it's retried first
                    pendingNotificationMessages.add(0, message)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Re-added message to FIFO buffer for retry (queue size: ${pendingNotificationMessages.size})")
                }
            }
            
            messageRequests.remove(messageRequestId)
            if (pendingSendCount > 0) {
                pendingSendCount--
            }
            notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
            return
        }
        
        // Message successfully sent - continue processing next message in buffer
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Message sent successfully, processing next in buffer if any")
        
        // Process next message in buffer if WebSocket is still healthy
        if (isWebSocketHealthy()) {
            processNextPendingNotificationMessage()
        }
    }
    
    /**
     * Marks a room as read from a notification action.
     * Uses the always-connected WebSocket maintained by the foreground service.
     */
    fun markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadFromNotification called for room $roomId")
        
        // Check websocket state
        if (!isWebSocketConnected() || !spacesLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket not ready yet, queueing notification action")
            
            // Queue the action to be executed when WebSocket is ready
            // (Foreground service maintains connection, this should be rare - only during initial startup)
            pendingNotificationActions.add(
                PendingNotificationAction(
                    type = "mark_read",
                    roomId = roomId,
                    eventId = eventId,
                    onComplete = onComplete
                )
            )
            
            // If callback provided and WebSocket not ready, call it immediately to prevent UI stalling
            if (onComplete != null) {
                android.util.Log.w("Andromuks", "AppViewModel: WebSocket not ready, calling completion callback immediately to prevent UI stalling")
                onComplete()
            }
            return
        }
        
        // WebSocket is ready (maintained by foreground service), mark as read directly
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marking room as read from notification (WebSocket maintained by service)")
        val markReadRequestId = requestIdCounter++
        
        markReadRequests[markReadRequestId] = roomId
        beginNotificationAction()
        val completionWrapper: () -> Unit = {
            onComplete?.invoke()
            endNotificationAction()
        }
        notificationActionCompletionCallbacks[markReadRequestId] = completionWrapper
        
        // Set up timeout to prevent infinite stalling - use IO dispatcher to avoid background throttling
        // Use shorter timeout when app is in background to handle throttling issues
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = if (isAppVisible) 30000L else 10000L // 10s timeout in background
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting mark read timeout to ${timeoutMs}ms (app visible: $isAppVisible)")
            delay(timeoutMs)
            // Switch to Main dispatcher only for the final callback
            withContext(Dispatchers.Main) {
                if (notificationActionCompletionCallbacks.containsKey(markReadRequestId)) {
                    android.util.Log.w("Andromuks", "AppViewModel: Mark read timeout after ${timeoutMs}ms for requestId=$markReadRequestId, calling completion callback")
                    notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
                    // Also clean up from markReadRequests
                    markReadRequests.remove(markReadRequestId)
                }
            }
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        
        // Handle immediate failure cases
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to send mark read from notification, result: $result")
            markReadRequests.remove(markReadRequestId)
            notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
            return
        }
        
        // No shutdown needed - foreground service keeps WebSocket open
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Mark read sent, WebSocket remains connected via service")
    }
    
    fun sendReaction(roomId: String, eventId: String, emoji: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendReaction called with roomId: '$roomId', eventId: '$eventId', emoji: '$emoji'")
        
        val ws = WebSocketService.getWebSocket() ?: return
        
        val reactionRequestId = requestIdCounter++
        
        // Track this outgoing request
        reactionRequests[reactionRequestId] = roomId
        
        // For custom emojis, extract MXC URL from formatted string for reaction key
        // Reactions should use ONLY the mxc:// URL, not the full markup
        val reactionKey = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
            // Extract MXC URL from format: ![:name:](mxc://url "Emoji: :name:")
            val mxcStart = emoji.indexOf("mxc://")
            if (mxcStart >= 0) {
                val mxcEnd = emoji.indexOf("\"", mxcStart)
                if (mxcEnd > mxcStart) {
                    emoji.substring(mxcStart, mxcEnd).trim()
                } else {
                    // Fallback: extract until closing parenthesis or end of string
                    val parenEnd = emoji.indexOf(")", mxcStart)
                    if (parenEnd > mxcStart) {
                        emoji.substring(mxcStart, parenEnd).trim()
                    } else {
                        emoji.substring(mxcStart).trim()
                    }
                }
            } else {
                emoji
            }
        } else {
            emoji
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "type" to "m.reaction",
            "content" to mapOf(
                "m.relates_to" to mapOf(
                    "rel_type" to "m.annotation",
                    "event_id" to eventId,
                    "key" to reactionKey
                )
            ),
            "disable_encryption" to false,
            "synchronous" to false
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_event with data: $commandData")
        sendWebSocketCommand("send_event", reactionRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $reactionRequestId")
        
        // Update recent emojis
        // For custom emojis, extract MXC URL from formatted string
        val emojiForRecent = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
            // Extract MXC URL from format: ![:name:](mxc://url "Emoji: :name:")
            val mxcStart = emoji.indexOf("mxc://")
            if (mxcStart >= 0) {
                val mxcEnd = emoji.indexOf("\"", mxcStart)
                if (mxcEnd > mxcStart) {
                    emoji.substring(mxcStart, mxcEnd)
                } else {
                    emoji.substring(mxcStart)
                }
            } else {
                emoji
            }
        } else {
            emoji
        }
        updateRecentEmojis(emojiForRecent)
    }
    
    fun updateRecentEmojis(emoji: String) {
        // CRITICAL: Do NOT update recentEmojiFrequencies here - it will be updated when we receive sync_complete from the server.
        // We only read from it to create the updated list to send, and update the UI optimistically for better UX.
        
        if (!hasLoadedRecentEmojisFromServer) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Skipping updateRecentEmojis - haven't loaded full recent emoji list from server yet. Will update after sync completes.")
            return
        }
        
        // Create a copy of the current frequencies (from server) and update it
        val currentFrequencies = recentEmojiFrequencies.toMutableList()
        val existingIndex = currentFrequencies.indexOfFirst { it.first == emoji }
        
        if (existingIndex >= 0) {
            // Emoji exists - increment its count
            val existingPair = currentFrequencies[existingIndex]
            val newCount = existingPair.second + 1
            currentFrequencies[existingIndex] = Pair(emoji, newCount)
        } else {
            // New emoji - add with count 1
            currentFrequencies.add(Pair(emoji, 1))
        }
        
        // Sort by frequency (descending)
        val sortedFrequencies = currentFrequencies.sortedByDescending { it.second }
        
        // Keep only top 20
        val updatedFrequencies = sortedFrequencies.take(20)
        
        // CRITICAL SAFEGUARD: Ensure we never have an empty list after update
        if (updatedFrequencies.isEmpty()) {
            android.util.Log.e("Andromuks", "AppViewModel: updateRecentEmojis resulted in empty list for emoji '$emoji' - this should never happen!")
            return
        }
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Preparing to send emoji update - current list has ${recentEmojiFrequencies.size} emojis, updated list will have ${updatedFrequencies.size} emojis")
            android.util.Log.d("Andromuks", "AppViewModel: Emoji '$emoji' will have count ${updatedFrequencies.find { it.first == emoji }?.second ?: 1}")
            android.util.Log.d("Andromuks", "AppViewModel: Full list being sent: ${updatedFrequencies.joinToString(", ") { "${it.first}(${it.second})" }}")
        }
        
        // Send the updated list to the server
        sendAccountDataUpdate(updatedFrequencies)
        
        // Update UI optimistically for better UX (but don't update recentEmojiFrequencies - server will send it back)
        val emojisList = updatedFrequencies.map { it.first }
        RecentEmojisCache.set(emojisList)
        recentEmojis = emojisList
    }
    
    private fun sendAccountDataUpdate(frequencies: List<Pair<String, Int>>) {
        val ws = WebSocketService.getWebSocket() ?: return
        
        // CRITICAL SAFEGUARD: Never send empty recent_emoji array to server
        // This would clear all recent emojis. If frequencies is empty, something went wrong.
        if (frequencies.isEmpty()) {
            android.util.Log.w("Andromuks", "AppViewModel: sendAccountDataUpdate called with empty frequencies list - skipping send to prevent clearing recent emojis")
            return
        }
        
        val accountDataRequestId = requestIdCounter++
        
        // Create the recent_emoji array format: [["emoji", count], ...]
        val recentEmojiArray = frequencies.map { listOf(it.first, it.second) }
        
        val commandData = mapOf(
            "type" to "io.element.recent_emoji",
            "content" to mapOf(
                "recent_emoji" to recentEmojiArray
            )
        )
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: set_account_data with ${frequencies.size} emojis")
            android.util.Log.d("Andromuks", "AppViewModel: Emojis being sent: ${frequencies.joinToString(", ") { "${it.first}(${it.second})" }}")
            android.util.Log.d("Andromuks", "AppViewModel: Full command data: $commandData")
        }
        sendWebSocketCommand("set_account_data", accountDataRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $accountDataRequestId")
    }
    
    fun sendReply(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendReply called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}")
        
        // Try to send the reply immediately
        val result = sendReplyInternal(roomId, text, originalEvent)
        
        // If WebSocket is not available, just log it - health monitoring will handle reconnection
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: sendReply failed with result: $result - health monitor will handle reconnection")
        }
    }
    
    private fun sendReplyInternal(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent): WebSocketResult {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendReplyInternal called")
        val messageRequestId = requestIdCounter++
        
        // Extract mentions from the original message sender
        val mentions = mutableListOf<String>()
        if (originalEvent.sender.isNotBlank()) {
            mentions.add(originalEvent.sender)
        }
        
        // PHASE 5.2: sendWebSocketCommand() now automatically tracks all commands with positive request_id
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to mapOf(
                "m.in_reply_to" to mapOf(
                    "event_id" to originalEvent.eventId
                )
            ),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        val result = sendWebSocketCommand("send_message", messageRequestId, commandData)
        
        if (result == WebSocketResult.SUCCESS) {
            messageRequests[messageRequestId] = roomId
            pendingSendCount++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Reply send queued with request_id: $messageRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send reply, result: $result")
        }
        
        return result
    }
    
    fun sendEdit(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendEdit called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}")
        
        val ws = WebSocketService.getWebSocket() ?: return
        val editRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[editRequestId] = roomId
        pendingSendCount++
        
        // CRITICAL FIX: Preserve reply relationship when editing a reply message
        // According to Matrix spec, edit events should preserve m.in_reply_to if the original message was a reply
        val replyInfo = originalEvent.getReplyInfo()
        
        // Build relates_to structure with edit relationship
        val relatesTo = mutableMapOf<String, Any>(
            "rel_type" to "m.replace",
            "event_id" to originalEvent.eventId
        )
        
        // Preserve reply relationship if the original message was a reply
        // The m.in_reply_to should be nested inside m.relates_to for edit events
        if (replyInfo != null) {
            relatesTo["m.in_reply_to"] = mapOf(
                "event_id" to replyInfo.eventId
            )
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preserving reply relationship to event: ${replyInfo.eventId} when editing ${originalEvent.eventId}")
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to relatesTo,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with edit data: $commandData")
        sendWebSocketCommand("send_message", editRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Edit command sent with request_id: $editRequestId")
    }
    
    fun sendMediaMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        mimeType: String,
        width: Int,
        height: Int,
        size: Long,
        blurHash: String,
        caption: String = "",
        msgType: String = "m.image",
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMediaMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', thumbnailUrl: '$thumbnailUrl'")
        
        val ws = WebSocketService.getWebSocket() ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Use caption if provided, otherwise use filename
        val body = caption.ifBlank { filename }
        
        // Build info map with optional thumbnail info
        val infoMap = mutableMapOf<String, Any>(
            "mimetype" to mimeType,
            "xyz.amorgan.blurhash" to blurHash,
            "w" to width,
            "h" to height,
            "size" to size
        )
        
        // Add thumbnail info if available
        if (thumbnailUrl != null) {
            infoMap["thumbnail_url"] = thumbnailUrl
            val thumbnailInfo = mutableMapOf<String, Any>()
            thumbnailWidth?.let { thumbnailInfo["w"] = it }
            thumbnailHeight?.let { thumbnailInfo["h"] = it }
            thumbnailMimeType?.let { thumbnailInfo["mimetype"] = it }
            if (thumbnailInfo.isNotEmpty()) {
                infoMap["thumbnail_info"] = thumbnailInfo
            }
        }
        
        val baseContent = mapOf(
            "msgtype" to msgType,
            "body" to body,
            "url" to mxcUrl,
            "info" to infoMap,
            "filename" to filename
        )
        
        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to "",
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with media data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Media message command sent with request_id: $messageRequestId")
    }
    
    /**
     * Convenience function to send an image message using upload result data
     */
    fun sendImageMessage(
        roomId: String,
        mxcUrl: String,
        width: Int,
        height: Int,
        size: Long,
        mimeType: String,
        blurHash: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) {
        // Extract filename from mxc URL (format: mxc://server/mediaId)
        val filename = mxcUrl.substringAfterLast("/").let { mediaId ->
            // Try to infer extension from mime type
            val extension = when {
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/gif") -> "gif"
                mimeType.startsWith("image/webp") -> "webp"
                else -> "jpg"
            }
            "image_$mediaId.$extension"
        }
        
        sendMediaMessage(
            roomId = roomId,
            mxcUrl = mxcUrl,
            filename = filename,
            mimeType = mimeType,
            width = width,
            height = height,
            size = size,
            blurHash = blurHash,
            caption = caption ?: "",
            msgType = "m.image",
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId,
            isThreadFallback = isThreadFallback,
            mentions = mentions,
            thumbnailUrl = thumbnailUrl,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight,
            thumbnailMimeType = thumbnailMimeType,
            thumbnailSize = thumbnailSize
        )
    }
    
    /**
     * Send a sticker message
     */
    fun sendStickerMessage(
        roomId: String,
        mxcUrl: String,
        body: String,
        mimeType: String,
        size: Long,
        width: Int = 0,
        height: Int = 0,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendStickerMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', body: '$body', width: $width, height: $height")
        
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        trackOutgoingRequest(messageRequestId, roomId)
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct ws.send()
        val baseContent = mutableMapOf<String, Any>(
            "msgtype" to "m.sticker",
            "body" to body,
            "url" to mxcUrl
        )
        val info = mutableMapOf<String, Any>(
            "mimetype" to mimeType,
            "size" to size
        )
        if (width > 0 && height > 0) {
            info["w"] = width
            info["h"] = height
        }
        baseContent["info"] = info
        
        val mentionsData = mapOf(
            "user_ids" to mentions,
            "room" to false
        )
        
        val dataMap = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "text" to "",
            "base_content" to baseContent,
            "mentions" to mentionsData,
            "url_previews" to emptyList<Any>()
        )
        
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            dataMap["relates_to"] = relatesTo
        }
        
        sendWebSocketCommand("send_message", messageRequestId, dataMap)
        
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sticker message send queued with request_id: $messageRequestId")
    }
    
    /**
     * Send a video message with thumbnail
     */
    fun sendVideoMessage(
        roomId: String,
        videoMxcUrl: String,
        thumbnailMxcUrl: String,
        width: Int,
        height: Int,
        duration: Int,
        size: Long,
        mimeType: String,
        thumbnailBlurHash: String,
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        thumbnailSize: Long,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendVideoMessage called with roomId: '$roomId', videoMxcUrl: '$videoMxcUrl'")
        
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Extract filename from mxc URL
        val filename = videoMxcUrl.substringAfterLast("/").let { mediaId ->
            val extension = when {
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/quicktime") -> "mov"
                mimeType.startsWith("video/webm") -> "webm"
                else -> "mp4"
            }
            "video_$mediaId.$extension"
        }
        
        // Use caption if provided, otherwise use filename
        val body = caption?.takeIf { it.isNotBlank() } ?: filename
        
        val baseContent = mapOf(
            "msgtype" to "m.video",
            "body" to body,
            "url" to videoMxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "thumbnail_info" to mapOf(
                    "mimetype" to "image/jpeg",
                    "xyz.amorgan.blurhash" to thumbnailBlurHash,
                    "w" to thumbnailWidth,
                    "h" to thumbnailHeight,
                    "size" to thumbnailSize
                ),
                "thumbnail_url" to thumbnailMxcUrl,
                "w" to width,
                "h" to height,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with video data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Video message command sent with request_id: $messageRequestId")
    }
    
    fun sendDelete(roomId: String, originalEvent: net.vrkknn.andromuks.TimelineEvent, reason: String = "") {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendDelete called with roomId: '$roomId', eventId: ${originalEvent.eventId}, reason: '$reason'")
        
        val deleteRequestId = requestIdCounter++
        
        // Track this outgoing request
        reactionRequests[deleteRequestId] = roomId
        
        val commandData = mutableMapOf(
            "room_id" to roomId,
            "event_id" to originalEvent.eventId
        )
        
        // Only add reason if it's not blank
        if (reason.isNotBlank()) {
            commandData["reason"] = reason
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: redact_event with data: $commandData")
        sendWebSocketCommand("redact_event", deleteRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Delete command sent with request_id: $deleteRequestId")
    }
    
    /**
     * Send an audio message
     */
    fun sendAudioMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        duration: Int, // duration in milliseconds
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendAudioMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', duration: ${duration}ms")
        
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        val baseContent = mapOf(
            "msgtype" to "m.audio",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with audio data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Audio message command sent with request_id: $messageRequestId")
    }
    
    /**
     * Send a file message
     */
    fun sendFileMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendFileMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', filename: '$filename'")
        
        val ws = WebSocketService.getWebSocket() ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        val baseContent = mapOf(
            "msgtype" to "m.file",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with file data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: File message command sent with request_id: $messageRequestId")
    }
    fun handleResponse(requestId: Int, data: Any) {
        // THREAD SAFETY: Create safe copies to avoid ConcurrentModificationException during logging
        val roomStateKeysSnapshot = synchronized(roomStateRequests) { roomStateRequests.keys.toList() }

        // PHASE 5.2/5.3: Acknowledge ALL commands with positive request_id when response arrives
        // Backend responds with same request_id, so we match by request_id and remove from queue
        // This ensures all commands are tracked and acknowledged properly
        var hasPendingOperation = false
        if (requestId > 0) {
            // Check if there's a pending operation for this request_id
            // If not, this might be a stale response or request_id collision
            val operation = synchronized(pendingOperationsLock) {
                pendingWebSocketOperations.find { op ->
                    (op.data["requestId"] as? Int) == requestId
                }
            }
            hasPendingOperation = operation != null
            handleMessageAcknowledgmentByRequestId(requestId)
        } else if (requestId == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Received response with request_id=0 (no acknowledgment needed)")
        }
        
        // CRITICAL FIX: If no pending operation was found and request_id > 0, check if it's in request maps
        // If it's in a request map, it's a valid response (operation might have been cleaned up already)
        // Only treat as stale if it's NOT in any request map
        if (requestId > 0 && !hasPendingOperation) {
            // Element Call widget responses are handled separately
            widgetCommandRequests.remove(requestId)?.let { deferred ->
                deferred.complete(data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget response received for requestId=$requestId")
                return
            }

            // Check if this request_id exists in any of our request maps - if so, it's valid (not stale)
            val isInRequestMap = profileRequests.containsKey(requestId) ||
                    timelineRequests.containsKey(requestId) ||
                    roomStateRequests.containsKey(requestId) ||
                    messageRequests.containsKey(requestId) ||
                    reactionRequests.containsKey(requestId) ||
                    markReadRequests.containsKey(requestId) ||
                    roomSummaryRequests.containsKey(requestId) ||
                    joinRoomRequests.containsKey(requestId) ||
                    leaveRoomRequests.containsKey(requestId) ||
                    fcmRegistrationRequests.containsKey(requestId) ||
                    eventRequests.containsKey(requestId) ||
                    freshnessCheckRequests.containsKey(requestId) ||
                    backgroundPrefetchRequests.containsKey(requestId) ||
                    paginateRequests.containsKey(requestId) ||
                    roomStateWithMembersRequests.containsKey(requestId) ||
                    userEncryptionInfoRequests.containsKey(requestId) ||
                    mutualRoomsRequests.containsKey(requestId) ||
                    trackDevicesRequests.containsKey(requestId) ||
                    resolveAliasRequests.containsKey(requestId) ||
                    getRoomSummaryRequests.containsKey(requestId) ||
                    joinRoomCallbacks.containsKey(requestId) ||
                    roomSpecificStateRequests.containsKey(requestId) ||
                    fullMemberListRequests.containsKey(requestId) ||
                    outgoingRequests.containsKey(requestId) ||
                    mentionsRequests.containsKey(requestId) ||
                    widgetCommandRequests.containsKey(requestId)
            
            // If it's NOT in any request map, it's truly stale - ignore it
            if (!isInRequestMap) {
                if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Ignoring stale response with requestId=$requestId (not in any request map and no pending operation)")
                return
            }
            // If it IS in a request map, it's valid - continue processing even though operation was cleaned up
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing response with requestId=$requestId (found in request map, operation may have been cleaned up)")
        }
        
        widgetCommandRequests.remove(requestId)?.let { deferred ->
            deferred.complete(data)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget response received for requestId=$requestId")
            return
        }
        
        if (profileRequests.containsKey(requestId)) {
            if (BuildConfig.DEBUG) {
                val userId = profileRequests[requestId]
                android.util.Log.d("Andromuks", "AppViewModel: Received profile response for requestId=$requestId, userId=$userId")
            }
            handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateRequests.containsKey(requestId)) {
            handleRoomStateResponse(requestId, data)
        } else if (messageRequests.containsKey(requestId)) {
            handleMessageResponse(requestId, data)
        } else if (reactionRequests.containsKey(requestId)) {
            handleReactionResponse(requestId, data)
        } else if (markReadRequests.containsKey(requestId)) {
            // Handle mark_read response - data should be a boolean
            val success = data as? Boolean ?: false
            handleMarkReadResponse(requestId, success)
        } else if (roomSummaryRequests.containsKey(requestId)) {
            handleRoomSummaryResponse(requestId, data)
        } else if (joinRoomRequests.containsKey(requestId)) {
            handleJoinRoomResponse(requestId, data)
        } else if (leaveRoomRequests.containsKey(requestId)) {
            handleLeaveRoomResponse(requestId, data)
        } else if (fcmRegistrationRequests.containsKey(requestId)) {
            handleFCMRegistrationResponse(requestId, data)
        } else if (eventRequests.containsKey(requestId)) {
            handleEventResponse(requestId, data)
        } else if (freshnessCheckRequests.containsKey(requestId)) {
            handleFreshnessCheckResponse(requestId, data)
        } else if (backgroundPrefetchRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (paginateRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateWithMembersRequests.containsKey(requestId)) {
            handleRoomStateWithMembersResponse(requestId, data)
        } else if (userEncryptionInfoRequests.containsKey(requestId)) {
            handleUserEncryptionInfoResponse(requestId, data)
        } else if (mutualRoomsRequests.containsKey(requestId)) {
            handleMutualRoomsResponse(requestId, data)
        } else if (trackDevicesRequests.containsKey(requestId)) {
            handleTrackDevicesResponse(requestId, data)
        } else if (resolveAliasRequests.containsKey(requestId)) {
            handleResolveAliasResponse(requestId, data)
        } else if (getRoomSummaryRequests.containsKey(requestId)) {
            handleGetRoomSummaryResponse(requestId, data)
        } else if (joinRoomCallbacks.containsKey(requestId)) {
            handleJoinRoomCallbackResponse(requestId, data)
        } else if (profileRequests.containsKey(requestId)) {
            handleOnDemandProfileResponse(requestId, data)
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            handleRoomSpecificStateResponse(requestId, data)
        } else if (fullMemberListRequests.containsKey(requestId)) {
            handleFullMemberListResponse(requestId, data)
        } else if (outgoingRequests.containsKey(requestId)) {
            handleOutgoingRequestResponse(requestId, data)
        } else if (mentionsRequests.containsKey(requestId)) {
            handleMentionsListResponse(requestId, data)
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unknown response requestId=$requestId")
        }
    }
    
    fun handleError(requestId: Int, errorMessage: String) {
        // PHASE 5.2/5.3: Acknowledge command even on error (remove from pending queue)
        // Error responses still mean the server received and processed the request
        // Backend responds with same request_id even for errors, so we acknowledge by request_id
        if (requestId > 0) {
            android.util.Log.w("Andromuks", "AppViewModel: PHASE 5.3 - Error response received for request_id=$requestId: $errorMessage (acknowledging command)")
            handleMessageAcknowledgmentByRequestId(requestId)
        } else if (requestId == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Received error with request_id=0 (no acknowledgment needed)")
        }

        widgetCommandRequests.remove(requestId)?.let { deferred ->
            if (errorMessage.contains("M_NOT_FOUND", ignoreCase = true) &&
                errorMessage.contains("Delayed event not found", ignoreCase = true)
            ) {
                deferred.complete(org.json.JSONObject())
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Widget delayed event missing for requestId=$requestId, returning empty response"
                    )
                }
                return
            }
            deferred.completeExceptionally(IllegalStateException(errorMessage))
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget error received for requestId=$requestId")
            return
        }
        
        if (profileRequests.containsKey(requestId)) {
            handleProfileError(requestId, errorMessage)
        } else if (messageRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Message send error for requestId=$requestId: $errorMessage")
            val roomId = messageRequests.remove(requestId)
            if (pendingSendCount > 0) {
                pendingSendCount--
            }
            // Invoke completion callback to prevent UI stalling
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        } else if (markReadRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mark read error for requestId=$requestId: $errorMessage")
            // Remove the failed request from pending
            markReadRequests.remove(requestId)
            // Invoke completion callback to prevent UI stalling
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        } else if (roomStateRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room state error for requestId=$requestId: $errorMessage")
            val roomId = roomStateRequests.remove(requestId)
            // PERFORMANCE: Remove from pending requests set on error
            if (roomId != null) {
                pendingRoomStateRequests.remove(roomId)
            }
        } else if (roomSummaryRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room summary error for requestId=$requestId: $errorMessage")
            roomSummaryRequests.remove(requestId)
        } else if (joinRoomRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Join room error for requestId=$requestId: $errorMessage")
            joinRoomRequests.remove(requestId)
        } else if (leaveRoomRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Leave room error for requestId=$requestId: $errorMessage")
            leaveRoomRequests.remove(requestId)
        } else if (outgoingRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Outgoing request error for requestId=$requestId: $errorMessage")
            outgoingRequests.remove(requestId)
        } else if (eventRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Event request error for requestId=$requestId: $errorMessage")
            val (_, callback) = eventRequests.remove(requestId) ?: return
            callback(null)
        } else if (mentionsRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mentions request error for requestId=$requestId: $errorMessage")
            mentionsRequests.remove(requestId)
            isMentionsLoading = false
            mentionEvents = emptyList()
        } else if (mutualRoomsRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mutual rooms error for requestId=$requestId: $errorMessage")
            val callback = mutualRoomsRequests.remove(requestId) ?: return
            // Call callback with empty list instead of error
            callback(emptyList(), null)
        } else if (userEncryptionInfoRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: User encryption info error for requestId=$requestId: $errorMessage")
            val callback = userEncryptionInfoRequests.remove(requestId) ?: return
            callback(null, errorMessage)
        } else if (trackDevicesRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Track devices error for requestId=$requestId: $errorMessage")
            val callback = trackDevicesRequests.remove(requestId) ?: return
            callback(null, errorMessage)
        } else if (resolveAliasRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Resolve alias error for requestId=$requestId: $errorMessage")
            val callback = resolveAliasRequests.remove(requestId) ?: return
            callback(null)
        } else if (getRoomSummaryRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Get room summary error for requestId=$requestId: $errorMessage")
            val callback = getRoomSummaryRequests.remove(requestId) ?: return
            callback(Pair(null, errorMessage))
        } else if (joinRoomCallbacks.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Join room error for requestId=$requestId: $errorMessage")
            val callback = joinRoomCallbacks.remove(requestId) ?: return
            callback(Pair(null, errorMessage))
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room specific state error for requestId=$requestId: $errorMessage")
            roomSpecificStateRequests.remove(requestId)
        } else if (fullMemberListRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Full member list error for requestId=$requestId: $errorMessage")
            val roomId = fullMemberListRequests.remove(requestId)
            // PERFORMANCE: Remove from pending requests set on error
            if (roomId != null) {
                pendingFullMemberListRequests.remove(roomId)
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Unknown error requestId=$requestId: $errorMessage")
        }
    }
    private fun handleProfileError(requestId: Int, errorMessage: String) {
        val userId = profileRequests.remove(requestId) ?: return
        val requestingRoomId = profileRequestRooms.remove(requestId)
        
        // PERFORMANCE: Remove from pending requests set even on error
        pendingProfileRequests.remove(userId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile lookup failed for $userId: $errorMessage")
        
        // CRITICAL FIX: Before applying low-quality fallback (username only),
        // check if we ALREADY have a valid profile from room state (e.g. from get_specific_room_state)
        val existingProfile = getUserProfile(userId, requestingRoomId)
        if (existingProfile != null && !existingProfile.displayName.isNullOrBlank()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Global profile lookup failed for $userId, but we already have a profile from room state. Skipping fallback.")
            return
        }
        
        // If no profile found anywhere, use username part of Matrix ID as last resort
        val username = userId.removePrefix("@").substringBefore(":")
        val memberProfile = MemberProfile(username, null)
        
        // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
        // This is a fallback profile (username only), so it should be set as global
        if (requestingRoomId != null) {
            storeMemberProfile(requestingRoomId, userId, memberProfile)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added fallback profile for $userId to room $requestingRoomId")
        }
        
        // Also update member cache for all rooms that already contain this user
        RoomMemberCache.getAllMembers().forEach { (roomId, memberMap) ->
            val existingMembers = RoomMemberCache.getRoomMembers(roomId)
            if (existingMembers.containsKey(userId)) {
                storeMemberProfile(roomId, userId, memberProfile)
                RoomMemberCache.updateMember(roomId, userId, memberProfile)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated member cache with username '$username' for $userId in room $roomId")
            }
        }
        
        // SYNC OPTIMIZATION: Schedule member update instead of immediate counter increment
        needsMemberUpdate = true
        scheduleUIUpdate("member")
    }
    
    private fun handleProfileResponse(requestId: Int, data: Any) {
        val userId = profileRequests.remove(requestId)
        if (userId == null) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: handleProfileResponse called for unknown requestId=$requestId")
            return
        }
        val requestingRoomId = profileRequestRooms.remove(requestId)
        
        // PERFORMANCE: Remove from pending requests set
        pendingProfileRequests.remove(userId)
        val obj = data as? JSONObject ?: return
        val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val display = obj.optString("displayname")?.takeIf { it.isNotBlank() }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleProfileResponse processing - userId: $userId, displayName: $display, avatarUrl: $avatar")
        
        val memberProfile = MemberProfile(display, avatar)
        
        // Update global profile (canonical profile from explicit request)
        // This will automatically clean up room-specific entries that now match global
        updateGlobalProfile(userId, memberProfile)
        
        // Update singleton cache
        if (requestingRoomId != null) {
            RoomMemberCache.updateMember(requestingRoomId, userId, memberProfile)
        }
        
        // Also update singleton cache for all rooms that already contain this user
        val allRooms = RoomMemberCache.getAllMembers()
        allRooms.forEach { (roomId, memberMap) ->
            if (memberMap.containsKey(userId)) {
                RoomMemberCache.updateMember(roomId, userId, memberProfile)
            }
        }
        
        if (userId == currentUserId) {
            currentUserProfile = UserProfile(userId = userId, displayName = display, avatarUrl = avatar)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated currentUserProfile - userId: $userId, displayName: $display, avatarUrl: $avatar")
        }
        
        // Save profile to disk cache for persistence
        // Use batch save queue to avoid blocking and improve performance
        queueProfileForBatchSave(userId, memberProfile)
        
        // Check if this is part of a full user info request
        val fullUserInfoCallback = fullUserInfoCallbacks.remove(requestId)
        if (fullUserInfoCallback != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invoking full user info callback for profile (requestId: $requestId, userId: $userId)")
            fullUserInfoCallback(obj)
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile response received but no full user info callback found (requestId: $requestId)")
        }
        
        // SYNC OPTIMIZATION: Schedule member update instead of immediate counter increment
        needsMemberUpdate = true
        scheduleUIUpdate("member")
    }
    
    // Profile management - in-memory only, loaded opportunistically when rendering events
    private val pendingProfileSaves = mutableMapOf<String, MemberProfile>()
    private var profileSaveJob: kotlinx.coroutines.Job? = null
    private var syncIngestor: net.vrkknn.andromuks.database.SyncIngestor? = null

    /**
     * Ensures SyncIngestor is initialized with the LRU cache listener.
     */
    private fun ensureSyncIngestor(): net.vrkknn.andromuks.database.SyncIngestor? {
        val context = appContext ?: return null
        if (syncIngestor == null) {
            syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context).apply {
                cacheUpdateListener = object : net.vrkknn.andromuks.database.SyncIngestor.CacheUpdateListener {
                    override fun getCachedRoomIds(): Set<String> = this@AppViewModel.getCachedRoomIds()
                    
                    override fun onEventsForCachedRoom(roomId: String, events: List<TimelineEvent>, requiresFullRerender: Boolean): Boolean {
                        return if (requiresFullRerender) {
                            invalidateCachedRoom(roomId)
                            false
                        } else {
                            appendEventsToCachedRoom(roomId, events)
                        }
                    }
                }
            }
        }
        return syncIngestor
    }

    /**
     * Manages global profile cache size to prevent memory issues.
     */
    private fun manageGlobalCacheSize() {
        if (ProfileCache.getGlobalCacheSize() > 1000) {
            // Use ProfileCache cleanup method
            ProfileCache.cleanupGlobalProfiles(500) // Keep only 500 most recent
            android.util.Log.w("Andromuks", "AppViewModel: Cleaned up global cache to prevent memory issues")
        }
    }
    
    /**
     * Manages room member cache size to prevent memory issues.
     */
    private fun manageRoomMemberCacheSize(roomId: String) {
        val memberMap = RoomMemberCache.getRoomMembers(roomId)
        if (memberMap.size > 500) {
            // Clear oldest entries to make room (take first 250)
            val keysToRemove = memberMap.keys.take(250)
            keysToRemove.forEach { userId ->
                RoomMemberCache.removeMember(roomId, userId)
            }
            android.util.Log.w("Andromuks", "AppViewModel: Cleared ${keysToRemove.size} old entries from room $roomId cache")
        }
    }
    
    /**
     * Manages flattened member cache size to prevent memory issues.
     */
    private fun manageFlattenedMemberCacheSize() {
        if (ProfileCache.getFlattenedCacheSize() > 2000) {
            // Clear oldest entries to make room
            ProfileCache.cleanupFlattenedProfiles(1000) // Keep only 1000 most recent
            android.util.Log.w("Andromuks", "AppViewModel: Cleaned up flattened cache to prevent memory issues")
        }
    }
    
    /**
     * Queues a profile for batch processing. This prevents blocking the main thread
     * when processing large member lists.
     */
    private fun queueProfileForBatchSave(userId: String, profile: MemberProfile) {
        synchronized(pendingProfileSaves) {
            // Aggressive memory management: Much smaller batch size to prevent OOM
            if (pendingProfileSaves.size >= 50) {
                android.util.Log.w("Andromuks", "AppViewModel: Too many pending profile saves (${pendingProfileSaves.size}), forcing immediate save")
                viewModelScope.launch(Dispatchers.IO) {
                    performBatchProfileSave()
                }
            }
            
            pendingProfileSaves[userId] = profile
            
            // Start batch save job if not already running
            if (profileSaveJob?.isActive != true) {
                profileSaveJob = viewModelScope.launch(Dispatchers.IO) {
                    // Much shorter delay to save more frequently
                    delay(50)
                    performBatchProfileSave()
                }
            }
        }
    }
    
    /**
     * Performs batch processing of queued profiles (in-memory only).
     */
    private suspend fun performBatchProfileSave() {
        val profilesToSave = synchronized(pendingProfileSaves) {
            if (pendingProfileSaves.isEmpty()) return
            val profiles = pendingProfileSaves.toMap()
            pendingProfileSaves.clear()
            profiles
        }
        
        if (profilesToSave.isEmpty()) return
        
        val startTime = System.currentTimeMillis()
        
        // Profiles are cached in-memory only - no DB persistence needed
        // Profiles are loaded opportunistically when rendering events via requestUserProfileOnDemand()
        if (BuildConfig.DEBUG) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "AppViewModel: Processed ${profilesToSave.size} profiles (in-memory only) in ${duration}ms")
        }
    }
    
    /**
     * Ensures current user profile is loaded if available.
     */
    fun loadCachedProfiles(context: android.content.Context) {
        try {
            // CRITICAL FIX: Ensure appContext is set before loading profiles
            if (appContext == null) {
                appContext = context.applicationContext
            }
            
            // Get current user ID from SharedPreferences if not already set
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val storedUserId = sharedPrefs.getString("current_user_id", "") ?: ""
            if (currentUserId.isBlank() && storedUserId.isNotBlank()) {
                currentUserId = storedUserId
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored currentUserId from SharedPreferences: $currentUserId")
            }
            
            // Profiles are cached in-memory only - no DB loading needed
            // Profiles are loaded opportunistically when rendering events via requestUserProfileOnDemand()
            // If current user profile is needed, request it from the backend
            if (currentUserProfile == null && currentUserId.isNotBlank()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting current user profile from server - userId: $currentUserId")
                requestUserProfile(currentUserId)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load cached profiles", e)
        }
    }
    
    private fun handleOutgoingRequestResponse(requestId: Int, data: Any) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleOutgoingRequestResponse called with requestId=$requestId")
        val roomId = outgoingRequests.remove(requestId)
        if (roomId == null && BuildConfig.DEBUG) {
            android.util.Log.w("Andromuks", "AppViewModel: No roomId found for outgoing request $requestId")
        }
    }
    
    /**
     * PHASE 5.3: Handle send_complete to track Matrix server delivery confirmation
     * send_complete has negative request_id (spontaneous from server)
     * Matches to original message by transaction_id stored in operation data
     */
    /**
     * Process all pending notification messages from FIFO buffer
     * Called when WebSocket becomes healthy (after init_complete)
     * Processes messages in order with delays between them
     */
    private fun processPendingNotificationMessages() {
        if (!isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket not healthy, skipping pending notification messages processing")
            return
        }
        
        val messageCount = synchronized(pendingNotificationMessagesLock) {
            pendingNotificationMessages.size
        }
        
        if (messageCount == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending notification messages to process")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing $messageCount pending notification messages from FIFO buffer")
        
        // Process messages sequentially with delays
        viewModelScope.launch(Dispatchers.IO) {
            var processed = 0
            while (true) {
                if (!isWebSocketHealthy()) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: WebSocket became unhealthy during processing, stopping (processed $processed/$messageCount)")
                    break
                }
                
                val hasMore = synchronized(pendingNotificationMessagesLock) {
                    pendingNotificationMessages.isNotEmpty()
                }
                
                if (!hasMore) {
                    break
                }
                
                // Process next message
                withContext(Dispatchers.Main) {
                    processNextPendingNotificationMessage()
                }
                
                processed++
                
                // Delay between messages for easier processing (500ms)
                if (processed < messageCount) {
                    delay(500L)
                }
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Finished processing pending notification messages ($processed/$messageCount processed)")
        }
    }
    
    fun handleSendComplete(eventData: JSONObject, error: String?) {
        val transactionId = eventData.optString("transaction_id", "")
        if (transactionId.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - send_complete has no transaction_id, cannot match to original message")
            return
        }
        
        // Messages are already removed from buffer when sent, so no cleanup needed here
        
        // Find operation by transaction_id (stored when response was received)
        val operation = pendingWebSocketOperations.find { 
            val opTransactionId = it.data["transaction_id"] as? String
            opTransactionId == transactionId
        }
        
        if (operation != null) {
            val command = operation.data["command"] as? String ?: operation.type.removePrefix("command_")
            if (error != null && error.isNotEmpty()) {
                android.util.Log.e("Andromuks", "AppViewModel: PHASE 5.3 - Matrix server error for transaction_id=$transactionId, command=$command: $error")
                logActivity("Matrix Server Error - $command: $error", null)
            } else {
                android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.3 - Matrix server confirmed delivery for transaction_id=$transactionId, command=$command")
                logActivity("Matrix Server Confirmed - $command", null)
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - No pending operation found for transaction_id=$transactionId (may have been already acknowledged)")
        }

        // Local echo handling removed; real events will arrive via sync.
    }

    fun processSendCompleteEvent(eventData: JSONObject) {
        android.util.Log.d("Andromuks", "AppViewModel: processSendCompleteEvent called")
        try {
            val event = TimelineEvent.fromJson(eventData)
            android.util.Log.d("Andromuks", "AppViewModel: Created timeline event from send_complete: ${event.eventId}, type=${event.type}, eventRoomId=${event.roomId}, sender=${event.sender}, currentRoomId=$currentRoomId, currentUserId=$currentUserId")
            
            // SHORTCUT OPTIMIZATION: Update shortcut for this room when user sends a message
            // This drastically reduces shortcut updates - only when user actively sends messages
            // Update shortcuts BEFORE room check so it works for any room, not just current room
            if ((event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") 
                && event.sender == currentUserId && event.roomId.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Message sent by user, preparing shortcut update for room ${event.roomId}")
                
                // Store conversationsApi in local variable to avoid smart cast issue
                val api = conversationsApi
                if (api == null) {
                    android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - conversationsApi is null, cannot update shortcut")
                } else {
                    // Get room from roomMap (should always exist when sending message)
                    var room = roomMap[event.roomId] ?: getRoomById(event.roomId)
                    
                    if (room != null) {
                        // Room found - update timestamp to current message timestamp
                        val updatedTimestamp = normalizeTimestamp(event.timestamp, event.unsigned?.optLong("age_ts") ?: 0L)
                        room = room.copy(sortingTimestamp = updatedTimestamp)
                        android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Room found, updating shortcut for ${room.name} with timestamp $updatedTimestamp")
                        
                        // Update shortcuts asynchronously
                        viewModelScope.launch(Dispatchers.Default) {
                            api.updateShortcutsFromSyncRooms(listOf(room))
                            android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Successfully updated shortcut for room ${room.name} after sending message")
                        }
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - Room ${event.roomId} not found in roomMap or getRoomById, cannot update shortcut")
                    }
                }
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Skipping shortcut update: type=${event.type}, sender=${event.sender}, currentUserId=$currentUserId, roomId=${event.roomId}")
            }
            
            // Only process timeline updates if it's for the current room
            if (event.roomId != currentRoomId) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: send_complete for different room (${event.roomId}), ignoring timeline update")
                return
            }
            
            if (event.type == "m.reaction") {
                // Process reaction events to update messageReactions instead of adding to timeline
                val relatesTo = event.content?.optJSONObject("m.relates_to")
                val emoji = relatesTo?.optString("key", "") ?: ""
                val relatesToEventId = relatesTo?.optString("event_id", "") ?: ""
                
                if (emoji.isNotBlank() && relatesToEventId.isNotBlank()) {
                    // Skip processing our own reactions from send_complete since sync_complete will handle them
                    // This prevents the double processing that causes the toggle behavior
                    if (event.sender == currentUserId) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping send_complete reaction from ourself (will be processed by sync_complete): $emoji from ${event.sender} to $relatesToEventId")
                    } else {
                        // Process reactions from other users in send_complete
                        val reactionEvent = ReactionEvent(
                            roomId = event.roomId,
                            eventId = event.eventId,
                            sender = event.sender,
                            emoji = emoji,
                            relatesToEventId = relatesToEventId,
                            timestamp = normalizeTimestamp(
                                event.timestamp,
                                event.unsigned?.optLong("age_ts") ?: 0L
                            )
                        )
                        processReactionEvent(reactionEvent)
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processed send_complete reaction from other user: $emoji from ${event.sender} to $relatesToEventId")
                    }
                }
            } else if (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") {
                // Check if this is an edit event
                val isEditEvent = when {
                    event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    else -> false
                }
                
                if (isEditEvent) {
                    // Handle edit via chain system
                    handleEditEventInChain(event)
                    buildTimelineFromChain() // Rebuild to show edit
                } else {
                    // Add regular event to chain (with deduplication)
                    addNewEventToChain(event)
                    buildTimelineFromChain() // Rebuild timeline to include new event
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing send_complete event", e)
        }
    }
    
    fun addTimelineEvent(event: TimelineEvent) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: addTimelineEvent called for event: ${event.eventId}, type=${event.type}, roomId=${event.roomId}, currentRoomId=$currentRoomId")
        
        // Only add to timeline if it's for the current room
        if (event.roomId == currentRoomId) {
            val currentEvents = timelineEvents.toMutableList()
            currentEvents.add(event)
            timelineEvents = currentEvents.sortedBy { it.timestamp }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added event to timeline, total events: ${timelineEvents.size}")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Event roomId (${event.roomId}) doesn't match currentRoomId ($currentRoomId), not adding to timeline")
        }
    }
    
    /**
     * Handle freshness check response (single-event paginate with limit=1)
     * This intercepts the response before SyncIngestor processes it
     * Compares the latest event's timeline_rowid with what we have in cache/DB
     * If newer, triggers a full paginate; otherwise continues normally
     */
    private fun handleFreshnessCheckResponse(requestId: Int, data: Any) {
        val roomId = freshnessCheckRequests.remove(requestId) ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Freshness check response for unknown requestId: $requestId")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling freshness check response for room: $roomId, requestId: $requestId")
        
        try {
            // Parse the single event from response
            val eventsArray = when (data) {
                is org.json.JSONArray -> data
                is org.json.JSONObject -> {
                    val timeline = data.optJSONArray("timeline")
                    if (timeline != null) timeline else org.json.JSONArray().apply { put(data) }
                }
                else -> {
                    android.util.Log.w("Andromuks", "AppViewModel: Unexpected data type in freshness check response: ${data::class.java.simpleName}")
                    return
                }
            }
            
            if (eventsArray.length() == 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check returned no events for $roomId - treating as up to date")
                return
            }
            
            // Get the latest event from response (should be only one)
            val latestEventJson = eventsArray.optJSONObject(eventsArray.length() - 1) ?: run {
                android.util.Log.w("Andromuks", "AppViewModel: Could not parse latest event from freshness check response")
                return
            }
            
            val latestEvent = TimelineEvent.fromJson(latestEventJson)
            val serverLatestRowId = latestEvent.timelineRowid
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check - server latest event: ${latestEvent.eventId}, timeline_rowid: $serverLatestRowId")
            
            // Get our latest timeline_rowid from RAM cache
            val cachedMetadata = RoomTimelineCache.getLatestCachedEventMetadata(roomId)
            val cachedLatestRowId = cachedMetadata?.timelineRowId ?: 0L
            
            // Process asynchronously to check DB if needed
            viewModelScope.launch {
                val ourLatestRowId = cachedLatestRowId
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check - our latest timeline_rowid: $ourLatestRowId (RAM), server: $serverLatestRowId")
                
                // Compare: if server has newer events, trigger full paginate
                if (serverLatestRowId > ourLatestRowId) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Server has newer events (server: $serverLatestRowId > ours: $ourLatestRowId), triggering full paginate for $roomId")
                    
                    // Wait for WebSocket to be ready
                    var waitCount = 0
                    val maxWaitAttempts = 50 // Wait up to 5 seconds
                    while (!isWebSocketConnected() && waitCount < maxWaitAttempts) {
                        kotlinx.coroutines.delay(100)
                        waitCount++
                    }
                    
                    if (isWebSocketConnected()) {
                        // Trigger full paginate with max_timeline_id = our latest rowId
                        val paginateRequestId = requestIdCounter++
                        backgroundPrefetchRequests[paginateRequestId] = roomId
                        
                        val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                            "room_id" to roomId,
                            "max_timeline_id" to ourLatestRowId,
                            "limit" to 100,
                            "reset" to false
                        ))
                        
                        if (result == WebSocketResult.SUCCESS) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent full paginate request after freshness check for $roomId (max_timeline_id: $ourLatestRowId)")
                            markInitialPaginate(roomId, "freshness_check_newer")
                        } else {
                            android.util.Log.w("Andromuks", "AppViewModel: Failed to send paginate after freshness check for $roomId: $result")
                            backgroundPrefetchRequests.remove(paginateRequestId)
                        }
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot fetch fresh data for $roomId")
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: We are up to date (server: $serverLatestRowId <= ours: $ourLatestRowId), no paginate needed for $roomId")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error handling freshness check response for $roomId", e)
        }
    }
    
    fun handleTimelineResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: START - requestId=$requestId, dataType=${data::class.java.simpleName}, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading")
        
        // Determine request type and get room ID
        val roomId = timelineRequests[requestId] ?: paginateRequests[requestId] ?: backgroundPrefetchRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "🟡 handleTimelineResponse: UNKNOWN requestId - requestId=$requestId, timelineRequests=${timelineRequests.keys}, paginateRequests=${paginateRequests.keys}, backgroundPrefetchRequests=${backgroundPrefetchRequests.keys}")
            return
        }

        val isPaginateRequest = paginateRequests.containsKey(requestId)
        val isBackgroundPrefetchRequest = backgroundPrefetchRequests.containsKey(requestId)
        android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: Processing - roomId=$roomId, requestId=$requestId, isPaginate=$isPaginateRequest, isBackgroundPrefetch=$isBackgroundPrefetchRequest, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading")

        // CRITICAL FIX: Parse has_more field BEFORE processing events, so we have it even if events array is empty
        var hasMoreFromResponse: Boolean? = null
        if (data is JSONObject && isPaginateRequest) {
            hasMoreFromResponse = data.optBoolean("has_more", true) // Default to true if not present
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsed has_more=$hasMoreFromResponse from response BEFORE processing events")
        }

        var totalReactionsProcessed = 0
        
        // Process events array - main event processing logic
        fun processEventsArray(eventsArray: JSONArray): Int {
            val eventCount = eventsArray.length()
            android.util.Log.d("Andromuks", "🟡 processEventsArray: START - roomId=$roomId, requestId=$requestId, eventCount=$eventCount, isPaginate=$isPaginateRequest, isBackgroundPrefetch=$isBackgroundPrefetchRequest, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading")
            if (eventCount == 0) {
                android.util.Log.w("Andromuks", "🟡 processEventsArray: EMPTY response - roomId=$roomId, requestId=$requestId, isPaginate=$isPaginateRequest")
            }
            val timelineList = mutableListOf<TimelineEvent>()
            val allEvents = mutableListOf<TimelineEvent>()  // For version processing
            val memberMap = RoomMemberCache.getRoomMembers(roomId)
            
            var ownMessageCount = 0
            var reactionProcessedCount = 0
            var filteredByRowId = 0
            var filteredByType = 0
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.optJSONObject(i)
                if (eventJson != null) {
                    val event = TimelineEvent.fromJson(eventJson)
                    allEvents.add(event)  // Collect all events for version processing
                    
                    // Track our own messages
                    if (event.sender == currentUserId && (event.type == "m.room.message" || event.type == "m.room.encrypted")) {
                        ownMessageCount++
                        val bodyPreview = when {
                            event.type == "m.room.message" -> event.content?.optString("body", "")?.take(50)
                            event.type == "m.room.encrypted" -> event.decrypted?.optString("body", "")?.take(50)
                            else -> ""
                        }
                        //android.util.Log.d("Andromuks", "AppViewModel: [PAGINATE] ★ Found OUR message in paginate response: ${event.eventId} body='$bodyPreview' timelineRowid=${event.timelineRowid}")
                    }
                    
                    // Process member events using helper function
                    if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                        val mutableMemberMap = memberMap.toMutableMap()
                        processMemberEvent(event, mutableMemberMap)
                        // Update singleton cache with changes
                        mutableMemberMap.forEach { (userId, profile) ->
                            RoomMemberCache.updateMember(roomId, userId, profile)
                        }
                    } else {
                        // Process reaction events using helper function
                        if (event.type == "m.reaction") {
                            if (processReactionFromTimeline(event)) {
                                reactionProcessedCount++
                            }
                            filteredByType++
                        } else {
                            // Define allowed event types that should appear in timeline
                            // These match the allowedEventTypes in RoomTimelineScreen and BubbleTimelineScreen
                            val allowedEventTypes = setOf(
                                "m.room.message",
                                "m.room.encrypted",
                                "m.room.member",
                                "m.room.name",
                                "m.room.topic",
                                "m.room.avatar",
                                "m.room.pinned_events",
                                "m.sticker"
                            )
                            
                            // Check if this is a kick (leave event where sender != state_key)
                            // Kicks should appear in timeline even with negative timelineRowid
                            // Note: Member events with timelineRowid == -1 are processed separately above (line 12562)
                            val isKick = event.type == "m.room.member" && 
                                        event.timelineRowid < 0 && 
                                        event.stateKey != null &&
                                        event.sender != event.stateKey &&
                                        event.content?.optString("membership") == "leave"
                            
                            // Filtering logic:
                            // 1. Allow all allowed event types regardless of timelineRowid
                            //    (timelineRowid can be negative for many valid timeline events, including messages)
                            // 2. For member events with negative timelineRowid, only allow kicks
                            //    (member events with timelineRowid >= 0 are always allowed)
                            // 3. Messages, encrypted messages, stickers, and system events are always allowed
                            //    regardless of timelineRowid (they can have timelineRowid == -1 in some cases)
                            val shouldAdd = when {
                                allowedEventTypes.contains(event.type) -> {
                                    // For member events with negative timelineRowid, only allow kicks
                                    // All other allowed event types (messages, system events, etc.) are allowed
                                    // even with negative timelineRowid
                                    if (event.type == "m.room.member" && event.timelineRowid < 0) {
                                        isKick
                                    } else {
                                        true
                                    }
                                }
                                else -> false
                            }
                            
                            if (shouldAdd) {
                                timelineList.add(event)
                            } else {
                                filteredByRowId++
                                if (BuildConfig.DEBUG && filteredByRowId <= 5) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Filtered event ${event.eventId} type=${event.type} timelineRowid=${event.timelineRowid}")
                                }
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}, ownMessages=$ownMessageCount, reactions=$reactionProcessedCount, filteredByRowId=$filteredByRowId, filteredByType=$filteredByType")
            if (ownMessageCount > 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ★★★ PAGINATE RESPONSE CONTAINS $ownMessageCount OF YOUR OWN MESSAGES ★★★")
            }
            if (reactionProcessedCount > 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ★★★ PROCESSED $reactionProcessedCount REACTIONS FROM PAGINATE RESPONSE ★★★")
            }
            
            // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${allEvents.size} events for version tracking")
            processVersionedMessages(allEvents)
            
            // Handle empty pagination responses
            // Only mark as "no more messages" if backend actually returned 0 events OR fewer events than requested
            // Don't mark as empty if events were filtered out (e.g., all reactions/state events)
            val totalEventsReturned = eventsArray.length()
            // CRITICAL FIX: When timelineList is empty, use has_more field to determine if more messages available
            // Don't set hasMoreMessages = false unless backend explicitly says has_more = false
            if (timelineList.isEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: ========================================")
                android.util.Log.w("Andromuks", "AppViewModel: ⚠️ EMPTY PAGINATION RESPONSE (requestId: $requestId)")
                android.util.Log.w("Andromuks", "AppViewModel: Backend returned $totalEventsReturned events, timeline events: ${timelineList.size} (filtered: rowId=$filteredByRowId, type=$filteredByType)")
                
                // Use has_more field if available, otherwise keep current state
                if (hasMoreFromResponse != null) {
                    hasMoreMessages = hasMoreFromResponse
                    android.util.Log.w("Andromuks", "AppViewModel: Setting hasMoreMessages=${hasMoreFromResponse} based on has_more field from response")
                    if (!hasMoreFromResponse) {
                        android.util.Log.w("Andromuks", "AppViewModel: 🏁 REACHED END OF MESSAGE HISTORY (has_more=false, empty response)")
                        appContext?.let { context ->
                            android.widget.Toast.makeText(context, "No more messages available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: No has_more field in response, keeping current hasMoreMessages state")
                }
                
                android.util.Log.w("Andromuks", "AppViewModel: ========================================")
                
                // Clean up request tracking
                paginateRequests.remove(requestId)
                paginateRequestMaxTimelineIds.remove(requestId)
                backgroundPrefetchRequests.remove(requestId)
                isPaginating = false
                android.util.Log.w("Andromuks", "🟡 processEventsArray: EMPTY response handled - roomId=$roomId, requestId=$requestId, returning early, isTimelineLoading=$isTimelineLoading")
                return reactionProcessedCount
            }
            
            if (timelineList.isNotEmpty()) {
                // Handle background prefetch requests first - before any UI processing
                if (backgroundPrefetchRequests.containsKey(requestId)) {
                    return handleBackgroundPrefetch(roomId, timelineList)
                }

                // Track oldest timelineRowId for initial paginate (when opening room)
                // This will be used for the first pull-to-refresh
                // The oldest event has the lowest (smallest) timelineRowId (always positive)
                if (!paginateRequests.containsKey(requestId)) {
                    val oldestInResponse = timelineList.minOfOrNull { it.timelineRowid }
                    if (oldestInResponse != null) {
                        // All timelineRowIds should be positive - 0 is a bug
                        if (oldestInResponse == 0L) {
                            android.util.Log.e("Andromuks", "AppViewModel: ⚠️ BUG: Initial paginate contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!")
                        } else if (oldestInResponse < 0) {
                            android.util.Log.e("Andromuks", "AppViewModel: ⚠️ BUG: Initial paginate contains negative timelineRowId=$oldestInResponse for room $roomId. TimelineRowId should always be positive!")
                        } else {
                            // Positive value is correct - store it for pull-to-refresh
                            oldestRowIdPerRoom[roomId] = oldestInResponse
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracked oldest timelineRowId=$oldestInResponse for room $roomId from initial paginate (${timelineList.size} events)")
                        }
                    }
                } else {
                    // For pagination requests, log the row ID range of returned events
                    val minRowId = timelineList.minByOrNull { it.timelineRowid }?.timelineRowid
                    val maxRowId = timelineList.maxByOrNull { it.timelineRowid }?.timelineRowid
                    val cacheBefore = RoomTimelineCache.getCachedEventCount(roomId)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pagination response - received ${timelineList.size} events with rowId range: $minRowId to $maxRowId, cache before: $cacheBefore")
                }
                
                // Populate edit chain mapping for clean edit handling using helper function
                // CRITICAL FIX: For pagination, merge events instead of clearing to preserve newer events
                val isPaginationRequest = paginateRequests.containsKey(requestId)
                val isInitialPaginate = timelineRequests.containsKey(requestId)
                // CRITICAL FIX: Only update global edit state if this is the current room
                // Background pagination response should not corrupt current room's edit mapping
                if (roomId == currentRoomId) {
                    buildEditChainsFromEvents(timelineList, clearExisting = !isPaginationRequest)
                    
                    // Process edit relationships
                    processEditRelationships()
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping global edit chain build - roomId ($roomId) != currentRoomId ($currentRoomId)")
                }
                
                if (isPaginationRequest) {
                    // This is a pagination request - merge with existing timeline
                    // Note: handlePaginationMerge updates cache for all rooms, but guards global state updates
                    handlePaginationMerge(roomId, timelineList, requestId)
                    paginateRequests.remove(requestId)
                    // Note: paginateRequestMaxTimelineIds cleanup happens in handlePaginationMerge
                    
                    // CRITICAL FIX: Only stop paginating indicator if currently viewing this room
                    if (roomId == currentRoomId) {
                        isPaginating = false
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pagination complete - roomId=$roomId, isPaginating set to ${if (roomId == currentRoomId) "FALSE" else "unchanged (background)"}")
                } else {
                    // This is an initial paginate - build timeline from chain mapping
                    android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: Initial paginate - roomId=$roomId, requestId=$requestId, timelineList.size=${timelineList.size}, isTimelineLoading=$isTimelineLoading")
                    handleInitialTimelineBuild(roomId, timelineList)
                    android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: After handleInitialTimelineBuild - roomId=$roomId, requestId=$requestId, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading")
                    // Clean up pending paginate tracking when initial paginate completes
                    if (isInitialPaginate) {
                        timelineRequests.remove(requestId)
                        roomsWithPendingPaginate.remove(roomId)
                        android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: Cleaned up tracking - roomId=$roomId, requestId=$requestId, remaining timelineRequests=${timelineRequests.size}")
                    }
                    
                    // CRITICAL FIX: After initial pagination completes, automatically request member profiles
                    // for all users in the timeline using get_specific_room_state
                    // This ensures room-specific display names and avatars are loaded correctly
                    if (timelineList.isNotEmpty()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initial pagination completed for $roomId, requesting member profiles for ${timelineList.size} events")
                        requestUpdatedRoomProfiles(roomId, timelineList)
                    }
                }
                
                // Mark room as read when timeline is successfully loaded - use most recent event by timestamp
                // (But not for background prefetch requests since we're just silently updating cache)
                // CRITICAL FIX: Only mark as read if the room is currently open (currentRoomId == roomId)
                // This prevents notifications from being dismissed when preemptive pagination happens
                // (preemptive pagination occurs when a notification is generated but user hasn't opened the room yet)
                if (!backgroundPrefetchRequests.containsKey(requestId)) {
                    // First check if room is actually open - don't mark as read for preemptive pagination
                    val isRoomOpen = currentRoomId == roomId
                    if (!isRoomOpen) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mark as read for room $roomId (room not currently open - preemptive pagination)")
                    } else {
                        // Mark as read only if room is actually visible (not just a minimized bubble)
                        // Check if this is a bubble and if it's visible
                        val shouldMarkAsRead = if (BubbleTracker.isBubbleOpen(roomId)) {
                            // Bubble exists - only mark as read if it's visible/maximized
                            BubbleTracker.isBubbleVisible(roomId)
                        } else {
                            // Not a bubble - mark as read (normal room view)
                            true
                        }
                        
                        if (shouldMarkAsRead) {
                            val mostRecentEvent = timelineList.maxByOrNull { it.timestamp }
                            if (mostRecentEvent != null) {
                                markRoomAsRead(roomId, mostRecentEvent.eventId)
                            }
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)")
                        }
                    }
                }
            }
            
            android.util.Log.d("Andromuks", "🟡 processEventsArray: COMPLETE - roomId=$roomId, requestId=$requestId, reactionsProcessed=$reactionProcessedCount, timelineList.size=${timelineList.size}, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading")
            return reactionProcessedCount
        }

        when (data) {
            is JSONArray -> {
                android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: JSONArray response - roomId=$roomId, requestId=$requestId, array.length=${data.length()}")
                totalReactionsProcessed = processEventsArray(data)
                android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: processEventsArray completed - roomId=$roomId, requestId=$requestId, reactionsProcessed=$totalReactionsProcessed, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading")
            }
            is JSONObject -> {
                val eventsArray = data.optJSONArray("events")
                if (eventsArray != null) {
                    android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: JSONObject with events array - roomId=$roomId, requestId=$requestId, events.length=${eventsArray.length()}")
                    
                    // CRITICAL: Process related_events FIRST before processing main events
                    // This ensures that when main events are processed and rendered, the reply targets
                    // from related_events are already in the cache and can be found immediately
                    val relatedEventsArray = data.optJSONArray("related_events")
                    if (relatedEventsArray != null && relatedEventsArray.length() > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${relatedEventsArray.length()} related_events from paginate response for room $roomId (BEFORE main events)")
                        val memberMap = RoomMemberCache.getRoomMembers(roomId)
                        RoomTimelineCache.addEventsFromSync(roomId, relatedEventsArray, memberMap)
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added ${relatedEventsArray.length()} related_events to timeline cache for room $roomId")
                        // CRITICAL: Increment timelineUpdateCounter so reply previews can reactively find events in related_events
                        // This ensures that when related_events are added to the cache, composables that are looking for reply targets
                        // will re-check the cache and find the newly added events
                        timelineUpdateCounter++
                    }
                    
                    // NOW process main events array - related_events are already in cache
                    totalReactionsProcessed = processEventsArray(eventsArray)
                    android.util.Log.d("Andromuks", "🟡 handleTimelineResponse: processEventsArray completed - roomId=$roomId, requestId=$requestId, reactionsProcessed=$totalReactionsProcessed, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading")
                    
                    // CRITICAL: Process read receipts AFTER events are fully processed
                    // This ensures that when receipts are applied, the events they reference are already in the timeline
                    // Similar to how related_events are processed BEFORE main events, receipts should be processed AFTER
                    val receipts = data.optJSONObject("receipts")
                    if (receipts != null && receipts.length() > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts from paginate response for room: $roomId (AFTER events processed) - ${receipts.length()} event groups, total events in timeline: ${timelineEvents.size}")
                        // Process receipts in background for parsing, but ensure events are already processed
                        viewModelScope.launch(Dispatchers.Default) {
                            try {
                                // Parse receipts data in background (extract changes)
                                val receiptsCopy = receipts.toString() // Deep copy JSON to avoid thread issues
                                val parsedReceipts = org.json.JSONObject(receiptsCopy)
                                
                                // Build the authoritative receipts map in background (no reading from readReceipts)
                                // PAGINATE IS AUTHORITATIVE: Accept all receipts as-is from the server, no deduplication needed
                                // The server's paginate response is the source of truth - if it says event X has 6 receipts, we show 6 receipts
                                val authoritativeReceipts = mutableMapOf<String, MutableList<ReadReceipt>>()
                                val keys = parsedReceipts.keys()
                                
                                while (keys.hasNext()) {
                                    val eventId = keys.next()
                                    val receiptsArray = parsedReceipts.optJSONArray(eventId)
                                    if (receiptsArray != null) {
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing receipts for eventId=$eventId, roomId=$roomId - ${receiptsArray.length()} receipts in array")
                                        val receiptsForEvent = mutableListOf<ReadReceipt>()

                                        for (i in 0 until receiptsArray.length()) {
                                            val receiptJson = receiptsArray.optJSONObject(i)
                                            if (receiptJson != null) {
                                                val receipt = ReadReceipt(
                                                    userId = receiptJson.optString("user_id", ""),
                                                    eventId = receiptJson.optString("event_id", ""),
                                                    timestamp = receiptJson.optLong("timestamp", 0),
                                                    receiptType = receiptJson.optString("receipt_type", ""),
                                                    roomId = roomId // Store room ID for consistency (paginate is authoritative but roomId helps with filtering)
                                                )
                                                
                                                // Validate receipt has required fields and eventId matches
                                                if (receipt.userId.isNotBlank() && receipt.eventId.isNotBlank() && receipt.eventId == eventId) {
                                                    receiptsForEvent.add(receipt)
                                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added receipt for eventId=$eventId, userId=${receipt.userId}, timestamp=${receipt.timestamp}, roomId=$roomId")
                                                } else {
                                                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Invalid receipt - eventId=$eventId, receiptEventId=${receipt.eventId}, userId=${receipt.userId}, roomId=$roomId")
                                                }
                                            }
                                        }
                                        
                                        // Store all receipts for this event
                                        authoritativeReceipts[eventId] = receiptsForEvent
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Final receipts for eventId=$eventId, roomId=$roomId: ${receiptsForEvent.size} receipts")
                                    } else {
                                        // Event has no receipts array - mark as empty (remove existing receipts)
                                        authoritativeReceipts[eventId] = mutableListOf()
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Event $eventId has no receipts array - marking as empty")
                                    }
                                }
                                
                                val totalReceipts = authoritativeReceipts.values.sumOf { it.size }
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processed $totalReceipts total receipts from paginate, distributed across ${authoritativeReceipts.size} events")
                                
                                // Apply changes on main thread to avoid concurrent modification during composition
                                withContext(Dispatchers.Main) {
                                    try {
                                        var hasChanges = false
                                        
                                        synchronized(readReceiptsLock) {
                                            // Apply all changes atomically on main thread
                                            authoritativeReceipts.forEach { (eventId, receipts) ->
                                                val existingReceipts = readReceipts[eventId]
                                                // CRITICAL FIX: Include roomId in comparison to properly detect changes
                                                // Old receipts might have empty roomId, new ones have roomId set
                                                val receiptsChanged = existingReceipts == null || 
                                                    existingReceipts.size != receipts.size ||
                                                    existingReceipts.any { existing ->
                                                        !receipts.any { auth ->
                                                            auth.userId == existing.userId && 
                                                            auth.timestamp == existing.timestamp &&
                                                            auth.eventId == existing.eventId &&
                                                            auth.roomId == existing.roomId // Compare roomId
                                                        }
                                                    } ||
                                                    receipts.any { auth ->
                                                        !existingReceipts.any { existing ->
                                                            existing.userId == auth.userId && 
                                                            existing.timestamp == auth.timestamp &&
                                                            existing.eventId == auth.eventId &&
                                                            existing.roomId == auth.roomId // Compare roomId
                                                        }
                                                    }
                                                
                                                if (receiptsChanged) {
                                                    if (receipts.isEmpty()) {
                                                        readReceipts.remove(eventId)
                                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ReceiptFunctions: Removed all receipts for eventId=$eventId, roomId=$roomId (server says none)")
                                                    } else {
                                                        readReceipts[eventId] = receipts
                                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ReceiptFunctions: Replaced receipts for eventId=$eventId, roomId=$roomId with ${receipts.size} receipts from paginate")
                                                    }
                                                    hasChanges = true
                                                }
                                            }
                                            
                                            // Update singleton cache after processing receipts (only if there were changes)
                                            if (hasChanges) {
                                                val receiptsForCache = readReceipts.mapValues { it.value.toList() }
                                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updating ReadReceiptCache with ${receiptsForCache.size} events (${receiptsForCache.values.sumOf { it.size }} total receipts) from paginate for room: $roomId")
                                                ReadReceiptCache.setAll(receiptsForCache)
                                                if (BuildConfig.DEBUG) {
                                                    val cacheAfter = ReadReceiptCache.getAllReceipts()
                                                    android.util.Log.d("Andromuks", "AppViewModel: ReadReceiptCache after update: ${cacheAfter.size} events (${cacheAfter.values.sumOf { it.size }} total receipts)")
                                                }
                                            }
                                        }
                                        
                                        // Single UI update after all processing (only if there were changes)
                                        if (hasChanges) {
                                            readReceiptsUpdateCounter++
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Andromuks", "AppViewModel: Error updating receipt state on main thread", e)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Andromuks", "AppViewModel: Error processing receipts in background", e)
                            }
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No receipts found in paginate response for room: $roomId")
                    }
                    
                    // After processing all events, check for missing m.in_reply_to targets
                    // If a message references a reply target that's not in the cache, fetch it via get_event
                    viewModelScope.launch(Dispatchers.Default) {
                        try {
                            // Re-check cache after adding related_events to ensure we have the latest state
                            val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                            val cachedEventIds = cachedEvents.map { it.eventId }.toSet()
                            val missingReplyTargets = mutableSetOf<Pair<String, String>>() // (roomId, eventId)
                            
                            // Check all events in the response for m.in_reply_to references
                            for (i in 0 until eventsArray.length()) {
                                val eventJson = eventsArray.optJSONObject(i) ?: continue
                                val event = TimelineEvent.fromJson(eventJson)
                                
                                // Check for m.in_reply_to in both content and decrypted
                                val replyToEventId = event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                                    ?: event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                                
                                if (replyToEventId != null && replyToEventId.isNotBlank()) {
                                    // Check if the reply target is in the cache
                                    if (!cachedEventIds.contains(replyToEventId)) {
                                        missingReplyTargets.add(Pair(event.roomId, replyToEventId))
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Missing reply target event_id=$replyToEventId for event ${event.eventId} in room ${event.roomId}")
                                    }
                                }
                            }
                            
                            // Also check related_events for m.in_reply_to references
                            if (relatedEventsArray != null) {
                                for (i in 0 until relatedEventsArray.length()) {
                                    val eventJson = relatedEventsArray.optJSONObject(i) ?: continue
                                    val event = TimelineEvent.fromJson(eventJson)
                                    
                                    val replyToEventId = event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                                        ?: event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                                    
                                    if (replyToEventId != null && replyToEventId.isNotBlank()) {
                                        if (!cachedEventIds.contains(replyToEventId)) {
                                            missingReplyTargets.add(Pair(event.roomId, replyToEventId))
                                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Missing reply target event_id=$replyToEventId for related event ${event.eventId} in room ${event.roomId}")
                                        }
                                    }
                                }
                            }
                            
                            // Fetch missing reply targets via get_event
                            if (missingReplyTargets.isNotEmpty()) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetching ${missingReplyTargets.size} missing reply target events via get_event")
                                
                                for ((targetRoomId, targetEventId) in missingReplyTargets) {
                                    // Use a suspend function to fetch the event
                                    val deferred = CompletableDeferred<TimelineEvent?>()
                                    withContext(Dispatchers.Main) {
                                        getEvent(targetRoomId, targetEventId) { event ->
                                            deferred.complete(event)
                                        }
                                    }
                                    
                                    val fetchedEvent = withTimeoutOrNull(5000L) {
                                        deferred.await()
                                    }
                                    
                                    if (fetchedEvent != null) {
                                        // Add the fetched event to the cache
                                        val memberMap = RoomMemberCache.getRoomMembers(targetRoomId)
                                        val eventsJsonArray = org.json.JSONArray()
                                        eventsJsonArray.put(fetchedEvent.toRawJsonObject())
                                        RoomTimelineCache.addEventsFromSync(targetRoomId, eventsJsonArray, memberMap)
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetched and cached missing reply target event_id=$targetEventId for room $targetRoomId")
                                    } else {
                                        android.util.Log.w("Andromuks", "AppViewModel: Failed to fetch missing reply target event_id=$targetEventId for room $targetRoomId (timeout or error)")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error checking for missing reply targets", e)
                        }
                    }
                } else {
                    android.util.Log.w("Andromuks", "🟡 handleTimelineResponse: JSONObject did not contain 'events' array - roomId=$roomId, requestId=$requestId, keys=${data.keys().asSequence().toList()}")
                }
                
                // Parse has_more field for pagination (but not for background prefetch)
                // NOTE: has_more is already parsed above for empty responses, but parse it here too
                // for non-empty responses to ensure consistency
                if (paginateRequests.containsKey(requestId)) {
                    val hasMore = hasMoreFromResponse ?: data.optBoolean("has_more", true) // Use pre-parsed value if available, otherwise parse
                    val fromServer = data.optBoolean("from_server", false)
                    
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ========================================")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PARSING PAGINATION METADATA")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel:    has_more: $hasMore")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel:    from_server: $fromServer")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel:    hasMoreMessages BEFORE: $hasMoreMessages")
                    
                    hasMoreMessages = hasMore
                    
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel:    hasMoreMessages AFTER: $hasMoreMessages")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full pagination response data keys: ${data.keys().asSequence().toList()}")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ========================================")
                    
                    // Show toast when reaching the end (empty responses already handled in processEventsArray)
                    if (!hasMore) {
                        android.util.Log.w("Andromuks", "AppViewModel: 🏁 REACHED END OF MESSAGE HISTORY (has_more=false)")
                        // Toast for empty responses is already shown in processEventsArray, so only show for non-empty responses here
                        // We can't easily check if response was empty here, so we'll show toast regardless (it's idempotent)
                        appContext?.let { context ->
                            android.widget.Toast.makeText(context, "No more messages available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (backgroundPrefetchRequests.containsKey(requestId)) {
                    // For background prefetch, we don't update hasMoreMessages to avoid affecting UI
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping has_more parsing for background prefetch request")
                }
                
                // NOTE: Receipts are now processed AFTER events (see above, after processEventsArray completes)
                // This ensures events are in the timeline before receipts are applied
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleTimelineResponse: ${data::class.java.simpleName}")
            }
        }

        // IMPORTANT: If we processed reactions in background prefetch, trigger UI update
        if (isBackgroundPrefetchRequest && totalReactionsProcessed > 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggering UI update for $totalReactionsProcessed reactions processed in background prefetch")
            reactionUpdateCounter++ // Trigger UI recomposition for reactions
        }

        val roomIdFromTimeline = timelineRequests.remove(requestId)
        if (roomIdFromTimeline != null) {
            roomsWithPendingPaginate.remove(roomIdFromTimeline)
        }
        paginateRequests.remove(requestId)
        paginateRequestMaxTimelineIds.remove(requestId)
        backgroundPrefetchRequests.remove(requestId)
    }
    private fun handleRoomStateResponse(requestId: Int, data: Any) {
        val roomId = roomStateRequests.remove(requestId) ?: return
        
        // PERFORMANCE: Remove from pending requests set
        pendingRoomStateRequests.remove(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling room state response for room: $roomId")
        
        // CRITICAL FIX: Track completion of initial room state loading
        if (allRoomStatesRequested && !allRoomStatesLoaded) {
            var wasInPendingSet = false
            synchronized(pendingRoomStateResponses) {
                wasInPendingSet = pendingRoomStateResponses.remove(roomId)
            }
            
            // Only count rooms that were part of the initial load
            if (wasInPendingSet) {
                completedRoomStateRequests++
                
                // Update progress message
                val progress = if (totalRoomStateRequests > 0) {
                    "$completedRoomStateRequests / $totalRoomStateRequests"
                } else {
                    "$completedRoomStateRequests"
                }
                addStartupProgressMessage("Loading bridge info for all rooms... $progress")
                
                // Check if all room states are loaded
                val remaining = synchronized(pendingRoomStateResponses) {
                    pendingRoomStateResponses.size
                }
                
                if (remaining == 0) {
                    // All room states loaded!
                    allRoomStatesLoaded = true
                    addStartupProgressMessage("Bridge info loaded for all rooms")
                    
                    // NOW allow commands to be sent
                    canSendCommandsToBackend = true
                    flushPendingCommandsQueue()
                    
                    // Check if startup is complete (will now pass)
                    checkStartupComplete()
                    
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: All room states loaded ($completedRoomStateRequests/$totalRoomStateRequests) - commands now allowed, startup can complete")
                    }
                }
            }
        }
        
        // NAVIGATION PERFORMANCE: Update navigation state cache when essential data is loaded
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            essentialDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
        
        when (data) {
            is JSONArray -> {
                // Server returns events array directly
                parseRoomStateFromEvents(roomId, data)
            }
            is JSONObject -> {
                val events = data.optJSONArray("events")
                if (events != null) {
                    parseRoomStateFromEvents(roomId, events)
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No events array in room state response")
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomStateResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    /**
     * Get stored room state for a specific room
     */
    fun getRoomState(roomId: String): JSONArray? {
        return roomStatesCache[roomId]
    }
    
    /**
     * Parse room state from state events.
     * OPTIMIZED: Single pass with minimal JSON access; early exits in branches.
     */
    private fun parseRoomStateFromEvents(roomId: String, events: JSONArray) {
        var name: String? = null
        var canonicalAlias: String? = null
        var topic: String? = null
        var avatarUrl: String? = null
        var isEncrypted = false
        var powerLevels: PowerLevelsInfo? = null
        val pinnedEventIds = mutableListOf<String>()
        var bridgeInfo: BridgeInfo? = null
        
        // OPTIMIZED: Process events in single pass with early exits
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            
            val eventType = event.optString("type")
            val content = event.optJSONObject("content") ?: continue
            
            when (eventType) {
                "m.room.name" -> {
                    name = content.optString("name")?.takeIf { it.isNotBlank() }
                }
                "m.room.canonical_alias" -> {
                    canonicalAlias = content.optString("alias")?.takeIf { it.isNotBlank() }
                }
                "m.room.topic" -> {
                    // OPTIMIZED: Cache parsed topic to avoid re-parsing
                    if (topic.isNullOrBlank()) {
                        topic = content.optString("topic")?.takeIf { it.isNotBlank() }
                        
                        // Fallback to structured format if simple topic not found
                        if (topic.isNullOrBlank()) {
                            val topicContent = content.optJSONObject("m.topic")
                            val textArray = topicContent?.optJSONArray("m.text")
                            if (textArray != null && textArray.length() > 0) {
                                val firstText = textArray.optJSONObject(0)
                                topic = firstText?.optString("body")?.takeIf { it.isNotBlank() }
                            }
                        }
                    }
                }
                "m.room.avatar" -> {
                    avatarUrl = content.optString("url")?.takeIf { it.isNotBlank() }
                }
                "m.room.encryption" -> {
                    // OPTIMIZED: Early exit once encryption detected
                    if (!isEncrypted && content.optString("algorithm")?.isNotBlank() == true) {
                        isEncrypted = true
                    }
                }
                "m.room.power_levels" -> {
                    // OPTIMIZED: Only parse if not already set (each event is definitive)
                    if (powerLevels == null) {
                        val usersObj = content.optJSONObject("users")
                        val usersMap = if (usersObj != null) {
                            mutableMapOf<String, Int>().apply {
                                usersObj.keys()?.forEach { userId ->
                                    put(userId, usersObj.optInt(userId, 0))
                                }
                            }
                        } else {
                            mutableMapOf()
                        }
                        
                        val eventsObj = content.optJSONObject("events")
                        val eventsMap = if (eventsObj != null) {
                            mutableMapOf<String, Int>().apply {
                                eventsObj.keys()?.forEach { ev ->
                                    put(ev, eventsObj.optInt(ev, 0))
                                }
                            }
                        } else {
                            mutableMapOf()
                        }
                        
                        powerLevels = PowerLevelsInfo(
                            users = usersMap,
                            usersDefault = content.optInt("users_default", 0),
                            redact = content.optInt("redact", 50),
                            events = eventsMap,
                            eventsDefault = content.optInt("events_default", 0)
                        )
                    }
                }
                "m.room.pinned_events" -> {
                    // OPTIMIZED: Clear and rebuild pinned events (latest wins)
                    pinnedEventIds.clear()
                    val pinnedArray = content.optJSONArray("pinned")
                    if (pinnedArray != null) {
                        for (j in 0 until pinnedArray.length()) {
                            val eventId = pinnedArray.optString(j)?.takeIf { it.isNotBlank() }
                            if (eventId != null) {
                                pinnedEventIds.add(eventId)
                            }
                        }
                    }
                }
                "m.bridge", "uk.half-shot.bridge" -> {
                    val parsedBridge = parseBridgeInfoEvent(event)
                    if (parsedBridge != null) {
                        bridgeInfo = parsedBridge
                    }
                }
            }
        }
        
        // Create room state object
        val roomState = RoomState(
            roomId = roomId,
            name = name,
            canonicalAlias = canonicalAlias,
            topic = topic,
            avatarUrl = avatarUrl,
            isEncrypted = isEncrypted,
            powerLevels = powerLevels,
            pinnedEventIds = pinnedEventIds,
            bridgeInfo = bridgeInfo
        )
        
        // Extract bridge protocol avatar URL for room list badge display
        val bridgeProtocolAvatarUrl = bridgeInfo?.protocol?.avatarUrl
        // Extract bridge protocol display name
        val bridgeDisplayName = bridgeInfo?.displayName
        
        // If bridge metadata says this is a DM, mark the room as direct (helps bridged DMs show in Direct tab).
        val bridgeSaysDm = bridgeInfo?.roomType?.equals("dm", ignoreCase = true) == true ||
            bridgeInfo?.roomTypeV2?.equals("dm", ignoreCase = true) == true
        
        // Update roomMap with bridge info (DM status and protocol avatar) if room exists
        val existing = roomMap[roomId]
        if (existing != null) {
            var updatedRoom = existing
            var needsUpdate = false
            
            // Update DM status if needed
            if (bridgeSaysDm && !existing.isDirectMessage) {
                updatedRoom = updatedRoom.copy(isDirectMessage = true)
                directMessageRoomIds = directMessageRoomIds + roomId
                needsUpdate = true
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked $roomId as DM via bridge room_type")
            }
            
            // WRITE-ONLY BRIDGE INFO: Once bridge info is set, it's never removed (even if response doesn't include it)
            // Bridge events are static/eternal - if a room is no longer bridged, it will be resolved on app restart
            // This prevents badges from disappearing due to incomplete get_room_state responses
            val finalBridgeProtocolAvatarUrl = bridgeProtocolAvatarUrl ?: existing.bridgeProtocolAvatarUrl
            
            if (finalBridgeProtocolAvatarUrl != existing.bridgeProtocolAvatarUrl) {
                // Bridge info changed (either set for first time, or updated)
                updatedRoom = updatedRoom.copy(bridgeProtocolAvatarUrl = finalBridgeProtocolAvatarUrl)
                needsUpdate = true
                if (BuildConfig.DEBUG) {
                    if (bridgeProtocolAvatarUrl != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Set/updated bridge protocol avatar for $roomId: $bridgeProtocolAvatarUrl")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Preserved existing bridge protocol avatar for $roomId: ${existing.bridgeProtocolAvatarUrl} (response didn't include m.bridge)")
                    }
                }
            } else if (finalBridgeProtocolAvatarUrl != null && existing.bridgeProtocolAvatarUrl != null) {
                // Bridge info unchanged but present - ensure it's explicitly set to trigger UI update if needed
                updatedRoom = updatedRoom.copy(bridgeProtocolAvatarUrl = finalBridgeProtocolAvatarUrl)
                // Only update if we haven't already set needsUpdate for other reasons
                if (!needsUpdate) {
                    needsUpdate = true
                }
            }
            
            if (needsUpdate) {
                roomMap[roomId] = updatedRoom
                allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                invalidateRoomSectionCache()
                // CRITICAL FIX: Increment roomListUpdateCounter to trigger UI recomposition
                // This ensures bridge badges appear when room state is updated
                roomListUpdateCounter++
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated roomMap with bridge info for $roomId, triggered UI update")
            }
            
            // CRITICAL FIX: Save bridge info to SharedPreferences cache for future initial connections
            // This allows us to skip get_room_state requests for rooms we already know about
            appContext?.let { context ->
                val avatarUrlToSave = finalBridgeProtocolAvatarUrl ?: ""
                net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeAvatarUrl(context, roomId, avatarUrlToSave)
                // Also save display name if available
                if (bridgeDisplayName != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(context, roomId, bridgeDisplayName)
                }
            }
        } else {
            // Room doesn't exist in roomMap yet - store bridge info for later
            // This can happen if get_room_state arrives before sync_complete creates the room
            if (bridgeProtocolAvatarUrl != null || bridgeSaysDm) {
                // Store bridge protocol avatar in a temporary cache that will be applied when room is created
                // For now, we'll rely on the room being created from sync_complete and then updated
                // The bridge info will be applied on the next get_room_state or when room is opened
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Bridge info received for $roomId but room not in roomMap yet (will be applied when room is created)")
            }
            
            if (bridgeSaysDm) {
                // No room yet; remember as DM so when the room is added it will be treated as direct.
                directMessageRoomIds = directMessageRoomIds + roomId
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Recorded $roomId as DM via bridge room_type (no room object yet)")
            }
            
            // CRITICAL FIX: Save bridge info to SharedPreferences even if room doesn't exist yet
            // This ensures we cache the info for when the room is created
            appContext?.let { context ->
                val avatarUrlToSave = bridgeProtocolAvatarUrl ?: ""
                net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeAvatarUrl(context, roomId, avatarUrlToSave)
                // Also save display name if available
                val bridgeDisplayName = bridgeInfo?.displayName
                if (bridgeDisplayName != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(context, roomId, bridgeDisplayName)
                }
            }
        }

        // Room state is in-memory only; no local persistence.
        
        // ✓ FIX: Only update currentRoomState if this is the currently open room
        // This prevents the room header from flashing through all rooms during shortcut loading
        if (roomId == currentRoomId) {
            currentRoomState = roomState
            roomStateUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✓ Updated current room state - Name: $name, Alias: $canonicalAlias, Topic: $topic, Avatar: $avatarUrl, Encrypted: $isEncrypted")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsed room state for $roomId (not current room) - Name: $name, Bridge Protocol Avatar: $bridgeProtocolAvatarUrl")
        }
    }
    
    /**
     * Get bridge display name from a room's cached state or SharedPreferences cache
     * Returns the protocol display name (e.g., "WhatsApp", "Telegram", "Discord") or null if not found
     */
    private fun getBridgeDisplayNameFromRoomState(roomId: String): String? {
        // First try to get from SharedPreferences cache (fastest, always available if cached)
        val context = appContext
        if (context != null) {
            val cachedDisplayName = net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeDisplayName(context, roomId)
            if (cachedDisplayName != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - Found cached display name for room $roomId: $cachedDisplayName")
                return cachedDisplayName
            }
        }
        
        // Fallback: try to parse from room state cache
        val roomStateEvents = roomStatesCache[roomId]
        if (roomStateEvents == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - No cached state for room $roomId")
            return null
        }
        
        // Find bridge event in room state
        for (i in 0 until roomStateEvents.length()) {
            val event = roomStateEvents.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.bridge" || eventType == "uk.half-shot.bridge") {
                val bridgeInfo = parseBridgeInfoEvent(event)
                val displayName = bridgeInfo?.displayName
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - Found bridge for room $roomId: displayName=$displayName, protocol=${bridgeInfo?.protocol?.id}, protocolDisplayName=${bridgeInfo?.protocol?.displayName}")
                
                // Cache the display name for future use
                val contextForCache = appContext
                if (displayName != null && contextForCache != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(contextForCache, roomId, displayName)
                }
                
                return displayName
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - No bridge event found in cached state for room $roomId (${roomStateEvents.length()} events)")
        return null
    }
    
    private fun parseBridgeInfoEvent(event: JSONObject): BridgeInfo? {
        val content = event.optJSONObject("content") ?: return null
        val stateKey = event.optString("state_key").takeIf { it.isNotBlank() }
        val bridgeBot = content.optString("bridgebot").takeIf { it.isNotBlank() }
        val creator = content.optString("creator").takeIf { it.isNotBlank() }
        val roomType = content.optString("com.beeper.room_type").takeIf { it.isNotBlank() }
        val roomTypeV2 = content.optString("com.beeper.room_type.v2").takeIf { it.isNotBlank() }
        
        val channelObj = content.optJSONObject("channel")
        val protocolObj = content.optJSONObject("protocol")
        
        val channelInfo = channelObj?.let {
            val id = it.optString("id").takeIf { value -> value.isNotBlank() }
            val displayName = it.optString("displayname").takeIf { value -> value.isNotBlank() }
            val avatarUrl = it.optString("avatar_url").takeIf { value -> value.isNotBlank() }
            val receiver = it.optString("fi.mau.receiver").takeIf { value -> value.isNotBlank() }
            
            if (id != null || displayName != null || avatarUrl != null || receiver != null) {
                BridgeChannelInfo(
                    id = id,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    receiver = receiver
                )
            } else {
                null
            }
        }
        
        val protocolInfo = protocolObj?.let {
            val id = it.optString("id").takeIf { value -> value.isNotBlank() }
            val displayName = it.optString("displayname").takeIf { value -> value.isNotBlank() }
            val avatarUrl = it.optString("avatar_url").takeIf { value -> value.isNotBlank() }
            val externalUrl = it.optString("external_url").takeIf { value -> value.isNotBlank() }
            
            if (id != null || displayName != null || avatarUrl != null || externalUrl != null) {
                BridgeProtocolInfo(
                    id = id,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    externalUrl = externalUrl
                )
            } else {
                null
            }
        }
        
        if (
            bridgeBot == null &&
            creator == null &&
            channelInfo == null &&
            protocolInfo == null
        ) {
            return null
        }
        
        return BridgeInfo(
            stateKey = stateKey,
            bridgeBot = bridgeBot,
            creator = creator,
            roomType = roomType,
            roomTypeV2 = roomTypeV2,
            channel = channelInfo,
            protocol = protocolInfo
        )
    }
    
    private fun handleMessageResponse(requestId: Int, data: Any) {
        val roomId = messageRequests.remove(requestId) ?: return
        if (pendingSendCount > 0) {
            pendingSendCount--
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling message response for room: $roomId, pendingSendCount=$pendingSendCount, data: $data")
        
        // PHASE 5.2: Acknowledgment is now handled in handleResponse() for all commands
        // No need to acknowledge here separately
        
        // PHASE 5.3: Extract transaction_id from response for send_complete matching
        // Response contains transaction_id that we'll use to match send_complete later
        if (data is JSONObject) {
            val transactionId = data.optString("transaction_id", "")
            if (transactionId.isNotEmpty()) {
                // Store transaction_id in the pending operation for send_complete matching
                val operation = pendingWebSocketOperations.find { 
                    val opRequestId = it.data["requestId"] as? Int
                    opRequestId == requestId
                }
                if (operation != null && operation.type.startsWith("command_send_message")) {
                    // Update operation with transaction_id for send_complete matching
                    val updatedData = operation.data.toMutableMap()
                    updatedData["transaction_id"] = transactionId
                    val updatedOperation = operation.copy(data = updatedData)
                    pendingWebSocketOperations.remove(operation)
                    pendingWebSocketOperations.add(updatedOperation)
                    savePendingOperationsToStorage()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Stored transaction_id=$transactionId for request_id=$requestId")
                }
            }
        }
        
        // Check if the response indicates an error
        var isError = false
        when (data) {
            is Boolean -> {
                if (!data) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send failed - boolean false response")
                }
            }
            is JSONObject -> {
                // Check for error indicators in JSON response
                val error = data.optString("error")
                val success = data.optBoolean("success", true)
                if (error.isNotEmpty() || !success) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send failed - error: $error, success: $success")
                }
            }
            is String -> {
                // String responses could indicate errors
                if (data.lowercase().contains("error") || data.lowercase().contains("fail")) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send appears to be an error response: $data")
                }
            }
        }
        
        // NOTE: We receive send_complete for sent messages, so we don't need to process
        // the response here to avoid duplicates. send_complete will add the event to timeline.
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Message response received, waiting for send_complete for actual event")
        
        // SHORTCUT OPTIMIZATION: Update shortcut when message is successfully sent
        // Update shortcuts immediately on message response (when we know it was sent successfully)
        // rather than waiting for send_complete which may not arrive or may be delayed
        if (!isError && roomId.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Message successfully sent to room $roomId, updating shortcut")
            
            val api = conversationsApi
            if (api != null) {
                // Get room from roomMap and update shortcut
                val room = roomMap[roomId] ?: getRoomById(roomId)
                if (room != null) {
                    // Update timestamp to current time since message was just sent
                    val updatedRoom = room.copy(sortingTimestamp = System.currentTimeMillis())
                    viewModelScope.launch(Dispatchers.Default) {
                        api.updateShortcutsFromSyncRooms(listOf(updatedRoom))
                        android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Successfully updated shortcut for room ${updatedRoom.name} after sending message")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - Room $roomId not found in roomMap, cannot update shortcut")
                }
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - conversationsApi is null, cannot update shortcut")
            }
        }
    }
    
    /**
     * Refresh shortcuts on app startup from current room list
     * This ensures shortcuts are up-to-date when the app starts, but doesn't update on every sync_complete
     * Only called once per app session after init_complete
     * 
     * Waits for initial sync processing to complete so rooms have their timestamps set
     */
    private fun refreshShortcutsOnStartup() {
        val api = conversationsApi
        if (api == null) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: refreshShortcutsOnStartup - conversationsApi is null, cannot refresh shortcuts")
            return
        }
        
        // Wait for initial sync processing to complete so rooms have their timestamps set
        // This ensures we have room data from sync_complete messages before refreshing shortcuts
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Wait for initial sync to complete (with timeout to avoid waiting forever)
                var waitCount = 0
                val maxWait = 50 // Wait up to 5 seconds (50 * 100ms)
                while (!initialSyncComplete && waitCount < maxWait) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
                
                if (!initialSyncComplete && waitCount >= maxWait) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Initial sync processing timeout, refreshing shortcuts anyway")
                }
                
                // Get top rooms from roomMap
                // Include rooms with timestamps OR unread counts OR any activity
                // This handles cases where rooms have events but timestamps aren't set yet
                val sortedRooms = roomMap.values
                    .filter { room ->
                        // Include rooms with timestamps, unread messages, or any activity
                        (room.sortingTimestamp != null && room.sortingTimestamp > 0) ||
                        (room.unreadCount != null && room.unreadCount > 0) ||
                        room.messagePreview != null // Has a message preview (indicates activity)
                    }
                    .sortedWith(compareByDescending<RoomItem> { room ->
                        // Primary sort: timestamp if available
                        room.sortingTimestamp ?: 0L
                    }.thenByDescending { room ->
                        // Secondary sort: unread count
                        room.unreadCount ?: 0
                    })
                
                if (sortedRooms.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing shortcuts on startup with ${sortedRooms.size} active rooms (top 4 will be used)")
                    // Use updateConversationShortcuts which handles the top 4 selection internally
                    api.updateConversationShortcuts(sortedRooms)
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No active rooms found (no timestamps, unread, or message previews), skipping shortcut refresh on startup")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error refreshing shortcuts on startup", e)
            }
        }
    }
    
    private fun handleReactionResponse(requestId: Int, data: Any) {
        val roomId = reactionRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling reaction response for room: $roomId, currentRoomId: $currentRoomId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Created reaction event: type=${event.type}, roomId=${event.roomId}, eventId=${event.eventId}")
                if (event.type == "m.reaction") {
                    // Don't add response events to timeline - they have temporary transaction IDs
                    // The real event will come via send_complete with proper Matrix event ID
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Ignoring reaction response event (temporary ID), waiting for send_complete: ${event.content?.optJSONObject("m.relates_to")?.optString("key")}")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Expected m.reaction event but got: ${event.type}")
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleReactionResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleRoomStateWithMembersResponse(requestId: Int, data: Any) {
        val callback = roomStateWithMembersRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling room state with members response for requestId: $requestId")
        
        try {
            // Parse the room state data using the utility function
            // This response includes members because include_members: true was sent
            val roomStateInfo = net.vrkknn.andromuks.utils.parseRoomStateResponse(data)
            
            if (roomStateInfo != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Successfully parsed room state with ${roomStateInfo.members.size} members (include_members=true response)")
                callback(roomStateInfo, null)
            } else {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to parse room state response")
                callback(null, "Failed to parse room state")
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error handling room state with members response", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleEventResponse(requestId: Int, data: Any) {
        val requestInfo = eventRequests.remove(requestId) ?: return
        val (roomId, callback) = requestInfo
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling event response for requestId: $requestId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrieved event: ${event.eventId}, type: ${event.type}, sender: ${event.sender}")
                callback(event)
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleEventResponse: ${data::class.java.simpleName}")
                callback(null)
            }
        }
    }
    
    private fun handleOnDemandProfileResponse(requestId: Int, data: Any) {
        val requestKey = profileRequests.remove(requestId) ?: return
        val (roomId, userId) = requestKey.split(":", limit = 2)
        
        // Remove from pending requests
        pendingProfileRequests.remove(requestKey)
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling on-demand profile response for $userId in room $roomId")
        
        when (data) {
            is JSONArray -> {
                if (data.length() > 0) {
                    // Parse the single member event
                    parseMemberEventsForProfileUpdate(roomId, data)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Successfully loaded profile for $userId")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: No profile data found for $userId")
                }
            }
            is JSONObject -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: On-demand profile response is JSONObject, expected JSONArray")
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleOnDemandProfileResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleRoomSpecificStateResponse(requestId: Int, data: Any) {
        val roomId = roomSpecificStateRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling room specific state response for room: $roomId, requestId: $requestId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room specific state response data type: ${data::class.java.simpleName}")
        
        // Check if this is an emoji pack request
        val emojiPackInfo = emojiPackRequests.remove(requestId)
        if (emojiPackInfo != null) {
            val (packRoomId, packName) = emojiPackInfo
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing emoji pack response for pack $packName in room $packRoomId")
            handleEmojiPackResponse(packRoomId, packName, data)
            return
        }
        
        // Check if this is a per-room profile callback request
        val profileCallback = roomSpecificProfileCallbacks.remove(requestId)
        if (profileCallback != null) {
            when (data) {
                is JSONArray -> {
                    if (data.length() > 0) {
                        val event = data.getJSONObject(0)
                        val content = event.optJSONObject("content")
                        if (content != null) {
                            val displayName = content.optString("displayname")?.takeIf { it.isNotBlank() }
                            val avatarUrl = content.optString("avatar_url")?.takeIf { it.isNotBlank() }
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - displayName: $displayName, avatarUrl: $avatarUrl")
                            profileCallback(displayName, avatarUrl)
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - no content in event")
                            profileCallback(null, null)
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - empty response")
                        profileCallback(null, null)
                    }
                }
                else -> {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Per-room profile callback - unexpected data type: ${data::class.java.simpleName}")
                    profileCallback(null, null)
                }
            }
            return
        }
        
        // CRITICAL FIX: Find the user ID from request metadata to clean up pending requests
        // This ensures cleanup even if response is empty or doesn't contain member events
        val metadataForRequest = profileRequestMetadata.entries.find { it.value.requestId == requestId }
        val userIdFromRequest = metadataForRequest?.value?.userId
        
        when (data) {
            is JSONArray -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing JSONArray response with ${data.length()} items")
                // Parse member events from the response
                parseMemberEventsForProfileUpdate(roomId, data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: parseMemberEventsForProfileUpdate completed")
                
                // CRITICAL FIX: Clean up pending request even if response was empty
                // This handles cases where user doesn't exist in room or response is empty
                if (data.length() == 0 && userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    profileRequestMetadata.remove(requestKey)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up pending profile request for empty response: $requestKey")
                }
            }
            is JSONObject -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room specific state response is JSONObject, expected JSONArray")
                // CRITICAL FIX: Clean up on unexpected response format
                if (userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    profileRequestMetadata.remove(requestKey)
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up pending profile request for unexpected response format: $requestKey")
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomSpecificStateResponse: ${data::class.java.simpleName}")
                // CRITICAL FIX: Clean up on unhandled response type
                if (userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    profileRequestMetadata.remove(requestKey)
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up pending profile request for unhandled response type: $requestKey")
                }
            }
        }
    }
    
    /**
     * Handle emoji pack response from get_specific_room_state
     */
    private fun handleEmojiPackResponse(roomId: String, packName: String, data: Any) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling emoji pack response for $packName in $roomId")
        
        when (data) {
            is JSONArray -> {
                if (data.length() > 0) {
                    val event = data.getJSONObject(0)
                    val content = event.optJSONObject("content")
                    if (content != null) {
                        val images = content.optJSONObject("images")
                        val packInfo = content.optJSONObject("pack")
                        val displayName = packInfo?.optString("display_name") ?: packName
                        
                        if (images != null) {
                            val emojis = mutableListOf<CustomEmoji>()
                            val stickers = mutableListOf<Sticker>()
                            val imageKeys = images.names()
                            if (imageKeys != null) {
                                for (i in 0 until imageKeys.length()) {
                                    val emojiName = imageKeys.optString(i)
                                    val emojiData = images.optJSONObject(emojiName)
                                    if (emojiData != null) {
                                        val usage = emojiData.optJSONArray("usage")
                                        val mxcUrl = emojiData.optString("url")
                                        val info = emojiData.optJSONObject("info")
                                        
                                        if (mxcUrl.isNotBlank() && mxcUrl.startsWith("mxc://")) {
                                            // Parse usage array to determine if this can be used as emoji, sticker, or both
                                            var hasSticker = false
                                            var hasEmoticon = false
                                            
                                            if (usage != null && usage.length() > 0) {
                                                for (j in 0 until usage.length()) {
                                                    val usageItem = usage.optString(j)
                                                    if (usageItem == "sticker") {
                                                        hasSticker = true
                                                    } else if (usageItem == "emoticon") {
                                                        hasEmoticon = true
                                                    }
                                                }
                                            } else {
                                                // No usage key or empty means it can be used as BOTH emoji and sticker
                                                hasEmoticon = true
                                                hasSticker = true
                                            }
                                            
                                            // Add to stickers if it has "sticker" usage (regardless of emoticon)
                                            if (hasSticker) {
                                                stickers.add(Sticker(
                                                    name = emojiName,
                                                    mxcUrl = mxcUrl,
                                                    body = emojiName, // Use name as body/caption
                                                    info = info
                                                ))
                                            }
                                            
                                            // Add to emojis if it has "emoticon" usage or no usage key
                                            // (entries can appear in both lists if they have both usage types)
                                            if (hasEmoticon) {
                                                emojis.add(CustomEmoji(
                                                    name = emojiName,
                                                    mxcUrl = mxcUrl,
                                                    info = info
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (emojis.isNotEmpty()) {
                                // Update or add emoji pack
                                val existingPacks = customEmojiPacks.toMutableList()
                                val existingIndex = existingPacks.indexOfFirst { it.roomId == roomId && it.packName == packName }
                                
                                val newPack = EmojiPack(
                                    packName = packName,
                                    displayName = displayName,
                                    roomId = roomId,
                                    emojis = emojis
                                )
                                
                                // Update singleton cache
                                EmojiPacksCache.updatePack(newPack)
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated emoji pack $packName with ${emojis.size} emojis")
                            }
                            
                            if (stickers.isNotEmpty()) {
                                // Update or add sticker pack
                                val existingStickerPacks = stickerPacks.toMutableList()
                                val existingStickerIndex = existingStickerPacks.indexOfFirst { it.roomId == roomId && it.packName == packName }
                                
                                val newStickerPack = StickerPack(
                                    packName = packName,
                                    displayName = displayName,
                                    roomId = roomId,
                                    stickers = stickers
                                )
                                
                                // Update singleton cache
                                StickerPacksCache.updatePack(newStickerPack)
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated sticker pack $packName with ${stickers.size} stickers")
                            }
                        }
                    }
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Emoji pack response is not JSONArray: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleFullMemberListResponse(requestId: Int, data: Any) {
        val roomId = fullMemberListRequests.remove(requestId) ?: return
        
        // PERFORMANCE: Remove from pending requests set
        pendingFullMemberListRequests.remove(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling full member list response for room: $roomId")
        
        // NAVIGATION PERFORMANCE: Update navigation state cache when member data is loaded
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            memberDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
        
        when (data) {
            is JSONArray -> {
                // Parse all room state events but only process m.room.member events
                parseFullMemberListFromRoomState(roomId, data)
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleFullMemberListResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun parseFullMemberListFromRoomState(roomId: String, events: JSONArray) {
        val startTime = System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsing full member list from ${events.length()} room state events for room: $roomId")
        
        // Clear existing cache to ensure we don't have stale invite members or other invalid entries
        // Since this is a full member list request, we want to start fresh
        val previousSize = RoomMemberCache.getRoomMembers(roomId).size
        RoomMemberCache.clearRoom(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared $previousSize existing members from cache for fresh member list")
        
        // Use a local mutable map to build up the new member list
        val memberMap = mutableMapOf<String, MemberProfile>()
        
        var updatedMembers = 0
        val isLargeRoom = events.length() > 100
        val profilesToBatchSave = mutableMapOf<String, MemberProfile>()
        var cacheCleared = false // Flag to only clear cache once when crossing 500 threshold
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.room.member") {
                val stateKey = event.optString("state_key")
                val content = event.optJSONObject("content")
                val membership = content?.optString("membership")
                
                if (stateKey.isNotEmpty()) {
                    when (membership) {
                        "join" -> {
                            // Add/update joined members with full profile data
                            val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                            val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                            
                            val newProfile = MemberProfile(displayName, avatarUrl)
                            memberMap[stateKey] = newProfile
                            
                            // Update singleton cache
                            RoomMemberCache.updateMember(roomId, stateKey, newProfile)
                            
                            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                            storeMemberProfile(roomId, stateKey, newProfile)
                            
                            // MEMORY MANAGEMENT: Cleanup if needed (less frequently for large rooms)
                            if (!isLargeRoom || updatedMembers % 50 == 0) {
                                manageGlobalCacheSize()
                                manageRoomMemberCacheSize(roomId)
                                manageFlattenedMemberCacheSize()
                            }
                            
                            if (BuildConfig.DEBUG && updatedMembers < 20) {
                                android.util.Log.d("Andromuks", "AppViewModel: Added member $stateKey to room $roomId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            }
                            updatedMembers++
                            
                            // For large rooms, collect profiles for batch save instead of queuing individually
                            if (isLargeRoom) {
                                profilesToBatchSave[stateKey] = newProfile
                                
                                // For very large lists, clear caches aggressively (only once)
                                if (updatedMembers > 500 && !cacheCleared) {
                                    // Clear all caches to prevent OOM
                                    ProfileCache.clear()
                                    cacheCleared = true
                                    android.util.Log.w("Andromuks", "AppViewModel: Cleared all caches due to large member list (${updatedMembers}+ members)")
                                }
                            } else {
                                // Queue for batch saving for smaller lists
                                queueProfileForBatchSave(stateKey, newProfile)
                            }
                        }
                        "leave", "ban" -> {
                            // Remove members who left or were banned
                            val wasRemoved = memberMap.remove(stateKey) != null
                            RoomMemberCache.removeMember(roomId, stateKey)
                            val wasRemovedFromFlattened = ProfileCache.hasFlattenedProfile(roomId, stateKey)
                            if (wasRemovedFromFlattened) {
                                ProfileCache.removeFlattenedProfile(roomId, stateKey)
                            }
                            
                            // OPTIMIZED: Remove from indexed cache
                            ProfileCache.removeFromRoomIndex(roomId, stateKey)
                            
                            if (wasRemoved || wasRemovedFromFlattened) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed $stateKey from room $roomId (membership: $membership)")
                                updatedMembers++
                            }
                            // Note: Don't remove from global cache as they might be in other rooms
                        }
                    }
                }
            }
        }
        
        if (updatedMembers > 0) {
            val duration = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedMembers members in full member list for room $roomId in ${duration}ms")
            // Trigger UI update since member cache changed
            updateCounter++
            memberUpdateCounter++ // CRITICAL: Increment memberUpdateCounter so UI recomposes and shows mention list
            
            // Profiles are cached in-memory only - no DB persistence needed
            
            // Profiles are cached in-memory only - no DB persistence needed
            // Profiles are loaded opportunistically when rendering events via requestUserProfileOnDemand()
        }
        
        // Also parse room state metadata (name, alias, topic, avatar) for header display
        parseRoomStateFromEvents(roomId, events)
    }
    
    private fun parseMemberEventsForProfileUpdate(roomId: String, events: JSONArray) {
        val startTime = System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsing ${events.length()} member events for profile update in room: $roomId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member events data: ${events.toString()}")
        
        // Get existing members from singleton cache
        val existingMembers = RoomMemberCache.getRoomMembers(roomId)
        val memberMap = existingMembers.toMutableMap()
        var updatedProfiles = 0
        val processedUserIds = mutableSetOf<String>()
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.room.member") {
                val stateKey = event.optString("state_key")
                val content = event.optJSONObject("content")
                val membership = content?.optString("membership")
                
                if (stateKey.isNotEmpty()) {
                    // Track processed user IDs to clean up pending requests
                    processedUserIds.add(stateKey)
                    
                    if (membership == "join") {
                        // Process joined members - update their profile data
                        val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                        val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                        
                        val newProfile = MemberProfile(displayName, avatarUrl)
                        val previousProfile = memberMap[stateKey]
                        
                        // Only update if the profile data has actually changed
                        if (previousProfile == null || 
                            previousProfile.displayName != displayName || 
                            previousProfile.avatarUrl != avatarUrl) {
                            
                            memberMap[stateKey] = newProfile
                            RoomMemberCache.updateMember(roomId, stateKey, newProfile)
                            
                            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                            storeMemberProfile(roomId, stateKey, newProfile)
                            
                            // MEMORY MANAGEMENT: Cleanup if needed
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated profile for $stateKey - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile update completed for $stateKey, triggering memberUpdateCounter")
                            
                            // CRITICAL FIX: If this is the current user, also update currentUserProfile
                            if (stateKey == currentUserId && currentUserProfile == null) {
                                currentUserProfile = UserProfile(
                                    userId = currentUserId,
                                    displayName = displayName,
                                    avatarUrl = avatarUrl
                                )
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set currentUserProfile from member event - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            }
                            
                            updatedProfiles++
                            
                            // For very large lists, clear caches aggressively to prevent OOM
                            if (updatedProfiles > 500) {
                                // Clear all caches to prevent OOM
                                ProfileCache.clear()
                                android.util.Log.w("Andromuks", "AppViewModel: Cleared all caches due to large profile update ($updatedProfiles profiles)")
                            } else {
                                // Queue for batch saving for smaller lists
                                queueProfileForBatchSave(stateKey, newProfile)
                            }
                        }
                    } else if (membership == "leave" || membership == "ban") {
                        // Remove members who left or were banned from room cache
                        val wasRemoved = memberMap.remove(stateKey) != null
                        val flattenedKey = "$roomId:$stateKey"
                        val wasRemovedFromFlattened = ProfileCache.hasFlattenedProfile(roomId, stateKey)
                        if (wasRemovedFromFlattened) {
                            ProfileCache.removeFlattenedProfile(roomId, stateKey)
                        }
                        
                        // OPTIMIZED: Remove from indexed cache
                        ProfileCache.removeFromRoomIndex(roomId, stateKey)
                        
                        if (wasRemoved || wasRemovedFromFlattened) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed $stateKey from room cache (membership: $membership)")
                            updatedProfiles++
                        }
                        // Note: Don't remove from global cache as they might be in other rooms
                    }
                }
            }
        }
        
        if (updatedProfiles > 0) {
            val duration = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedProfiles profiles in room $roomId in ${duration}ms")
            // Trigger UI update since member cache changed
            updateCounter++
            memberUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggered memberUpdateCounter++ for $updatedProfiles profile updates")
        }
        
        // Clean up pending profile requests for processed users
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("type") == "m.room.member") {
                val stateKey = event.optString("state_key")
                if (stateKey.isNotEmpty()) {
                    val requestKey = "$roomId:$stateKey"
                    pendingProfileRequests.remove(requestKey)
                    profileRequestMetadata.remove(requestKey) // CRITICAL FIX: Also clean up metadata
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up pendingProfileRequests for $requestKey")
                }
            }
        }
    }
    
    /**
     * SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
     */
    private fun checkAndUpdateCurrentRoomTimelineOptimized(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null && currentRoomId.isNotEmpty() && rooms.has(currentRoomId)) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Checking timeline diff for room: $currentRoomId")
                
                // Update timeline data first
                updateTimelineFromSync(syncJson, currentRoomId)
                
                // Check if timeline actually changed using diff-based detection
                val newTimelineStateHash = generateTimelineStateHash(timelineEvents)
                val timelineStateChanged = newTimelineStateHash != lastTimelineStateHash
                
                if (timelineStateChanged) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Timeline state changed, scheduling UI update")
                    needsTimelineUpdate = true
                    scheduleUIUpdate("timeline")
                    lastTimelineStateHash = newTimelineStateHash
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Timeline state unchanged, skipping UI update")
                }
            }
        }
    }
    
    private fun checkAndUpdateCurrentRoomTimeline(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null) {
                // Log all rooms in this sync_complete
                val roomKeys = rooms.keys()
                val roomsInSync = mutableListOf<String>()
                while (roomKeys.hasNext()) {
                    roomsInSync.add(roomKeys.next())
                }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sync_complete contains events for ${roomsInSync.size} rooms: $roomsInSync")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: currentRoomId = $currentRoomId (${if (currentRoomId.isNotEmpty()) "ROOM OPEN" else "NO ROOM OPEN"})")
                
                // Only update timeline if room is currently open
                if (currentRoomId.isNotEmpty() && rooms.has(currentRoomId)) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✓ Processing sync_complete events for OPEN room: $currentRoomId")
                    updateTimelineFromSync(syncJson, currentRoomId)
                } else if (currentRoomId.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - current room $currentRoomId not in this sync batch")
                    // Events are in-memory only - timeline is updated directly from sync_complete
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - no room currently open (events will be cached only)")
                }
            }
        }
    }
    
    /**
     * Cache timeline events from sync_complete for all rooms
     * This allows instant room opening if we have enough cached events
     */
    /**
     * Cache timeline events from sync for all rooms.
     * OPTIMIZED: Processes current room immediately, defers others to background thread.
     */
    private fun cacheTimelineEventsFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val rooms = data.optJSONObject("rooms") ?: return
        
        val roomKeys = rooms.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomData = rooms.optJSONObject(roomId) ?: continue
            val events = roomData.optJSONArray("events") ?: continue
            
            val memberMap = RoomMemberCache.getRoomMembers(roomId)
            RoomTimelineCache.addEventsFromSync(roomId, events, memberMap)
        }
    }
    private fun updateTimelineFromSync(syncJson: JSONObject, roomId: String) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null) {
                val roomData = rooms.optJSONObject(roomId)
                if (roomData != null) {
                    // Update room state if present
                    val meta = roomData.optJSONObject("meta")
                    if (meta != null) {
                        val name = meta.optString("name")?.takeIf { it.isNotBlank() }
                        val canonicalAlias = meta.optString("canonical_alias")?.takeIf { it.isNotBlank() }
                        val topic = meta.optString("topic")?.takeIf { it.isNotBlank() }
                        val avatarUrl = meta.optString("avatar")?.takeIf { it.isNotBlank() }
                        
                        if (name != null || canonicalAlias != null || topic != null || avatarUrl != null) {
                            val roomState = RoomState(
                                roomId = roomId,
                                name = name,
                                canonicalAlias = canonicalAlias,
                                topic = topic,
                                avatarUrl = avatarUrl,
                                powerLevels = currentRoomState?.powerLevels, // Preserve existing power levels
                                pinnedEventIds = currentRoomState?.pinnedEventIds ?: emptyList(), // Preserve existing pinned events
                                bridgeInfo = currentRoomState?.bridgeInfo
                            )
                            // ✓ Safety check: Only update if this is the currently open room
                            if (roomId == currentRoomId) {
                                currentRoomState = roomState
                            } 
                        }
                    }
                    
                    // Process new timeline events
                    val events = roomData.optJSONArray("events")
                    if (events != null && events.length() > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.length()} new timeline events for room: $roomId")
                        processSyncEventsArray(events, roomId)
                    }
                    
                    // NOTE: Read receipts are now processed for ALL rooms in processParsedSyncResult()
                    // This ensures receipts are updated even when rooms are not currently open
                    // No need to process receipts here - they're already handled globally
                }
            }
        }
    }
    private fun processSyncEventsArray(eventsArray: JSONArray, roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processSyncEventsArray called with ${eventsArray.length()} events")
        val memberMap = RoomMemberCache.getRoomMembers(roomId)
        
        // Process events in timestamp order for clean edit handling
        val events = mutableListOf<TimelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i)
            if (eventJson != null) {
                val event = TimelineEvent.fromJson(eventJson)
                events.add(event)
            }
        }
        
        // Sort events by timestamp to process in order
        // OPTIMIZED: Only sort if we have events to process
        if (events.isNotEmpty()) {
            events.sortBy { it.timestamp }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} events in timestamp order")
            
            // Count event types for debugging (lightweight operation, can defer if needed)
            val eventTypeCounts = events.groupBy { it.type }.mapValues { it.value.size }
            val ownMessageCount = events.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Event breakdown: $eventTypeCounts (including $ownMessageCount from YOU)")
        }
        
        // Skip processing if no events
        if (events.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No events to process")
            return
        }
        
        // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
        // Defer to background thread if we have many events
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} sync events for version tracking")
        if (events.size > 20) {
            viewModelScope.launch(Dispatchers.Default) {
                processVersionedMessages(events)
            }
        } else {
            processVersionedMessages(events)
        }
        
        for (event in events) {          
            if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                // State member event; update cache only
                val userId = event.stateKey ?: event.sender
                val content = event.content
                if (content != null) {
                    val displayName = content.optString("displayname")?.takeIf { it.isNotBlank() }
                    val avatarUrl = content.optString("avatar_url")?.takeIf { it.isNotBlank() }
                    if (displayName != null || avatarUrl != null) {
                        val profile = MemberProfile(displayName, avatarUrl)
                        RoomMemberCache.updateMember(roomId, userId, profile)
                        // PERFORMANCE: Also add to global cache for O(1) lookups
                        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
                    }
                }
            } else if (event.type == "m.room.member" && event.timelineRowid >= 0L) {
                // Timeline member event (join/leave that should show in timeline)
                // Also extract and update member profile if display name/avatar changed
                updateMemberProfileFromTimelineEvent(roomId, event)
                addNewEventToChain(event)
            } else if (event.type == "m.room.redaction") {
                
                // Add redaction event to timeline so findLatestRedactionEvent can find it
                addNewEventToChain(event)
                
                // Extract the event ID being redacted
                val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                
                if (redactsEventId != null) {
                    
                    // Find and update the original message in the timeline
                    val currentEvents = timelineEvents.toMutableList()
                    val originalIndex = currentEvents.indexOfFirst { it.eventId == redactsEventId }
                    
                    if (originalIndex >= 0) {
                        val originalEvent = currentEvents[originalIndex]
                        // Create a copy with redactedBy set
                        val redactedEvent = originalEvent.copy(redactedBy = event.eventId)
                        currentEvents[originalIndex] = redactedEvent
                        timelineEvents = currentEvents
                        
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Marked event $redactsEventId as redacted by ${event.eventId}")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Could not find event $redactsEventId to mark as redacted (might be in paginated history)")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Redaction event has no 'redacts' field")
                }
                
                // Request sender profile if missing from cache
                if (!memberMap.containsKey(event.sender)) {
                    requestUserProfile(event.sender, roomId)
                } 
                
                // OPTIMIZED: UI update will be triggered by buildTimelineFromChain() at the end of processSyncEventsArray
            } else if (event.type == "m.reaction") {
                // CRITICAL FIX: Add reaction events to cache so they can be restored when reopening room
                // Reaction events are processed in-memory AND cached for persistence
                RoomTimelineCache.mergePaginatedEvents(roomId, listOf(event))
                
                // Process reaction events (don't add to timeline)
                val content = event.content
                if (content != null) {
                    val relatesTo = content.optJSONObject("m.relates_to")
                    if (relatesTo != null) {
                        val relatesToEventId = relatesTo.optString("event_id")
                        val emoji = relatesTo.optString("key")
                        val relType = relatesTo.optString("rel_type")
                        
                        if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
                            // Check if this reaction has been redacted
                            if (event.redactedBy != null) {
                                // Remove this reaction by processing it as if the user toggled it off
                                val reactionEvent = ReactionEvent(
                                    roomId = roomId,
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = normalizeTimestamp(
                                        event.timestamp,
                                        event.unsigned?.optLong("age_ts") ?: 0L
                                    )
                                )
                                // Process the reaction - it will be removed since the user is already in the list
                                processReactionEvent(reactionEvent)
                            } else {
                                // Normal reaction, add it                             
                                // Process all reactions normally - no special handling for our own reactions
                                val reactionEvent = ReactionEvent(
                                    roomId = roomId,
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = normalizeTimestamp(
                                        event.timestamp,
                                        event.unsigned?.optLong("age_ts") ?: 0L
                                    )
                                )
                                processReactionEvent(reactionEvent)
                            }
                        }
                    }
                }
            } else if (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") {
                // Log if this is our own message from another client
                val isOwnMessage = event.sender == currentUserId
                if (isOwnMessage) {
                    val bodyPreview = when {
                        event.type == "m.room.message" -> event.content?.optString("body", "")?.take(50)
                        event.type == "m.room.encrypted" -> event.decrypted?.optString("body", "")?.take(50)
                        else -> ""
                    }
                }
                
                // Check if this is an edit event (m.replace relationship)
                val isEditEvent = when {
                    event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    else -> false
                }
                
                if (isEditEvent) {
                    // Handle edit event using edit chain system
                    handleEditEventInChain(event)
                } else {
                    // Add new timeline event to chain (works for messages from ANY client)
                    addNewEventToChain(event)
                }
            } else if (event.type == "m.room.pinned_events" || event.type == "m.room.name" || event.type == "m.room.topic" || event.type == "m.room.avatar") {
                // System events that should appear in timeline
                addNewEventToChain(event)
            } 
        }
        
        // Summary of what was processed
        val addedToTimeline = events.count { event ->
            (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") ||
            (event.type == "m.room.member" && event.timelineRowid >= 0L) ||
            (event.type == "m.room.redaction") ||
            (event.type == "m.room.pinned_events" || event.type == "m.room.name" || event.type == "m.room.topic" || event.type == "m.room.avatar")
        }
        val memberStateUpdates = events.count { event ->
            event.type == "m.room.member" && event.timelineRowid == -1L
        }
        val reactions = events.count { it.type == "m.reaction" }
        val unhandled = events.size - addedToTimeline - memberStateUpdates - reactions
        
        // Update room state from new timeline events (name/avatar) if present
        // CRITICAL: Only update room state if this is the currently open room
        if (roomId == currentRoomId) {
            updateRoomStateFromTimelineEvents(currentRoomId, events)
        }
        
        // Only process edit relationships for new edit events
        val newEditEvents = events.filter { event ->
            val isEditEvent = when {
                event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                else -> false
            }
            isEditEvent
        }
        
        if (newEditEvents.isNotEmpty()) {
            processNewEditRelationships(newEditEvents)
        }
        
        // CRITICAL FIX: Only rebuild timeline if this is the currently open room
        // Otherwise we're rebuilding the timeline for the wrong room, causing flickering
        if (roomId == currentRoomId) {
            // Build timeline from chain
            val timelineCountBefore = timelineEvents.size
            buildTimelineFromChain()
            val timelineCountAfter = timelineEvents.size
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Rebuilt timeline after processing sync events for current room $roomId: before=$timelineCountBefore, after=$timelineCountAfter")
            
            // Timeline is updated directly from sync_complete events via processSyncEventsArray()
            // No DB persistence or refresh needed - all data is in-memory
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping timeline rebuild for room $roomId (not currently open, currentRoomId=$currentRoomId)")
        }
        
        // Mark room as read for the newest event only if room is actually visible (not just a minimized bubble)
        // Check if this is a bubble and if it's visible
        val shouldMarkAsRead = if (BubbleTracker.isBubbleOpen(roomId)) {
            // Bubble exists - only mark as read if it's visible/maximized
            BubbleTracker.isBubbleVisible(roomId)
        } else {
            // Not a bubble - mark as read (normal room view)
            true
        }
        
        if (shouldMarkAsRead) {
            val newestEvent = events.maxByOrNull { it.timestamp }
            if (newestEvent != null) {
                markRoomAsRead(roomId, newestEvent.eventId)
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)")
        }
    }
    
    /**
     * Handles edit events using the edit chain tracking system.
     * This ensures clean edit handling by tracking the edit chain properly.
     */
    private fun handleEditEventInChain(editEvent: TimelineEvent) {        
        // Check if the edit event needs decryption
        val processedEditEvent = if (editEvent.type == "m.room.encrypted" && editEvent.decrypted == null) {
            // For now, store as-is - decryption should happen elsewhere
            editEvent
        } else {
            editEvent
        }
        
        // Store the edit event
        editEventsMap[editEvent.eventId] = processedEditEvent
        
        // Get the target event ID from the edit event
        val relatesTo = when {
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
            editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }
        
        val targetEventId = relatesTo?.optString("event_id")
        if (targetEventId != null) {
            
            // Find the target event in our chain mapping
            val targetEntry = eventChainMap[targetEventId]
            if (targetEntry != null) {
               
                // Update the target event's replacedBy field with the new edit
                targetEntry.replacedBy = editEvent.eventId
                
            } 
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Could not find target event ID in edit event ${editEvent.eventId}")
        }
    }
    
    /**
     * Adds a new timeline event to the edit chain.
     * Includes deduplication to prevent the same event from being added multiple times.
     */
    private fun addNewEventToChain(event: TimelineEvent) {
        
        // DEDUPLICATION: Check if event already exists in chain
        if (eventChainMap.containsKey(event.eventId)) {
            return
        }
        
        // Add regular event to chain mapping
        eventChainMap[event.eventId] = EventChainEntry(
            eventId = event.eventId,
            ourBubble = event,
            replacedBy = null,
            originalTimestamp = event.timestamp
        )

    }
    
    /**
     * Processes edit relationships for new edit events only.
     */
    private fun processNewEditRelationships(newEditEvents: List<TimelineEvent>) {
        
        // Process only the new edit events
        for (editEvent in newEditEvents) {
            val editEventId = editEvent.eventId
            
            // Get the target event ID from the edit event
            val relatesTo = when {
                editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
                editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
                else -> null
            }
            
            val targetEventId = relatesTo?.optString("event_id")
            if (targetEventId != null) {
                val targetEntry = eventChainMap[targetEventId]
                if (targetEntry != null) {
                    // Check if the target already has a replacement
                    if (targetEntry.replacedBy != null) {
                        // Find the end of the current chain and add this edit to the end
                        val chainEnd = findChainEnd(targetEntry.replacedBy!!)
                        if (chainEnd != null) {
                            chainEnd.replacedBy = editEventId
                        }
                    } else {
                        // First edit for this target
                        targetEntry.replacedBy = editEventId
                    }
                } 
            } 
        }
    }
    
    /**
     * Processes edit relationships to build the complete edit chain.
     */
    private fun processEditRelationships() {
        
        // Safety check: limit the number of edit events to prevent blocking
        if (editEventsMap.size > 100) {
            val limitedEditEvents = editEventsMap
                .values
                .sortedByDescending { it.timestamp }
                .take(100)
                .associateBy { it.eventId }
            editEventsMap.clear()
            editEventsMap.putAll(limitedEditEvents)
        }
        
        //android.util.Log.d("Andromuks", "processEditRelationships: editEventsMap size=${editEventsMap.size}")
        // NOTE: Edit events should NOT be added to eventChainMap - they are only stored in editEventsMap
        // Edit events are linked to their original events via the replacedBy field on the original event's entry
        //android.util.Log.d("Andromuks", "processEditRelationships: eventChainMap now has ${eventChainMap.size} entries")

        // Sort edit events by timestamp to process in chronological order
        val sortedEditEvents = editEventsMap.values.sortedBy { it.timestamp }
        //android.util.Log.d("Andromuks", "processEditRelationships: processing ${sortedEditEvents.size} edit events in timestamp order")

        for (editEvent in sortedEditEvents) {
            val editEventId = editEvent.eventId

            val relatesTo = when {
                editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
                editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
                else -> null
            }

            val targetEventId = relatesTo?.optString("event_id")
            if (targetEventId != null) {
                val targetEntry = eventChainMap[targetEventId]
                if (targetEntry != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d(
                        "Andromuks",
                        "processEditRelationships: edit ${editEventId} targets ${targetEventId} (current replacedBy=${targetEntry.replacedBy})"
                    )
                    if (targetEntry.replacedBy != null) {
                        val chainEnd = findChainEndOptimized(targetEntry.replacedBy!!, mutableMapOf())
                        if (chainEnd != null) {
                            if (BuildConfig.DEBUG) android.util.Log.d(
                                "Andromuks",
                                "processEditRelationships: extending chain end ${chainEnd.eventId} with ${editEventId}"
                            )
                            chainEnd.replacedBy = editEventId
                        } else {
                            android.util.Log.w(
                                "Andromuks",
                                "processEditRelationships: could not find chain end for ${targetEntry.replacedBy}; replacing with ${editEventId}"
                            )
                            targetEntry.replacedBy = editEventId
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "Andromuks",
                            "processEditRelationships: first edit for ${targetEventId} is ${editEventId}"
                        )
                        targetEntry.replacedBy = editEventId
                    }
                } else {
                    android.util.Log.w(
                        "Andromuks",
                        "processEditRelationships: target entry missing for edit ${editEventId} (target=${targetEventId})"
                    )
                }
            } else {
                android.util.Log.w(
                    "Andromuks",
                    "processEditRelationships: edit ${editEventId} missing relates_to event_id"
                )
            }
        }
    }
    
    /**
     * Finds the end of an edit chain by following replacedBy links.
     * OPTIMIZED: Now uses memoization to avoid repeated traversals (O(n²) -> O(n))
     */
    private fun findChainEndOptimized(startEventId: String, cache: MutableMap<String, EventChainEntry?>): EventChainEntry? {
        // Check cache first
        if (cache.containsKey(startEventId)) {
            return cache[startEventId]
        }
        
        var currentId = startEventId
        var currentEntry = eventChainMap[currentId]
        
        // Follow the chain to find the end
        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            // Check if we've already cached this chain end
            if (cache.containsKey(currentId)) {
                val cachedEnd = cache[currentId]
                cache[startEventId] = cachedEnd
                return cachedEnd
            }
            currentEntry = eventChainMap[currentId]
        }
        
        // Cache the result for future lookups
        cache[startEventId] = currentEntry
        return currentEntry
    }
    
    /**
     * LEGACY: Finds the end of an edit chain by following replacedBy links.
     * Kept for backward compatibility but should use findChainEndOptimized instead.
     */
    private fun findChainEnd(startEventId: String): EventChainEntry? {
        var currentId = startEventId
        var currentEntry = eventChainMap[currentId]
        
        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            currentEntry = eventChainMap[currentId]
        }
        
        return currentEntry
    }
    
    /**
     * Builds the timeline from the edit chain mapping.
     * OPTIMIZED: Combined event processing and redaction handling in single pass.
     */
    private fun buildTimelineFromChain() {
        // DIAGNOSTIC: Track when this is called
        val callStack = Thread.currentThread().stackTrace.take(5).joinToString(" -> ") { it.methodName }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "buildTimelineFromChain: Called (eventChainMap.size=${eventChainMap.size}, currentRoomId=$currentRoomId, callStack=$callStack)"
        )
        
        try {
            val timelineEvents = mutableListOf<TimelineEvent>()
            val redactionMap = mutableMapOf<String, String>() // Map of target eventId -> redaction eventId
            
            // THREAD SAFETY: Create snapshot of map entries to prevent ConcurrentModificationException
            // This prevents crashes when the map is modified concurrently (e.g., by background coroutines)
            // Use synchronized block to safely create snapshot even if map is being modified
            val eventChainSnapshot = try {
                synchronized(eventChainMap) {
                    eventChainMap.toMap()
                }
            } catch (e: ConcurrentModificationException) {
                android.util.Log.w("Andromuks", "AppViewModel: ConcurrentModificationException while creating snapshot, retrying", e)
                // Retry once - create a new map with current entries
                eventChainMap.toMap()
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "buildTimelineFromChain: Created snapshot with ${eventChainSnapshot.size} entries")
        
            // First collect all redactions so order doesn't matter
            for ((_, entry) in eventChainSnapshot) {
                val redactionEvent = entry.ourBubble
                if (redactionEvent != null && redactionEvent.type == "m.room.redaction") {
                    val redactsEventId = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    if (redactsEventId != null) {
                        redactionMap[redactsEventId] = redactionEvent.eventId
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "Andromuks",
                            "buildTimelineFromChain: redaction ${redactionEvent.eventId} targets $redactsEventId"
                        )
                    }
                }
            }

            // DIAGNOSTIC: Log snapshot statistics
            val totalEntries = eventChainSnapshot.size
            var nullBubbleCount = 0
            var redactionCount = 0
            var processedCount = 0
            var errorCount = 0
            
            // Now process all non-redaction events with the populated map
            for ((eventId, entry) in eventChainSnapshot) {
                val ourBubble = entry.ourBubble
                if (ourBubble == null) {
                    nullBubbleCount++
                    android.util.Log.w("Andromuks", "buildTimelineFromChain: Entry $eventId has null ourBubble - skipping")
                    continue
                }
                if (ourBubble.type == "m.room.redaction") {
                    redactionCount++
                    continue
                }
                try {
                    // Apply redaction if this event is targeted
                    val redactedBy = redactionMap[eventId]
                    val finalEvent = if (redactedBy != null) {
                        val baseEvent = getFinalEventForBubble(entry)
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "Andromuks",
                            "buildTimelineFromChain: applying redaction $redactedBy to event $eventId"
                        )
                        baseEvent.copy(redactedBy = redactedBy)
                    } else {
                        getFinalEventForBubble(entry)
                    }
                    
                    timelineEvents.add(finalEvent)
                    processedCount++
                    if (finalEvent.redactedBy != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "Andromuks",
                            "buildTimelineFromChain: timeline event ${finalEvent.eventId} marked redacted by ${finalEvent.redactedBy}"
                        )
                    }
                    //android.util.Log.d("Andromuks", "AppViewModel: Added event for ${eventId} with final content from ${entry.replacedBy ?: eventId}${if (redactedBy != null) " (redacted by $redactedBy)" else ""}")
                } catch (e: Exception) {
                    errorCount++
                    android.util.Log.e("Andromuks", "AppViewModel: Error processing event ${eventId} in buildTimelineFromChain", e)
                    // Skip this event if there's an error (prevents crash from corrupt edit chain)
                    // Add the base event without following edit chain as fallback
                    // ourBubble is guaranteed to be non-null here because we checked and continued if null above
                    timelineEvents.add(ourBubble)
                    processedCount++
                }
            }
            
            // DIAGNOSTIC: Log summary
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "buildTimelineFromChain: Processed $processedCount events from $totalEntries entries " +
                "(nullBubble=$nullBubbleCount, redactions=$redactionCount, errors=$errorCount, " +
                "timelineEvents.size=${timelineEvents.size})"
            )
            
            // Sort by timestamp and update timeline
            val sortedTimelineEvents = timelineEvents.sortedBy { it.timestamp }
            
            // Detect new messages for animation
            val previousEventIds = this.timelineEvents.map { it.eventId }.toSet()
            val newEventIds = sortedTimelineEvents.map { it.eventId }.toSet()
            val actuallyNewMessages = newEventIds - previousEventIds
            
            // Check if this is initial room loading (when previous timeline was empty)
            val isInitialRoomLoad = this.timelineEvents.isEmpty() && sortedTimelineEvents.isNotEmpty()
            
            // Track new messages for sound notifications (animations removed for performance)
            val roomOpenTimestamp = roomOpenTimestamps[currentRoomId] // Get timestamp when room was opened
            
            if (actuallyNewMessages.isNotEmpty() && !isInitialRoomLoad && roomOpenTimestamp != null) {
                val currentTime = System.currentTimeMillis()
                
                // Check if any of the new messages are from other users (not our own messages)
                var shouldPlaySound = false
                var hasMessageForCurrentRoom = false
                var newMessageRoomId: String? = null
                
                actuallyNewMessages.forEach { eventId ->
                    val newEvent = sortedTimelineEvents.find { it.eventId == eventId }
                    
                    // Track new messages for sound notification (only messages newer than room open)
                    val isNewMessage = newEvent?.let { event ->
                        event.timestamp > roomOpenTimestamp
                    } ?: false
                    
                    if (isNewMessage) {
                        newMessageAnimations[eventId] = currentTime
                    }
                    
                    // Check if this message is from another user (not our own message) for sound notification
                    val isFromOtherUser = newEvent?.let { event ->
                        // Track which room this message is for
                        newMessageRoomId = event.roomId
                        
                        // Check if message is for current room
                        if (event.roomId == currentRoomId) {
                            hasMessageForCurrentRoom = true
                        }
                        
                        // Only play sound for message events from other users
                        (event.type == "m.room.message" || event.type == "m.room.encrypted") && 
                        event.sender != currentUserId
                    } ?: false
                    
                    if (isFromOtherUser) {
                        shouldPlaySound = true
                    }
                }
                
                // SOUND REMOVED: No longer play sound for received messages (animations removed)
                // Sound is now only played for messages we send (handled in TimelineEventItem)
                // This keeps the UI quiet for incoming messages while still providing feedback for our own sends
                if (shouldPlaySound && BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Sound suppressed for received message in room $newMessageRoomId (sound only plays for messages we send)")
                }
            }
            
            // No limit on timeline events - all events are kept in cache
            // LRU eviction in RoomTimelineCache handles memory management
            this.timelineEvents = sortedTimelineEvents
            // PERFORMANCE FIX: Removed persistRenderableEvents() from buildTimelineFromChain()
            // Pre-rendering on every sync was causing heavy CPU load with 580+ rooms.
            // Timeline is now rendered lazily when room is opened, not on every sync_complete.
            // The renderable table is only populated on room open via processCachedEvents().
            timelineUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error in buildTimelineFromChain", e)
            // If building timeline fails, try to preserve existing timeline if possible
            // This prevents the UI from going completely blank
            if (this.timelineEvents.isEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed and timeline is empty, keeping empty timeline")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed, preserving existing ${this.timelineEvents.size} events")
            }
            // Re-throw to let caller handle it
            throw e
        }
    }
    /**
     * Load older messages using backend pagination (cache-only approach, no DB loading)
     * Replaced DB-based loading with backend pagination request
     */
    fun loadOlderMessages(roomId: String, showToast: Boolean = true) {
        // CACHE-ONLY APPROACH: Use backend pagination instead of DB loading
        // requestPaginationWithSmallestRowId() handles backend pagination using cache-only
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: loadOlderMessages($roomId) - using backend pagination")
        requestPaginationWithSmallestRowId(roomId, limit = 100)
    }
    
    /**
     * Request pagination from backend using the smallest row ID from cache only (no DB).
     * Used for pull-to-refresh to load older events.
     * 
     * @param roomId The room ID to paginate
     * @param limit Number of events to fetch (default 100)
     */
    fun requestPaginationWithSmallestRowId(roomId: String, limit: Int = 100) {
        if (isPaginating) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Already paginating, skipping request for $roomId")
            return
        }
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot paginate for $roomId")
            return
        }
        
        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: No app context, cannot paginate for $roomId")
            return
        }
        
        // CACHE-ONLY APPROACH: Use cache to determine oldest event (fallback to tracked value)
        isPaginating = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // CRITICAL FIX: Prefer tracked value (from previous pagination response) over cache value
                // This ensures we use the actual oldest event from the last response, not the cache's
                // oldest event which might be stale if duplicates were filtered out
                // According to Webmucks backend: pass timeline_rowid as-is (positive or negative)
                // - Positive N means "return events with timeline.rowid < N" (older than N)
                // - Negative -N means "return events with timeline.rowid < -N" (even older, more-negative IDs)
                // - 0 means "no upper bound / fetch recent" (ONLY use when cache is empty)
                val cacheEventCount = RoomTimelineCache.getCachedEventCount(roomId)
                val oldestCachedRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
                val oldestTrackedRowId = oldestRowIdPerRoom[roomId]
                
                // CRITICAL FIX: Prefer tracked value over cached value to avoid getting stuck on same max_timeline_id
                // The tracked value is updated from the pagination response and represents the actual oldest
                // event we received, while the cache value might be stale if duplicates were filtered out
                // Pass the value as-is (positive or negative) - backend handles both correctly
                // Only use 0 when cache is actually empty (no events at all)
                val oldestRowId = when {
                    oldestTrackedRowId != null && oldestTrackedRowId != -1L -> oldestTrackedRowId
                    oldestCachedRowId != -1L -> oldestCachedRowId
                    cacheEventCount == 0 -> {
                        // Cache is empty - use 0 to request most recent events
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "AppViewModel: Cache is empty for room $roomId. Using max_timeline_id=0 to request most recent events.")
                        }
                        0L
                    }
                    else -> {
                        // Cache has events but we couldn't get oldest row ID - this shouldn't happen
                        // Log warning and use 0 as fallback
                        android.util.Log.w("Andromuks", "AppViewModel: ⚠️ Cache has $cacheEventCount events for room $roomId but couldn't get oldest timelineRowId (cached=$oldestCachedRowId, tracked=$oldestTrackedRowId). Using max_timeline_id=0 as fallback.")
                        0L
                    }
                }
                
                // Use the oldest rowId to request events older than this
                // Note: max_timeline_id=0 means "get the oldest available events"
                val maxTimelineId = oldestRowId
                
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Using oldest timelineRowId=$oldestRowId (cached=$oldestCachedRowId, tracked=$oldestTrackedRowId) as max_timeline_id=$maxTimelineId for room $roomId")
                }
                
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Requesting pagination for $roomId with oldest timelineRowId=$oldestRowId, max_timeline_id=$maxTimelineId, limit=$limit"
                )
                
                val paginateRequestId = requestIdCounter++
                paginateRequests[paginateRequestId] = roomId
                paginateRequestMaxTimelineIds[paginateRequestId] = maxTimelineId // Track max_timeline_id used for progress detection
                
                // Log paginate request
                android.util.Log.d("Andromuks", "paginate: Room - $roomId, MaxTimelineRowID - $maxTimelineId, Limit - $limit")
                
                val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                    "room_id" to roomId,
                    "max_timeline_id" to maxTimelineId,
                    "limit" to limit,
                    "reset" to false
                ))
                
                if (result != WebSocketResult.SUCCESS) {
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to send paginate request for $roomId: $result")
                    withContext(Dispatchers.Main) {
                        paginateRequests.remove(paginateRequestId)
                        paginateRequestMaxTimelineIds.remove(paginateRequestId)
                        isPaginating = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error determining oldest timelineRowId", e)
                withContext(Dispatchers.Main) {
                    isPaginating = false
                }
            }
        }
    }
    
    private fun mergePaginationEvents(newEvents: List<TimelineEvent>) {
        if (newEvents.isEmpty()) {
            return
        }

        // Update version caches with newly loaded events (edits, redactions, originals)
        processVersionedMessages(newEvents)

        // Integrate the new events into the edit-chain structures so buildTimelineFromChain()
        // can regenerate the final timeline with the latest edits applied.
        for (event in newEvents) {
            when {
                isEditEvent(event) -> {
                    editEventsMap[event.eventId] = event
                }
                else -> {
                    val existingEntry = eventChainMap[event.eventId]
                    if (existingEntry == null) {
                        eventChainMap[event.eventId] = EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp
                        )
                    } else if (existingEntry.ourBubble == null) {
                        eventChainMap[event.eventId] = existingEntry.copy(ourBubble = event)
                    }

                    if (event.type == "m.room.redaction") {
                        val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                        if (redactsEventId != null) {
                            // redactionCache is computed from messageVersions, handled by MessageVersionsCache.updateVersion
                            val versioned = messageVersions[redactsEventId]
                            if (versioned != null) {
                                MessageVersionsCache.updateVersion(redactsEventId, versioned.copy(
                                    redactedBy = event.eventId,
                                    redactionEvent = event
                                ))
                            } else {
                                // Redaction came before the original event - try to find original in cache
                                // This happens when pagination returns redaction before the original message
                                val originalEvent = RoomTimelineCache.getCachedEvents(event.roomId)?.find { it.eventId == redactsEventId }
                                
                                if (originalEvent != null) {
                                    // Found original event in cache - create VersionedMessage with redaction
                                    MessageVersionsCache.updateVersion(redactsEventId, VersionedMessage(
                                        originalEventId = redactsEventId,
                                        originalEvent = originalEvent,
                                        versions = emptyList(),
                                        redactedBy = event.eventId,
                                        redactionEvent = event
                                    ))
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: mergePaginationEvents - Redaction event ${event.eventId} received before original $redactsEventId, but found original in cache")
                                } else {
                                    // Original not in cache yet - create placeholder so redaction can be found
                                    // We'll use the redaction event itself as a temporary originalEvent
                                    // This will be updated when the original event arrives
                                    MessageVersionsCache.updateVersion(redactsEventId, VersionedMessage(
                                        originalEventId = redactsEventId,
                                        originalEvent = event,  // Temporary placeholder
                                        versions = emptyList(),
                                        redactedBy = event.eventId,
                                        redactionEvent = event
                                    ))
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: mergePaginationEvents - Redaction event ${event.eventId} received before original $redactsEventId - created placeholder")
                                }
                            }
                        }
                    }
                }
            }
        }

        processEditRelationships()
        buildTimelineFromChain()
    }
    /**
     * Gets the final event for a bubble, following the edit chain to the latest edit.
     */
    private fun getFinalEventForBubble(entry: EventChainEntry): TimelineEvent {
        // Safety check: ensure ourBubble is not null
        val initialBubble = entry.ourBubble
        if (initialBubble == null) {
            android.util.Log.e("Andromuks", "AppViewModel: getFinalEventForBubble called with null ourBubble for event ${entry.eventId}")
            throw IllegalStateException("Entry ${entry.eventId} has null ourBubble")
        }
        
        // Explicitly type as non-null since we've checked for null above
        var currentEvent: TimelineEvent = requireNotNull(initialBubble) { "ourBubble should be non-null after null check" }
        var currentEntry = entry
        val visitedEvents = mutableSetOf<String>() // Prevent infinite loops
        
        
        // Follow the edit chain to find the latest edit
        var chainDepth = 0
        while (currentEntry.replacedBy != null) {
            // Safety check: limit chain depth to prevent infinite loops
            if (chainDepth >= 20) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ChainDepth >= 20, we have to break")
                break
            }
            chainDepth++
            
            val editEventId = currentEntry.replacedBy!!
            
            // Check for infinite loop - only check if we're trying to visit an event we've already processed in this chain
            if (visitedEvents.contains(editEventId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Infinite loop detected! Edit event ${editEventId} already visited in this chain")
                break
            }
            visitedEvents.add(editEventId)
            
            val editEventNullable = editEventsMap[editEventId]
            if (editEventNullable == null) {
                android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} not found in edit events map")
                break
            }
            
            // editEvent is guaranteed non-null here due to the null check above
            // Use requireNotNull to ensure the compiler recognizes it as non-null
            val editEvent = requireNotNull(editEventNullable) { "Edit event $editEventId should be non-null after null check" }
            
            // Merge the edit content into the current event
            currentEvent = mergeEditContent(currentEvent, editEvent)
            
            // Update current entry to continue following the chain
            // Edit events have their own chain entries, so we can follow them
            val nextEntry = eventChainMap[editEventId]
            if (nextEntry == null) {
                break
            }
            
            // Check if the next entry is the same as the current entry (infinite loop)
            if (nextEntry.eventId == currentEntry.eventId) {
                android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} points to itself, breaking chain")
                break
            }
            
            currentEntry = nextEntry
        }
        
        return currentEvent
    }
    /**
     * Finds events that are superseded by a new event.
     * 
     * @param newEvent The new event that might supersede others
     * @param existingEvents List of existing events to check
     * @return List of event IDs that are superseded by the new event
     */
    private fun findSupersededEvents(newEvent: TimelineEvent, existingEvents: List<TimelineEvent>): List<String> {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: findSupersededEvents called for event ${newEvent.eventId}")
        val supersededEventIds = mutableListOf<String>()
        
        // Check if this is an edit event (m.replace relationship)
        val relatesTo = when {
            newEvent.type == "m.room.message" -> newEvent.content?.optJSONObject("m.relates_to")
            newEvent.type == "m.room.encrypted" && newEvent.decryptedType == "m.room.message" -> newEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: relatesTo for event ${newEvent.eventId}: $relatesTo")
        
        val relatesToEventId = relatesTo?.optString("event_id")
        val relType = relatesTo?.optString("rel_type")
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: relatesToEventId: $relatesToEventId, relType: $relType")
        
        if (relType == "m.replace" && relatesToEventId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: This is an edit event targeting $relatesToEventId")
            // This is an edit event - find the original event it replaces
            val originalEvent = existingEvents.find { it.eventId == relatesToEventId }
            if (originalEvent != null) {
                supersededEventIds.add(originalEvent.eventId)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Edit event ${newEvent.eventId} supersedes original event ${originalEvent.eventId}")
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WARNING - Could not find original event $relatesToEventId in existing events")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Available event IDs: ${existingEvents.map { it.eventId }}")
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Not an edit event (relType: $relType, relatesToEventId: $relatesToEventId)")
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Returning superseded event IDs: $supersededEventIds")
        return supersededEventIds
    }
    
    /**
     * Merges edit content into the original event.
     * 
     * This function takes the original event and merges the new content from an edit event,
     * preserving the original event's structure while updating the content.
     * 
     * @param originalEvent The original event to be updated
     * @param editEvent The edit event containing the new content
     * @return A new TimelineEvent with merged content
     */
    private fun mergeEditContent(originalEvent: TimelineEvent, editEvent: TimelineEvent): TimelineEvent {
        // Create a new content JSON object based on the original event
        val mergedContent = JSONObject(originalEvent.content.toString())
        
        // Get the new content from the edit event
        // For encrypted rooms, look in decrypted field; for non-encrypted rooms, look in content field
        val newContent = when {
            editEvent.type == "m.room.encrypted" -> editEvent.decrypted?.optJSONObject("m.new_content")
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.new_content")
            else -> null
        }
        if (newContent != null) {
            // Create a completely new decrypted content object with the new content
            // Use the new content directly as the merged decrypted content
            val mergedDecrypted = JSONObject(newContent.toString())

            // For non-encrypted rooms, also update the content field
            val finalContent = if (originalEvent.type == "m.room.message") {
                // For non-encrypted rooms, update the content field with new content
                val updatedContent = JSONObject(originalEvent.content.toString())
                updatedContent.put("body", newContent.optString("body", ""))
                updatedContent.put("msgtype", newContent.optString("msgtype", "m.text"))
                updatedContent
            } else {
                // For encrypted rooms, keep the original content
                mergedContent
            }
            
            // Create the merged event with updated content
            val mergedEvent = originalEvent.copy(
                content = finalContent,
                decrypted = mergedDecrypted
            )
            return mergedEvent
        }
        // If no new content, return the original event
        return originalEvent
    }
    
    
    fun markRoomAsRead(roomId: String, eventId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: markRoomAsRead called with roomId: '$roomId', eventId: '$eventId'")
        
        // DEDUPLICATION: Check if we've already sent this exact mark_read command
        // This prevents hundreds of duplicate commands when LaunchedEffect restarts
        val lastSentEventId = lastMarkReadSent[roomId]
        if (lastSentEventId == eventId) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate mark_read for room $roomId with event $eventId (already sent)")
            // Still optimistically clear unread counts even if we skip the command
            optimisticallyClearUnreadCounts(roomId)
            return
        }
        
        // Track this command to prevent duplicates
        lastMarkReadSent[roomId] = eventId
        
        // Optimistically clear unread counts immediately for instant UI feedback
        // The backend will confirm this in the next sync_complete, but we update UI now
        optimisticallyClearUnreadCounts(roomId)
        
        // Try to mark as read immediately
        val result = markRoomAsReadInternal(roomId, eventId)
        
        // If WebSocket is not available, queue the operation for retry when connection is restored
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: markRoomAsRead failed with result: $result - queuing for retry when connection is restored")
            addPendingOperation(
                PendingWebSocketOperation(
                    type = "markRoomAsRead",
                    data = mapOf(
                        "roomId" to roomId,
                        "eventId" to eventId
                    )
                ),
                saveToStorage = true // Save to storage when WebSocket is disconnected
            )
        }
    }
    
    /**
     * Optimistically clear unread counts for a room when marking as read.
     * This provides instant UI feedback before the backend sends updated counts in sync_complete.
     */
    private fun optimisticallyClearUnreadCounts(roomId: String) {
        // Update in-memory state if the room exists in roomMap
        val existingRoom = roomMap[roomId]
        if (existingRoom != null && ((existingRoom.unreadCount != null && existingRoom.unreadCount > 0) || 
            (existingRoom.highlightCount != null && existingRoom.highlightCount > 0))) {
            // Update room with cleared unread counts
            val updatedRoom = existingRoom.copy(
                unreadCount = null,
                highlightCount = null
            )
            roomMap[roomId] = updatedRoom
            
            // Update allRooms to reflect the change immediately
            val updatedAllRooms = allRooms.map { room ->
                if (room.id == roomId) updatedRoom else room
            }
            allRooms = updatedAllRooms
            
            // Also update spaces list if needed
            if (spaceList.isNotEmpty()) {
                val updatedSpaces = spaceList.map { space ->
                    val updatedSpaceRooms = space.rooms.map { room ->
                        if (room.id == roomId) updatedRoom else room
                    }
                    space.copy(rooms = updatedSpaceRooms)
                }
                setSpaces(updatedSpaces, skipCounterUpdate = false)
            }
        }
        
        // Invalidate cache to force recalculation of sections and badge counts
        invalidateRoomSectionCache()
        
        // Trigger UI update - all data is in-memory only
        roomListUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked room $roomId as read (in-memory only)")
    }
    
    private fun markRoomAsReadInternal(roomId: String, eventId: String): WebSocketResult {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadInternal called")
        val markReadRequestId = requestIdCounter++
        
        // PHASE 5.2: sendWebSocketCommand() now automatically tracks all commands with positive request_id
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        
        if (result == WebSocketResult.SUCCESS) {
            markReadRequests[markReadRequestId] = roomId
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Mark read queued with request_id: $markReadRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send mark read, result: $result")
        }
        
        return result
    }
    
    fun getRoomSummary(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Getting room summary for invite: $roomId")
        
        val summaryRequestId = requestIdCounter++
        roomSummaryRequests[summaryRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("get_room_summary", summaryRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
    }
    
    fun acceptRoomInvite(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Accepting room invite: $roomId")
        
        // CRITICAL: Preemptively mark room as newly joined so it appears at top when sync arrives
        newlyJoinedRoomIds.add(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preemptively marked room $roomId as newly joined")
        
        val acceptRequestId = requestIdCounter++
        joinRoomRequests[acceptRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("join_room", acceptRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
        
        // Remove from pending invites (in-memory only)
        PendingInvitesCache.removeInvite(roomId)
        
        // Invites are in-memory only - no local cleanup needed
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed invite from memory: $roomId")
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun refuseRoomInvite(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refusing room invite: $roomId")
        
        val refuseRequestId = requestIdCounter++
        leaveRoomRequests[refuseRequestId] = roomId
        sendWebSocketCommand("leave_room", refuseRequestId, mapOf(
            "room_id" to roomId
        ))
        
        // Remove from pending invites (in-memory only)
        PendingInvitesCache.removeInvite(roomId)
        
        // Invites are in-memory only - no local cleanup needed
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed invite from memory: $roomId")
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun handleMarkReadResponse(requestId: Int, success: Boolean) {
        val roomId = markReadRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Mark read response for room $roomId: $success")
            // Remove the request from pending
            markReadRequests.remove(requestId)
            
            // PHASE 5.2: Acknowledgment is now handled in handleResponse() for all commands
            // No need to acknowledge here separately
            
            // Invoke completion callback for notification actions
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        }
    }
    
    /**
     * PHASE 5.2/5.3: Handle message acknowledgment by request_id (source of truth)
     * Backend responds with same request_id, so we match by request_id stored in operation data
     * Finds message in queue, marks as acknowledged, and removes from queue
     * 
     * This is called from handleResponse() and handleError() for all commands with positive request_id
     */
    private fun handleMessageAcknowledgmentByRequestId(requestId: Int) {
        val operation: PendingWebSocketOperation? = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.find { op ->
                (op.data["requestId"] as? Int) == requestId
            }
        }
        if (operation != null) {
            val command = operation.data["command"] as? String ?: operation.type.removePrefix("command_")
            val elapsed = System.currentTimeMillis() - operation.timestamp
            android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.3 - Command acknowledged by request_id: requestId=$requestId, command=$command, type=${operation.type}, elapsed=${elapsed}ms")
            logActivity("Command Acknowledged - $command (request_id: $requestId)", null)
            synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.remove(operation)
            }
            savePendingOperationsToStorage()
            if (BuildConfig.DEBUG) {
                val remaining = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
                android.util.Log.d("Andromuks", "AppViewModel: Removed acknowledged command from queue ($remaining remaining)")
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - No pending operation found for requestId: $requestId (may have been already acknowledged, not tracked, or request_id=0)")
        }
    }
    
    fun handleRoomSummaryResponse(requestId: Int, data: Any) {
        val roomId = roomSummaryRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room summary response for room $roomId")
            // Room summary received - could be used to update invite details if needed
            roomSummaryRequests.remove(requestId)
        }
    }
    
    fun handleJoinRoomResponse(requestId: Int, data: Any) {
        val roomId = joinRoomRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Join room response for room $roomId")
            // Room join successful - invite will be removed from sync
            joinRoomRequests.remove(requestId)
        }
    }
    
    fun leaveRoom(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Leaving room: $roomId")
        
        val leaveRequestId = requestIdCounter++
        leaveRoomRequests[leaveRequestId] = roomId
        sendWebSocketCommand("leave_room", leaveRequestId, mapOf(
            "room_id" to roomId
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent leave_room command for $roomId with requestId=$leaveRequestId")
    }
    
    fun handleLeaveRoomResponse(requestId: Int, data: Any) {
        val roomId = leaveRoomRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Leave room response for room $roomId")
            // Room leave successful - room will be removed from sync
            leaveRoomRequests.remove(requestId)
        }
    }
    /**
     * Send WebSocket command to the backend
     * Commands are sent individually with sequential request IDs
     */
    private fun sendRawWebSocketCommand(command: String, requestId: Int, data: Any?): WebSocketResult {
        // REFACTORING: Convert data to Map format for WebSocketService.sendCommand()
        val dataMap = when (data) {
            null -> emptyMap<String, Any>()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                data as Map<String, Any>
            }
            is JSONObject -> {
                // Convert JSONObject to Map
                val map = mutableMapOf<String, Any>()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = data.opt(key) ?: ""
                }
                map
            }
            else -> {
                // For complex types, fall back to direct WebSocket send
                // This is a legacy path - most commands should use Map<String, Any>
                android.util.Log.w("Andromuks", "sendRawWebSocketCommand: Complex data type, using legacy path: ${data::class.simpleName}")
                // Use WebSocketService.getWebSocket() and send directly for complex types
                return try {
                    val json = JSONObject()
                    json.put("command", command)
                    json.put("request_id", requestId)
                    json.put("data", data)
                    val jsonString = json.toString()
                    val ws = WebSocketService.getWebSocket()
                    if (ws == null) {
                        android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, cannot send command: $command")
                        return WebSocketResult.NOT_CONNECTED
                    }
                    // Legacy path: direct send for complex data types
                    ws.send(jsonString)
                    WebSocketResult.SUCCESS
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to send raw WebSocket command: $command", e)
                    WebSocketResult.CONNECTION_ERROR
                }
            }
        }

        // Normalize send_to_device payload
        val normalizedData = if (command == "send_to_device" && dataMap.isNotEmpty()) {
            val eventType = (dataMap["event_type"] as? String) ?: (dataMap["type"] as? String) ?: ""
            val normalized = mutableMapOf<String, Any>()
            if (eventType.isNotBlank()) {
                normalized["event_type"] = eventType
            }
            if (dataMap.containsKey("encrypted")) {
                normalized["encrypted"] = dataMap["encrypted"] ?: false
            }
            if (dataMap.containsKey("messages")) {
                normalized["messages"] = dataMap["messages"] ?: emptyList<Any>()
            }
            // Copy other fields
            dataMap.forEach { (key, value) ->
                if (!normalized.containsKey(key)) {
                    normalized[key] = value
                }
            }
            normalized
        } else {
            dataMap
        }

        // Use WebSocketService.sendCommand()
        return if (WebSocketService.sendCommand(command, requestId, normalizedData)) {
            WebSocketResult.SUCCESS
        } else {
            WebSocketResult.CONNECTION_ERROR
        }
    }

    fun sendWidgetCommand(command: String, data: Any?, onResult: (Result<Any?>) -> Unit) {
        val requestId = requestIdCounter++
        val deferred = CompletableDeferred<Any?>()
        widgetCommandRequests[requestId] = deferred

        val result = sendRawWebSocketCommand(command, requestId, data)
        if (result != WebSocketResult.SUCCESS) {
            widgetCommandRequests.remove(requestId)
            onResult(Result.failure(IllegalStateException("WebSocket not connected")))
            return
        }

        viewModelScope.launch {
            val response = withTimeoutOrNull(30_000L) { deferred.await() }
            if (response == null) {
                widgetCommandRequests.remove(requestId)
                onResult(Result.failure(java.util.concurrent.TimeoutException("Widget command timeout")))
            } else {
                onResult(Result.success(response))
            }
        }
    }

    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult {
        // Handle offline mode
        if (isOfflineMode && !isOfflineCapableCommand(command)) {
            android.util.Log.w("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Command $command queued for offline retry")
            queueCommandForOfflineRetry(command, requestId, data)
            return WebSocketResult.NOT_CONNECTED
        }
        
        // REFACTORING: Use WebSocketService.sendCommand() API instead of direct WebSocket access
        // Check connection state first
        if (!WebSocketService.isWebSocketConnected()) {
            // CRITICAL FIX: Queue command for retry when WebSocket is disconnected
            // This prevents commands from being lost when WebSocket temporarily disconnects
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, queuing command: $command (requestId: $requestId)")
            queueCommandForOfflineRetry(command, requestId, data)
            return WebSocketResult.NOT_CONNECTED
        }
        
        // CRITICAL FIX: Block commands until init_complete arrives and all initial sync_complete messages are processed
        // This prevents get_room_state commands from being sent before rooms are populated from sync_complete
        // Only applies on initial connection (not reconnections with last_received_event)
        // EXCEPTION: Allow get_room_state commands during initial room state loading (they're exempt from blocking)
        val isInitialRoomStateLoading = allRoomStatesRequested && !allRoomStatesLoaded
        val isExemptCommand = command == "get_room_state" && isInitialRoomStateLoading
        
        if (!canSendCommandsToBackend && !isExemptCommand) {
            synchronized(pendingCommandsQueue) {
                pendingCommandsQueue.add(Triple(command, requestId, data))
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Command $command (requestId: $requestId) queued - waiting for init_complete + sync_complete processing (${pendingCommandsQueue.size} commands queued)")
            }
            return WebSocketResult.NOT_CONNECTED
        }
        
        // REMOVED: Queue flushing blocking behavior
        // Webmuks can handle out-of-order messages and responses are matched by request_id
        // There's no need to block new commands while retrying old ones
        // New commands can be sent immediately, and responses will be processed as they arrive
        
        // REFACTORING: Use WebSocketService.sendCommand() API
        // Log all WebSocket commands being sent
        val roomId = data["room_id"] as? String
        if (command == "paginate") {
            android.util.Log.d("Andromuks", "🟠 sendWebSocketCommand: SENDING paginate - requestId=$requestId, roomId=$roomId, data=${org.json.JSONObject(data).toString().take(200)}")
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "sendWebSocketCommand: command='$command', requestId=$requestId, data=${org.json.JSONObject(data).toString().take(200)}")
        }
        
        val sendResult = if (WebSocketService.sendCommand(command, requestId, data)) {
            if (command == "paginate") {
                android.util.Log.d("Andromuks", "🟠 sendWebSocketCommand: paginate SENT successfully - requestId=$requestId, roomId=$roomId")
            }
            WebSocketResult.SUCCESS
        } else {
            android.util.Log.w("Andromuks", "🟠 sendWebSocketCommand: FAILED to send $command - requestId=$requestId, roomId=$roomId (service returned false)")
            WebSocketResult.CONNECTION_ERROR
        }
        
        // PHASE 5.2: Track all commands with positive request_id for acknowledgment
        // request_id = 0 means no response expected (like typing)
        // request_id > 0 means we expect a response with same request_id
        // CRITICAL FIX: Only track in memory for acknowledgment - don't save to storage when WebSocket is connected
        // Storage is only needed for persistence across app restarts or when disconnected
        if (requestId > 0 && sendResult == WebSocketResult.SUCCESS) {
            val messageId = java.util.UUID.randomUUID().toString()
            val acknowledgmentTimeout = System.currentTimeMillis() + 30000L // 30 seconds
            
            val operation = PendingWebSocketOperation(
                type = "command_$command", // Use command name as type for generic tracking
                data = mapOf(
                    "command" to command,
                    "requestId" to requestId,
                    "data" to data
                ),
                retryCount = 0,
                messageId = messageId, // Internal tracking only
                timestamp = System.currentTimeMillis(),
                acknowledged = false,
                acknowledgmentTimeout = acknowledgmentTimeout
            )
            
            // Add to in-memory queue for acknowledgment tracking (no storage save)
            // Only save to storage if WebSocket disconnects or command fails
            synchronized(pendingOperationsLock) {
                if (pendingWebSocketOperations.size >= MAX_QUEUE_SIZE) {
                    // Remove oldest operation (by timestamp)
                    val oldest = pendingWebSocketOperations.minByOrNull { it.timestamp }
                    if (oldest != null) {
                        pendingWebSocketOperations.remove(oldest)
                        if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Queue full (${MAX_QUEUE_SIZE}), removed oldest operation: ${oldest.type}")
                    }
                }
                pendingWebSocketOperations.add(operation)
            }
            // Don't save to storage - command was sent successfully, only need in-memory tracking
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracking command '$command' with request_id: $requestId for acknowledgment (in-memory only)")
        }
        
        return sendResult
    }
    
    /**
     * NETWORK OPTIMIZATION: Check if command can work offline (use cached data)
     */
    private fun isOfflineCapableCommand(command: String): Boolean {
        return when (command) {
            "get_profile", "get_room_state" -> true // These can use cached data
            else -> false
        }
    }
    
    /**
     * CRITICAL FIX: Flush pending commands queue after init_complete and all sync_complete messages are processed
     * This sends all commands that were blocked during initial connection
     */
    private fun flushPendingCommandsQueue() {
        val commandsToFlush = synchronized(pendingCommandsQueue) {
            val commands = pendingCommandsQueue.toList()
            pendingCommandsQueue.clear()
            commands
        }
        
        if (commandsToFlush.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Flushing ${commandsToFlush.size} queued commands after init_complete + sync_complete processing")
            }
            
            // Send all queued commands
            for ((command, requestId, data) in commandsToFlush) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Flushing queued command: $command (requestId: $requestId)")
                }
                // Recursively call sendWebSocketCommand - it will now succeed since canSendCommandsToBackend is true
                sendWebSocketCommand(command, requestId, data)
            }
        }
    }
    
    /**
     * CRITICAL FIX: Request get_room_state for ALL rooms after init_complete and sync_complete processing
     * This ensures bridge badges are loaded before navigating to RoomListScreen
     * These requests are exempt from the canSendCommandsToBackend blocking
     */
    private fun loadAllRoomStatesAfterInitComplete() {
        // Only run on initial connection (not reconnections)
        val isReconnecting = WebSocketService.isReconnectingWithLastReceivedEvent()
        if (isReconnecting) {
            // On reconnection, allow commands immediately (no need to load all room states)
            canSendCommandsToBackend = true
            flushPendingCommandsQueue()
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Reconnection detected - skipping all room state loading, allowing commands immediately")
            }
            return
        }
        
        viewModelScope.launch(Dispatchers.Default) {
            // Wait a moment for sync_complete processing to finish
            delay(500)
            
            // Get all rooms from roomMap
            val allRoomIds = synchronized(roomMap) {
                roomMap.keys.toList()
            }
            
            if (allRoomIds.isEmpty()) {
                // No rooms - allow commands immediately
                allRoomStatesLoaded = true
                canSendCommandsToBackend = true
                flushPendingCommandsQueue()
                checkStartupComplete()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: No rooms to load - allowing commands immediately")
                }
                return@launch
            }
            
            // CRITICAL OPTIMIZATION: Check SharedPreferences cache to skip rooms we already know about
            // Only request get_room_state for rooms not in cache (new rooms)
            val context = appContext ?: return@launch
            val uncachedRoomIds = allRoomIds.filter { roomId ->
                !net.vrkknn.andromuks.utils.BridgeInfoCache.isCached(context, roomId)
            }
            
            // Load cached bridge info into roomMap for rooms that are cached
            val cachedCount = allRoomIds.size - uncachedRoomIds.size
            if (cachedCount > 0) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Loading ${cachedCount} cached bridge info entries from SharedPreferences")
                }
                
                allRoomIds.forEach { roomId ->
                    if (net.vrkknn.andromuks.utils.BridgeInfoCache.isCached(context, roomId)) {
                        val cachedAvatarUrl = net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, roomId)
                        // cachedAvatarUrl will be empty string if not bridged, or mxc:// URL if bridged
                        val bridgeAvatarUrl = if (cachedAvatarUrl != null && cachedAvatarUrl.isNotEmpty()) {
                            cachedAvatarUrl
                        } else {
                            null
                        }
                        
                        // Update roomMap with cached bridge info
                        val existing = roomMap[roomId]
                        if (existing != null && existing.bridgeProtocolAvatarUrl != bridgeAvatarUrl) {
                            val updatedRoom = existing.copy(bridgeProtocolAvatarUrl = bridgeAvatarUrl)
                            roomMap[roomId] = updatedRoom
                            allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                            invalidateRoomSectionCache()
                            roomListUpdateCounter++
                        }
                    }
                }
            }
            
            if (uncachedRoomIds.isEmpty()) {
                // All rooms are cached - no need to request room states
                allRoomStatesLoaded = true
                canSendCommandsToBackend = true
                flushPendingCommandsQueue()
                checkStartupComplete()
                addStartupProgressMessage("Bridge info loaded for all rooms (from cache)")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: All ${allRoomIds.size} rooms are cached - skipping room state requests")
                }
                return@launch
            }
            
            // Mark that we're requesting room states (only for uncached rooms)
            allRoomStatesRequested = true
            totalRoomStateRequests = uncachedRoomIds.size
            completedRoomStateRequests = 0
            
            synchronized(pendingRoomStateResponses) {
                pendingRoomStateResponses.clear()
                pendingRoomStateResponses.addAll(uncachedRoomIds)
            }
            
            if (cachedCount > 0) {
                addStartupProgressMessage("Loading bridge info for ${uncachedRoomIds.size} new rooms (${cachedCount} cached)... 0 / ${uncachedRoomIds.size}")
            } else {
                addStartupProgressMessage("Loading bridge info for ${uncachedRoomIds.size} new rooms... 0 / ${uncachedRoomIds.size}")
            }
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for ${uncachedRoomIds.size} uncached rooms (${cachedCount} already cached)")
            }
            
            // Request room state only for uncached rooms with small delays to avoid overwhelming backend
            uncachedRoomIds.forEachIndexed { index, roomId ->
                // Skip if already requested
                if (!pendingRoomStateRequests.contains(roomId)) {
                    requestRoomState(roomId)
                } else {
                    // Already requested - remove from pending set and increment counter
                    synchronized(pendingRoomStateResponses) {
                        pendingRoomStateResponses.remove(roomId)
                    }
                    completedRoomStateRequests++
                }
                
                // Small delay every 10 requests to avoid overwhelming backend
                if ((index + 1) % 10 == 0) {
                    delay(100)
                } else {
                    delay(20) // Very small delay between requests
                }
            }
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Finished sending room state requests for ${uncachedRoomIds.size} uncached rooms. Waiting for responses...")
            }
        }
    }
    
    /**
     * NETWORK OPTIMIZATION: Queue command for retry when connection is restored
     */
    private fun queueCommandForOfflineRetry(command: String, requestId: Int, data: Map<String, Any>) {
        // PHASE 5.1: Add to pending operations for retry when connection is restored
        // Queue size limit is now enforced in addPendingOperation()
        // CRITICAL FIX: Save to storage when WebSocket is disconnected (need persistence)
        addPendingOperation(
            PendingWebSocketOperation(
                type = "offline_$command",
                data = mapOf(
                    "command" to command,
                    "requestId" to requestId,
                    "data" to data
                ),
                retryCount = 0
            ),
            saveToStorage = true // Save to storage when disconnected
        )
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Queued offline command: $command")
    }
    
    /**
     * Send get_event command to retrieve full event details from server
     * Useful when we only have partial event information (e.g., for reply previews)
     */
    fun getEvent(roomId: String, eventId: String, callback: (TimelineEvent?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getEvent called for roomId: '$roomId', eventId: '$eventId'")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with null, health monitor will handle reconnection")
            callback(null)
            return
        }
        
        val eventRequestId = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_event: $eventRequestId")
        
        // Store the callback to handle the response
        eventRequests[eventRequestId] = roomId to callback
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: get_event with data: $commandData")
        sendWebSocketCommand("get_event", eventRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $eventRequestId")
        
        // Add timeout mechanism to prevent infinite loading
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 10000L // 10 second timeout
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting get_event timeout to ${timeoutMs}ms for requestId=$eventRequestId")
            delay(timeoutMs)
            
            // Check if request is still pending
            if (eventRequests.containsKey(eventRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: get_event timeout after ${timeoutMs}ms for requestId=$eventRequestId, calling callback with null")
                // Switch to Main dispatcher for callback
                withContext(Dispatchers.Main) {
                    eventRequests.remove(eventRequestId)?.let { (_, callback) ->
                        callback(null)
                    }
                }
            }
        }
    }
    
    /**
     * Request mentions list from backend
     * @param maxTimestamp Timestamp to get mentions before this time (defaults to current time)
     * @param limit Maximum number of mentions to return (default 50)
     */
    fun requestMentionsList(maxTimestamp: Long? = null, limit: Int = 50) {
        val actualMaxTimestamp = maxTimestamp ?: System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: requestMentionsList called with maxTimestamp=$actualMaxTimestamp, limit=$limit")
        
        val ws = WebSocketService.getWebSocket() ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - cannot request mentions list")
            return
        }
        
        val mentionsRequestId = requestIdCounter++
        mentionsRequests[mentionsRequestId] = Unit
        
        isMentionsLoading = true
        
        val commandData = mapOf<String, Any>(
            "type" to 4, // Mention type (from backend)
            "limit" to limit,
            "max_timestamp" to actualMaxTimestamp
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending get_mentions command with request_id=$mentionsRequestId, data=$commandData")
        sendWebSocketCommand("get_mentions", mentionsRequestId, commandData)
        
        // Add timeout mechanism
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 15000L // 15 second timeout
            delay(timeoutMs)
            
            if (mentionsRequests.containsKey(mentionsRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: get_mentions timeout after ${timeoutMs}ms for requestId=$mentionsRequestId")
                withContext(Dispatchers.Main) {
                    mentionsRequests.remove(mentionsRequestId)
                    isMentionsLoading = false
                }
            }
        }
    }
    
    /**
     * Handle mentions list response from backend
     */
    private fun handleMentionsListResponse(requestId: Int, data: Any) {
        mentionsRequests.remove(requestId)
        isMentionsLoading = false
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling mentions list response for requestId: $requestId")
        
        when (data) {
            is org.json.JSONArray -> {
                // Parse array of event JSON objects
                val events = mutableListOf<TimelineEvent>()
                for (i in 0 until data.length()) {
                    val eventJson = data.optJSONObject(i) ?: continue
                    try {
                        val event = TimelineEvent.fromJson(eventJson)
                        events.add(event)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error parsing mention event at index $i: ${e.message}", e)
                    }
                }
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsed ${events.size} mention events from response")
                
                // Process events and fetch reply targets if needed
                processMentionEvents(events)
            }
            else -> {
                android.util.Log.w("Andromuks", "AppViewModel: Unexpected data type in mentions list response: ${data::class.java.simpleName}")
                mentionEvents = emptyList()
            }
        }
    }
    
    /**
     * Process mention events: convert to MentionEvent objects and fetch reply targets
     */
    private fun processMentionEvents(events: List<TimelineEvent>) {
        viewModelScope.launch(Dispatchers.IO) {
            val mentionEventList = mutableListOf<MentionEvent>()
            val replyTargetsToFetch = mutableListOf<Pair<String, String>>() // (roomId, eventId)
            
            // First pass: create MentionEvent objects and collect reply targets
            for (event in events) {
                val roomId = event.roomId
                val room = getRoomById(roomId)
                val roomName = room?.name
                val roomAvatarUrl = room?.avatarUrl
                
                // Check if this event is a reply
                val replyToEventId = event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                    ?: event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                
                if (replyToEventId != null) {
                    replyTargetsToFetch.add(Pair(roomId, replyToEventId))
                }
                
                mentionEventList.add(
                    MentionEvent(
                        mentionEntry = MentionEntry(roomId, event.eventId),
                        event = event,
                        roomName = roomName,
                        roomAvatarUrl = roomAvatarUrl
                    )
                )
            }
            
            // Fetch reply targets in parallel
            val replyEventsMap = mutableMapOf<String, TimelineEvent?>() // "roomId:eventId" -> event
            if (replyTargetsToFetch.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetching ${replyTargetsToFetch.size} reply target events")
                
                // Helper function to convert callback-based getEvent to suspend function
                suspend fun fetchEvent(roomId: String, eventId: String): TimelineEvent? {
                    val deferred = CompletableDeferred<TimelineEvent?>()
                    getEvent(roomId, eventId) { event ->
                        deferred.complete(event)
                    }
                    return deferred.await()
                }
                
                val deferredResults = replyTargetsToFetch.map { (roomId, eventId) ->
                    async {
                        val key = "$roomId:$eventId"
                        val replyEvent = withTimeoutOrNull(5000L) {
                            fetchEvent(roomId, eventId)
                        }
                        key to replyEvent
                    }
                }
                
                // Wait for all requests to complete
                deferredResults.awaitAll().forEach { (key, event) ->
                    replyEventsMap[key] = event
                }
            }
            
            // Update mention events with reply targets
            val finalMentionEvents = mentionEventList.map { mentionEvent ->
                val replyToEventId = mentionEvent.event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                    ?: mentionEvent.event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                
                if (replyToEventId != null) {
                    val key = "${mentionEvent.event.roomId}:$replyToEventId"
                    val replyEvent = replyEventsMap[key]
                    mentionEvent.copy(replyToEvent = replyEvent)
                } else {
                    mentionEvent
                }
            }
            
            // Update mentionEvents on main thread
            withContext(Dispatchers.Main) {
                mentionEvents = finalMentionEvents
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated mentionEvents with ${finalMentionEvents.size} events")
            }
        }
    }

    // Settings functions
    fun toggleCompression() {
        enableCompression = !enableCompression
        
        // Save setting to SharedPreferences
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val saved = prefs.edit()
                .putBoolean("enable_compression", enableCompression)
                .commit() // Use commit() instead of apply() to ensure it's saved synchronously
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Saved enableCompression setting: $enableCompression (commit result: $saved)")
                // Verify it was actually saved
                val verify = prefs.getBoolean("enable_compression", !enableCompression)
                android.util.Log.d("Andromuks", "AppViewModel: Verified enableCompression in SharedPreferences: $verify")
            }
        }
        
        // Restart WebSocket with new compression setting
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket due to compression setting change")
        logActivity("Compression Setting Changed - Restarting", null)
        restartWebSocket("Compression setting changed")
    }
    
    fun toggleEnterKeyBehavior() {
        enterKeySendsMessage = !enterKeySendsMessage
        
        // Save setting to SharedPreferences
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("enter_key_sends_message", enterKeySendsMessage)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved enterKeySendsMessage setting: $enterKeySendsMessage")
        }
    }
    
    fun toggleLoadThumbnailsIfAvailable() {
        loadThumbnailsIfAvailable = !loadThumbnailsIfAvailable
        
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("load_thumbnails_if_available", loadThumbnailsIfAvailable)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved loadThumbnailsIfAvailable setting: $loadThumbnailsIfAvailable"
            )
        }
    }
    
    fun toggleRenderThumbnailsAlways() {
        renderThumbnailsAlways = !renderThumbnailsAlways
        
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("render_thumbnails_always", renderThumbnailsAlways)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved renderThumbnailsAlways setting: $renderThumbnailsAlways"
            )
        }
    }

    fun updateElementCallBaseUrl(url: String) {
        elementCallBaseUrl = url.trim()
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("element_call_base_url", elementCallBaseUrl)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved elementCallBaseUrl setting: $elementCallBaseUrl"
            )
        }
    }
    
    /**
     * Load settings from SharedPreferences
     */
    fun loadSettings(context: Context? = null) {
        val contextToUse = context ?: appContext
        contextToUse?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            enableCompression = prefs.getBoolean("enable_compression", true) // Default to true
            enterKeySendsMessage = prefs.getBoolean("enter_key_sends_message", true) // Default to true (Enter sends, Shift+Enter newline)
            loadThumbnailsIfAvailable = prefs.getBoolean("load_thumbnails_if_available", true) // Default to true
            renderThumbnailsAlways = prefs.getBoolean("render_thumbnails_always", true) // Default to true
            elementCallBaseUrl = prefs.getString("element_call_base_url", "") ?: ""
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded enableCompression setting: $enableCompression")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded enterKeySendsMessage setting: $enterKeySendsMessage")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded loadThumbnailsIfAvailable setting: $loadThumbnailsIfAvailable")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded renderThumbnailsAlways setting: $renderThumbnailsAlways")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded elementCallBaseUrl setting: $elementCallBaseUrl")
        }
    }
    /**
     * Starts the WebSocket service to maintain connection in background
     * 
     * Note: The service primarily shows a foreground notification to prevent
     * Android from killing the app process. The actual WebSocket connection
     * is managed by NetworkUtils and AppViewModel.
     */
    fun startWebSocketService() {
        appContext?.let { context ->
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Starting WebSocket foreground service")
            logActivity("Starting WebSocket Service", null)
            val intent = android.content.Intent(context, WebSocketService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    WebSocketService.shouldUseForegroundService()
                ) {
                    // Normal path on Android O+: request a foreground service start
                    context.startForegroundService(intent)
                } else {
                    // Either pre-O, or we've previously been denied FGS for this process.
                    // In that case, fall back to a regular service start to avoid
                    // ForegroundServiceDidNotStartInTimeException.
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to start WebSocketService", e)
            }
            
            // Service will manage connection lifecycle once started
        }
    }
    
    /**
     * PHASE 1.4 FIX: Initialize WebSocket connection using viewModelScope
     * This ensures the connection attempt survives activity recreation
     * Called from AuthCheckScreen when primary instance is ready to connect
     */
    fun initializeWebSocketConnection(homeserverUrl: String, token: String) {
        // Only primary instance should connect
        if (instanceRole != InstanceRole.PRIMARY) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: initializeWebSocketConnection called on non-primary instance ($viewModelId), ignoring")
            return
        }
        
        // Check if already connected
        if (WebSocketService.isWebSocketConnected()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket already connected, skipping initialization")
            attachToExistingWebSocketIfAvailable()
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Delegating WebSocket connection to WebSocketService")
        
        // Start WebSocket service BEFORE connecting websocket
        startWebSocketService()
        
        // Set app as visible since we're starting the app
        WebSocketService.setAppVisibility(true)
        
        // REFACTORING: Delegate connection to service
        // The service now owns the WebSocket connection lifecycle
        // Pass this ViewModel for message routing (optional - service can work without it)
        WebSocketService.connectWebSocket(homeserverUrl, token, this@AppViewModel, reason = "Initial connection from AppViewModel")
    }

    // User Info Functions
    
    /**
     * Requests encryption info for a user
     */
    fun requestUserEncryptionInfo(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting encryption info for user: $userId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        userEncryptionInfoRequests[requestId] = callback
        
        sendWebSocketCommand("get_profile_encryption_info", requestId, mapOf(
            "user_id" to userId
        ))
    }
    
    /**
     * Requests mutual rooms with a user
     */
    fun requestMutualRooms(userId: String, callback: (List<String>?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting mutual rooms with user: $userId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        mutualRoomsRequests[requestId] = callback
        
        sendWebSocketCommand("get_mutual_rooms", requestId, mapOf(
            "user_id" to userId
        ))
    }
    
    /**
     * Starts tracking a user's devices
     */
    fun trackUserDevices(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracking devices for user: $userId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        trackDevicesRequests[requestId] = callback
        
        sendWebSocketCommand("track_user_devices", requestId, mapOf(
            "user_id" to userId
        ))
    }
    /**
     * Gets all messages in a thread (thread root + all replies)
     * @param roomId The room containing the thread
     * @param threadRootEventId The event ID that started the thread
     * @return List of timeline events in the thread, sorted by timestamp
     */
    fun getThreadMessages(roomId: String, threadRootEventId: String): List<TimelineEvent> {
        val threadMessages = mutableListOf<TimelineEvent>()
        
        // Add the thread root message first
        val rootMessage = timelineEvents.find { it.eventId == threadRootEventId }
        if (rootMessage != null) {
            threadMessages.add(rootMessage)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found thread root: $threadRootEventId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Thread root not found: $threadRootEventId")
        }
        
        // Add all messages that are part of this thread
        val threadReplies = timelineEvents.filter { event ->
            event.isThreadMessage() && event.relatesTo == threadRootEventId
        }
        
        threadMessages.addAll(threadReplies)
        
        // Sort by timestamp
        val sortedMessages = threadMessages.sortedBy { it.timestamp }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getThreadMessages - found ${sortedMessages.size} messages in thread (1 root + ${threadReplies.size} replies)")
        
        return sortedMessages
    }
    
    /**
     * Sends a reply in a thread
     * @param roomId The room ID
     * @param text The message text
     * @param threadRootEventId The thread root event ID (where the thread started)
     * @param fallbackReplyToEventId The specific message being replied to (for fallback compatibility)
     */
    fun sendThreadReply(
        roomId: String,
        text: String,
        threadRootEventId: String,
        fallbackReplyToEventId: String? = null
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendThreadReply called - roomId: $roomId, text: '$text', threadRoot: $threadRootEventId, fallbackReply: $fallbackReplyToEventId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - cannot send thread reply, health monitor will handle reconnection")
            return
        }
        
        val messageRequestId = requestIdCounter++
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Resolve reply target: if explicitly replying, use that; otherwise use latest event in thread
        val resolvedReplyTarget = fallbackReplyToEventId
            ?: getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId

        // Only mention when explicitly replying
        val mentionUserIds = if (fallbackReplyToEventId != null) {
            timelineEvents.firstOrNull { it.eventId == fallbackReplyToEventId }?.sender
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                ?: emptyList()
        } else {
            emptyList()
        }

        // Per Matrix thread spec: is_falling_back should be false when using an explicit m.in_reply_to inside the thread
        val isFallingBack = fallbackReplyToEventId == null

        // Build the thread reply structure
        val relatesTo = mutableMapOf<String, Any>(
            "rel_type" to "m.thread",
            "event_id" to threadRootEventId,
            "is_falling_back" to isFallingBack
        )
        
        // Add fallback reply-to for clients without thread support
        if (resolvedReplyTarget != null) {
            relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to relatesTo,
            "mentions" to mapOf(
                "user_ids" to mentionUserIds,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending thread reply with data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
    }
    
    /**
     * Requests complete user profile information (profile, encryption info, mutual rooms)
     */
    fun requestFullUserInfo(userId: String, forceRefresh: Boolean = false, callback: (net.vrkknn.andromuks.utils.UserProfileInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting full user info for: $userId (forceRefresh: $forceRefresh)")
        
        var displayName: String? = null
        var avatarUrl: String? = null
        var timezone: String? = null
        var pronouns: List<net.vrkknn.andromuks.utils.UserPronouns>? = null
        var encryptionInfo: net.vrkknn.andromuks.utils.UserEncryptionInfo? = null
        var mutualRooms: List<String> = emptyList()
        var arbitraryFields: Map<String, Any> = emptyMap()
        
        var profileCompleted = false
        var encryptionCompleted = false
        var mutualRoomsCompleted = false
        var hasError = false
        
        fun checkCompletion() {
            val isSelfUser = userId == currentUserId
            
            val completedCount = (if (profileCompleted) 1 else 0) + 
                                (if (encryptionCompleted) 1 else 0) + 
                                (if (mutualRoomsCompleted) 1 else 0)
            
            val expectedRequests = if (isSelfUser) 2 else 3
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info progress - profile: $profileCompleted, encryption: $encryptionCompleted, mutualRooms: $mutualRoomsCompleted (expected: $expectedRequests, completed: $completedCount)")
            
            if (completedCount >= expectedRequests && !hasError) {
                val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    timezone = timezone,
                    pronouns = pronouns,
                    encryptionInfo = encryptionInfo,
                    mutualRooms = mutualRooms,
                    roomDisplayName = null, // Per-room profile will be loaded separately if roomId is provided
                    roomAvatarUrl = null,
                    arbitraryFields = arbitraryFields
                )
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info completed for $userId")
                callback(profileInfo, null)
            } else if (!hasError) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info still waiting for requests to complete (${completedCount}/$expectedRequests)")
            }
        }
        
        // Request 1: Profile (always request fresh from backend - get_profile always fetches latest data)
        val profileRequestId = requestIdCounter++
        profileRequests[profileRequestId] = userId
        sendWebSocketCommand("get_profile", profileRequestId, mapOf(
            "user_id" to userId
        ))
        
        // Override the profile handler temporarily to capture the result
        val originalProfileCallback = profileRequests[profileRequestId]
        profileRequests[profileRequestId] = userId // Keep userId for routing
        
        // We need to intercept the profile response, so we'll handle it in handleProfileResponse
        // For now, let's use a different approach - store a callback for full user info requests
        
        // Request 2: Encryption Info
        requestUserEncryptionInfo(userId) { encInfo, error ->
            if (error != null) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to get encryption info: $error")
                // Don't treat as critical error - encryption info might not be available
            }
            encryptionInfo = encInfo
            encryptionCompleted = true
            checkCompletion()
        }
        
        // Request 3: Mutual Rooms
        // Skip mutual rooms request if viewing our own profile (backend returns HTTP 422)
        if (userId == currentUserId) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mutual rooms request for self")
            mutualRooms = emptyList()
            mutualRoomsCompleted = true
            checkCompletion()
        } else {
            requestMutualRooms(userId) { rooms, error ->
                if (error != null) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to get mutual rooms: $error")
                    hasError = true
                    callback(null, error)
                    return@requestMutualRooms
                }
                mutualRooms = rooms ?: emptyList()
                mutualRoomsCompleted = true
                checkCompletion()
            }
        }
        
        // Handle profile response separately
        val tempProfileCallback: (JSONObject?) -> Unit = { profileData ->
            if (profileData != null) {
                displayName = profileData.optString("displayname")?.takeIf { it.isNotBlank() }
                avatarUrl = profileData.optString("avatar_url")?.takeIf { it.isNotBlank() }
                // Support both timezone field formats: prefer m.tz (standardized) over us.cloke.msc4175.tz (legacy)
                timezone = profileData.optString("m.tz")?.takeIf { it.isNotBlank() }
                    ?: profileData.optString("us.cloke.msc4175.tz")?.takeIf { it.isNotBlank() }
                
                // Extract pronouns from io.fsky.nyx.pronouns array
                val pronounsArray = profileData.optJSONArray("io.fsky.nyx.pronouns")
                if (pronounsArray != null && pronounsArray.length() > 0) {
                    val pronounsList = mutableListOf<net.vrkknn.andromuks.utils.UserPronouns>()
                    for (i in 0 until pronounsArray.length()) {
                        val pronounObj = pronounsArray.optJSONObject(i)
                        if (pronounObj != null) {
                            val language = pronounObj.optString("language", "en")
                            val summary = pronounObj.optString("summary", "")
                            if (summary.isNotBlank()) {
                                pronounsList.add(net.vrkknn.andromuks.utils.UserPronouns(
                                    language = language,
                                    summary = summary
                                ))
                            }
                        }
                    }
                    if (pronounsList.isNotEmpty()) {
                        pronouns = pronounsList
                    }
                }
                
                // Extract all arbitrary fields (everything except known fields)
                val knownKeys = setOf("displayname", "avatar_url", "us.cloke.msc4175.tz", "m.tz", "io.fsky.nyx.pronouns")
                val arbitraryFieldsMap = mutableMapOf<String, Any>()
                val keys = profileData.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!knownKeys.contains(key)) {
                        val value = profileData.get(key)
                        // Convert JSON types to Kotlin types
                        when (value) {
                            is org.json.JSONArray -> arbitraryFieldsMap[key] = value
                            is org.json.JSONObject -> arbitraryFieldsMap[key] = value
                            is String -> arbitraryFieldsMap[key] = value
                            is Number -> arbitraryFieldsMap[key] = value
                            is Boolean -> arbitraryFieldsMap[key] = value
                            else -> arbitraryFieldsMap[key] = value.toString()
                        }
                    }
                }
                
                // Store arbitrary fields in outer variable
                arbitraryFields = arbitraryFieldsMap
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile data received for $userId - display: $displayName, avatar: ${avatarUrl != null}, timezone: $timezone, pronouns: ${pronouns?.size ?: 0}, arbitraryFields: ${arbitraryFieldsMap.size}")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Profile data is null for $userId")
            }
            profileCompleted = true
            checkCompletion()
        }
        
        // Store this callback for later
        fullUserInfoCallbacks[profileRequestId] = tempProfileCallback
        
        // Add timeout mechanism to prevent hanging
        viewModelScope.launch {
            kotlinx.coroutines.delay(10000) // 10 second timeout
            if (!profileCompleted || !encryptionCompleted || (!mutualRoomsCompleted && userId != currentUserId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Full user info request timed out for $userId")
                // Clean up callbacks
                fullUserInfoCallbacks.remove(profileRequestId)
                if (!hasError) {
                    // Create a partial result with what we have
                    val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                        timezone = timezone,
                        pronouns = pronouns,
                        encryptionInfo = encryptionInfo,
                        mutualRooms = mutualRooms,
                        roomDisplayName = null, // Per-room profile will be loaded separately if roomId is provided
                        roomAvatarUrl = null,
                        arbitraryFields = arbitraryFields
                    )
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Returning partial user info after timeout for $userId")
                    callback(profileInfo, null)
                }
            }
        }
    }
    
    // Temporary storage for full user info profile callbacks
    private val fullUserInfoCallbacks = mutableMapOf<Int, (JSONObject?) -> Unit>()
    
    private fun handleUserEncryptionInfoResponse(requestId: Int, data: Any) {
        val callback = userEncryptionInfoRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling encryption info response for requestId: $requestId")
        
        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing encryption info", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleMutualRoomsResponse(requestId: Int, data: Any) {
        val callback = mutualRoomsRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling mutual rooms response for requestId: $requestId")
        
        try {
            val roomsList = when (data) {
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        list.add(data.getString(i))
                    }
                    list
                }
                is List<*> -> data.mapNotNull { it as? String }
                else -> {
                    android.util.Log.e("Andromuks", "AppViewModel: Unexpected data type for mutual rooms")
                    emptyList()
                }
            }
            callback(roomsList, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing mutual rooms", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleTrackDevicesResponse(requestId: Int, data: Any) {
        val callback = trackDevicesRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling track devices response for requestId: $requestId")
        
        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing track devices response", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun parseUserEncryptionInfo(data: Any): net.vrkknn.andromuks.utils.UserEncryptionInfo {
        val jsonData = when (data) {
            is JSONObject -> data
            else -> JSONObject(data.toString())
        }
        
        val devicesTracked = jsonData.optBoolean("devices_tracked", false)
        val devicesArray = jsonData.optJSONArray("devices")
        val devices = if (devicesArray != null) {
            val list = mutableListOf<net.vrkknn.andromuks.utils.DeviceInfo>()
            for (i in 0 until devicesArray.length()) {
                val deviceJson = devicesArray.getJSONObject(i)
                list.add(
                    net.vrkknn.andromuks.utils.DeviceInfo(
                        deviceId = deviceJson.getString("device_id"),
                        name = deviceJson.getString("name"),
                        identityKey = deviceJson.getString("identity_key"),
                        signingKey = deviceJson.getString("signing_key"),
                        fingerprint = deviceJson.getString("fingerprint"),
                        trustState = deviceJson.getString("trust_state")
                    )
                )
            }
            list
        } else {
            null
        }
        
        return net.vrkknn.andromuks.utils.UserEncryptionInfo(
            devicesTracked = devicesTracked,
            devices = devices,
            masterKey = jsonData.optString("master_key")?.takeIf { it.isNotBlank() },
            firstMasterKey = jsonData.optString("first_master_key")?.takeIf { it.isNotBlank() },
            userTrusted = jsonData.optBoolean("user_trusted", false),
            errors = jsonData.opt("errors")
        )
    }
    
    /**
     * Navigate to a room after joining
     * If room already exists, navigate immediately. Otherwise wait for sync.
     */
    fun joinRoomAndNavigate(roomId: String, navController: androidx.navigation.NavController) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: joinRoomAndNavigate called for $roomId")
        
        // Navigate directly - RoomTimelineScreen's LaunchedEffect will handle timeline loading
        val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
        navController.navigate("room_timeline/$encodedRoomId")
        // Timeline loading will be handled by RoomTimelineScreen's LaunchedEffect(roomId)
        // which calls requestRoomTimeline, which will use reset=true for newly joined rooms
    }
    
    private fun getRoomDisplayName(roomId: String): String {
        val roomItem = getRoomById(roomId)
        return roomItem?.name ?: roomId
    }
    
    /**
     * Resolve room alias to room ID with callback
     */
    fun resolveRoomAlias(alias: String, callback: (Pair<String, List<String>>?) -> Unit) {
        val requestId = requestIdCounter++
        resolveAliasRequests[requestId] = callback
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct send()
        sendWebSocketCommand("resolve_alias", requestId, mapOf("alias" to alias))
    }
    
    /**
     * Get room summary with callback
     */
    fun getRoomSummary(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit) {
        val requestId = requestIdCounter++
        getRoomSummaryRequests[requestId] = callback
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct send()
        sendWebSocketCommand("get_room_summary", requestId, mapOf(
            "room_id_or_alias" to roomIdOrAlias,
            "via" to viaServers
        ))
    }
    
    /**
     * Join room with callback
     */
    fun joinRoomWithCallback(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<String?, String?>?) -> Unit) {
        val requestId = requestIdCounter++
        joinRoomCallbacks[requestId] = callback
        
        // Store the roomIdOrAlias so we can mark it as newly joined when we get the response
        // We'll mark it in handleJoinRoomCallbackResponse when we get the actual room ID
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct send()
        val dataMap = mutableMapOf<String, Any>("room_id_or_alias" to roomIdOrAlias)
        if (viaServers.isNotEmpty()) {
            dataMap["via"] = viaServers
        }
        sendWebSocketCommand("join_room", requestId, dataMap)
    }
    
    private fun handleResolveAliasResponse(requestId: Int, data: Any) {
        val callback = resolveAliasRequests.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val roomId = data.optString("room_id")
            val serversArray = data.optJSONArray("servers")
            val servers = mutableListOf<String>()
            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    servers.add(serversArray.getString(i))
                }
            }
            if (roomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Resolved alias to roomId=$roomId, servers=$servers")
                callback(Pair(roomId, servers))
            } else {
                callback(null)
            }
        } else {
            callback(null)
        }
    }
    
    private fun handleGetRoomSummaryResponse(requestId: Int, data: Any) {
        val callback = getRoomSummaryRequests.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val summary = net.vrkknn.andromuks.utils.RoomSummary(
                roomId = data.optString("room_id", ""),
                avatarUrl = data.optString("avatar_url").takeIf { it.isNotEmpty() },
                canonicalAlias = data.optString("canonical_alias").takeIf { it.isNotEmpty() },
                guestCanJoin = data.optBoolean("guest_can_join", false),
                joinRule = data.optString("join_rule", ""),
                name = data.optString("name").takeIf { it.isNotEmpty() },
                numJoinedMembers = data.optInt("num_joined_members", 0),
                roomType = data.optString("room_type").takeIf { it.isNotEmpty() },
                worldReadable = data.optBoolean("world_readable", false),
                membership = data.optString("membership").takeIf { it.isNotEmpty() },
                roomVersion = data.optString("im.nheko.summary.version").takeIf { it.isNotEmpty() }
            )
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Got room summary for ${summary.roomId}")
            callback(Pair(summary, null))
        } else {
            callback(Pair(null, null))
        }
    }
    
    private fun handleJoinRoomCallbackResponse(requestId: Int, data: Any) {
        val callback = joinRoomCallbacks.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val roomId = data.optString("room_id")
            if (roomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Joined room successfully, roomId=$roomId")
                
                // CRITICAL: Preemptively mark room as newly joined so it appears at top when sync arrives
                newlyJoinedRoomIds.add(roomId)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preemptively marked room $roomId as newly joined from joinRoomWithCallback")
                
                callback(Pair(roomId, null))
            } else {
                callback(Pair(null, null))
            }
        } else {
            callback(Pair(null, null))
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Initialize periodic cleanup to prevent memory leaks
     */
    init {
        // Log app start (will be persisted when appContext is available)
        // Note: Activity log will be loaded when loadStateFromStorage is called from AuthCheck
        logActivity("App Started")
        
        // Populate all singleton caches on initialization
        // This ensures data is available even when AppViewModel is recreated
        populateReadReceiptsFromCache()
        populateMessageReactionsFromCache()
        populateRecentEmojisFromCache()
        populatePendingInvitesFromCache()
        populateRoomMemberCacheFromCache()
        populateEmojiPacksFromCache()
        populateStickerPacksFromCache()
        
        // Start periodic cleanup job
        viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // Run every 5 minutes
                performPeriodicMemoryCleanup()
            }
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Periodic cleanup of stale data to prevent memory pressure
     */
    private fun performPeriodicMemoryCleanup() {
        try {
            // Clean up stale member cache entries
            performMemberCacheCleanup()
            
            // Clean up stale message versions (keep only recent ones)
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (7 * 24 * 60 * 60 * 1000) // 7 days ago
            
            val versionsToRemove = messageVersions.filter { (_, versioned) ->
                versioned.versions.isNotEmpty() && 
                versioned.versions.first().timestamp < cutoffTime
            }.keys
            
            if (versionsToRemove.isNotEmpty()) {
                // Note: MessageVersionsCache doesn't have per-event removal, so we clear all
                // In practice, this cleanup happens rarely and clearing all is acceptable
                MessageVersionsCache.clear()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${versionsToRemove.size} old message versions")
            }
            
            // Clean up old processed reactions
            if (processedReactions.size > 200) {
                val toRemove = processedReactions.take(processedReactions.size - 100)
                processedReactions.removeAll(toRemove)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${toRemove.size} old processed reactions")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error during periodic memory cleanup", e)
        }
    }
    
    // =============================================================================
    // HELPER FUNCTIONS FOR TIMELINE RESPONSE REFACTORING
    // =============================================================================
    
    /**
     * Data class to hold parsed timeline response data
     */
    private data class TimelineResponseData(
        val events: JSONArray?,
        val hasMore: Boolean = true,
        val receipts: JSONObject? = null,
        val fromServer: Boolean = false
    ) {
        companion object {
            fun empty() = TimelineResponseData(events = JSONArray())
        }
    }
    /**
     * Extract profile information from member event
     */
    private fun extractProfileFromMemberEvent(event: TimelineEvent): MemberProfile {
        val displayName = event.content?.optString("displayname")?.takeIf { it.isNotBlank() }
        val avatarUrl = event.content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
        return MemberProfile(displayName, avatarUrl)
    }
    
    /**
     * Check if profile has changed
     */
    private fun isProfileChange(
        previousProfile: MemberProfile?,
        newProfile: MemberProfile,
        event: TimelineEvent
    ): Boolean {
        val membership = event.content?.optString("membership")
        val prevContent = event.unsigned?.optJSONObject("prev_content")
        val prevMembership = prevContent?.optString("membership")
        
        return prevMembership == "join" && membership == "join" &&
            (previousProfile?.displayName != newProfile.displayName ||
             previousProfile?.avatarUrl != newProfile.avatarUrl)
    }
    
    /**
     * Extract reaction event from timeline event
     */
    private fun extractReactionEvent(event: TimelineEvent): ReactionEvent? {
        val content = event.content ?: return null
        val relatesTo = content.optJSONObject("m.relates_to") ?: return null
        
        val relatesToEventId = relatesTo.optString("event_id")
        val emoji = relatesTo.optString("key")
        val relType = relatesTo.optString("rel_type")
        
        return if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
            ReactionEvent(
                roomId = event.roomId,
                eventId = event.eventId,
                sender = event.sender,
                emoji = emoji,
                relatesToEventId = relatesToEventId,
                timestamp = normalizeTimestamp(
                    event.timestamp,
                    event.unsigned?.optLong("age_ts") ?: 0L
                )
            )
        } else {
            null
        }
    }
    

    
    /**
     * Process member event - update cache
     */
    private fun processMemberEvent(
        event: TimelineEvent,
        memberMap: MutableMap<String, MemberProfile>
    ): Boolean {
        if (event.type != "m.room.member" || event.timelineRowid != -1L) return false
        
        val userId = event.stateKey ?: event.sender
        val profile = extractProfileFromMemberEvent(event)
        val previousProfile = memberMap[userId]
        
        memberMap[userId] = profile
        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
        
        return if (isProfileChange(previousProfile, profile, event)) {
            memberUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile change detected in timeline for $userId")
            true
        } else {
            false
        }
    }
    
    /**
     * Process reaction event from timeline
     */
    private fun processReactionFromTimeline(event: TimelineEvent): Boolean {
        if (event.type != "m.reaction") return false
        
        val reaction = extractReactionEvent(event) ?: return false
        
        return if (event.redactedBy == null) {
            processReactionEvent(reaction, isHistorical = true)
            true
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping redacted historical reaction: ${reaction.emoji} from ${reaction.sender} to ${reaction.relatesToEventId}")
            false
        }
    }
    
    /**
     * Build edit chains from events
     * @param clearExisting If true, clears existing eventChainMap before adding new events.
     *                      If false, merges new events with existing ones (for pagination).
     */
    private fun buildEditChainsFromEvents(timelineList: List<TimelineEvent>, clearExisting: Boolean = true) {
        if (clearExisting) {
            eventChainMap.clear()
            editEventsMap.clear()
        }
        
        for (event in timelineList) {
            val isEditEvt = isEditEvent(event)
            
            if (isEditEvt) {
                // Always update edit events map (edits can replace older edits)
                editEventsMap[event.eventId] = event
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Added edit event ${event.eventId} to edit events map")
            } else {
                // Merge: only add if not present, or if this is a newer version
                val existingEntry = eventChainMap[event.eventId]
                if (existingEntry == null || event.timestamp > existingEntry.originalTimestamp) {
                    eventChainMap[event.eventId] = EventChainEntry(
                        eventId = event.eventId,
                        ourBubble = event,
                        replacedBy = existingEntry?.replacedBy, // Preserve edit chain if merging
                        originalTimestamp = event.timestamp
                    )
                    if (BuildConfig.DEBUG && existingEntry != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Updated existing event ${event.eventId} in chain mapping (newer timestamp)")
                    } else if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Added regular event ${event.eventId} to chain mapping")
                    }
                }
            }
        }
    }
    
    /**
     * Handle background prefetch request
     */
    private fun handleBackgroundPrefetch(
        roomId: String,
        timelineList: List<TimelineEvent>
    ): Int {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing background prefetch request, silently adding ${timelineList.size} events to cache (roomId: $roomId)")
        RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)
        
        // No local persistence - using cache only
        signalRoomSnapshotReady(roomId)
        
        val newCacheCount = RoomTimelineCache.getCachedEventCount(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✅ Background prefetch completed - cache now has $newCacheCount events for room $roomId")
        
        // CRITICAL FIX: Only update smallestRowId if this room is currently open
        // smallestRowId is a global variable that affects the currently open room's pagination
        // Updating it for a background prefetch of a different room would break pagination for the open room
        if (currentRoomId == roomId) {
            smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated smallestRowId for background prefetch (room is currently open)")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipped updating smallestRowId for background prefetch (room $roomId is not currently open, currentRoomId=$currentRoomId)")
        }
        return 0 // No reactions processed
    }
    
    /**
     * Handle pagination merge
     */
    private fun handlePaginationMerge(
        roomId: String,
        timelineList: List<TimelineEvent>,
        requestId: Int
    ) {
        if (BuildConfig.DEBUG) {
            // Extra safety: detect any events whose roomId doesn't match the target room.
            val mismatched = timelineList.filter { it.roomId != roomId }
            if (mismatched.isNotEmpty()) {
                val distinctRooms = mismatched.asSequence().map { it.roomId }.distinct().take(5).toList()
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: ⚠️ Mismatched room_id in pagination merge for room $roomId, events from rooms: $distinctRooms (count=${mismatched.size})"
                )
                appContext?.let { context ->
                    android.widget.Toast.makeText(
                        context,
                        "Debug: Dropped ${mismatched.size} events with wrong room_id for room $roomId",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PAGINATION RESPONSE RECEIVED (requestId: $requestId)")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Received ${timelineList.size} events from backend")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Timeline events BEFORE merge: ${timelineEvents.size}")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cache BEFORE merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events")
        
        val cacheBefore = RoomTimelineCache.getCachedEventCount(roomId)
        val oldestRowIdBefore = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        
        val eventsAdded = RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)
        
        // CRITICAL FIX: After adding events to cache, reload ALL events from cache into eventChainMap
        // This ensures the timeline reflects all cached events, not just the newly paginated ones
        // Without this, eventChainMap might be missing events that were in the cache but not in eventChainMap
        val allCachedEvents = RoomTimelineCache.getCachedEvents(roomId)
        if (allCachedEvents != null && allCachedEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Reloading ${allCachedEvents.size} events from cache into eventChainMap after pagination")
            
            // CRITICAL FIX: Only update global state (eventChainMap, editEventsMap, timelineEvents) if this is the current room
            if (roomId == currentRoomId) {
                // Clear and rebuild eventChainMap from all cached events
                // This ensures we have all events, not just the ones that were in eventChainMap before
                eventChainMap.clear()
                editEventsMap.clear()
                
                for (event in allCachedEvents) {
                    val isEdit = isEditEvent(event)
                    if (isEdit) {
                        editEventsMap[event.eventId] = event
                    } else {
                        eventChainMap[event.eventId] = EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp
                        )
                    }
                }
                
                // Process versioned messages and edit relationships
                processVersionedMessages(allCachedEvents)
                processEditRelationships()
                
                // Rebuild timeline from all cached events
                buildTimelineFromChain()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping eventChainMap rebuild - roomId ($roomId) != currentRoomId ($currentRoomId). Cache merged only.")
            }
        } else {
            // Fallback: if we can't get all cached events, just merge the new ones
            // Only if it's the current room. Otherwise skip.
            if (roomId == currentRoomId) {
                mergePaginationEvents(timelineList)
            }
        }
        
        val oldestRowIdAfter = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        
        // CRITICAL FIX: Detect when pagination makes no progress by comparing request's max_timeline_id to response
        val maxTimelineIdUsed = paginateRequestMaxTimelineIds[requestId]
        paginateRequestMaxTimelineIds.remove(requestId) // Clean up tracking
        
        // Log paginate response with earliest and oldest timeline_rowid
        if (timelineList.isNotEmpty()) {
            val earliestTimelineRowId = timelineList.minOfOrNull { it.timelineRowid } ?: -1L
            val oldestTimelineRowId = timelineList.maxOfOrNull { it.timelineRowid } ?: -1L
            android.util.Log.d("Andromuks", "paginate response: Room - $roomId, Earliest timeline_rowid - $earliestTimelineRowId, Oldest timeline_rowid - $oldestTimelineRowId, Events - ${timelineList.size}")
        } else {
            android.util.Log.d("Andromuks", "paginate response: Room - $roomId, No events returned")
        }
        
        // Track the oldest timelineRowId from this pagination response
        // The oldest event will have the lowest (smallest) timelineRowId in the response
        // Note: timelineRowId can be positive or negative - both are valid according to Webmucks backend
        if (timelineList.isNotEmpty()) {
            val oldestInResponse = timelineList.minOfOrNull { it.timelineRowid }
            if (oldestInResponse != null) {
                // timelineRowId of 0 is a bug (should never happen)
                if (oldestInResponse == 0L) {
                    android.util.Log.e("Andromuks", "AppViewModel: ⚠️ BUG: Pagination response contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!")
                } else {
                    // CRITICAL FIX: Detect when we're making no progress (response's oldest event >= max_timeline_id we used)
                    // This means we got the same or newer events, not older ones - we're stuck!
                    // Note: For negative values, ">=" comparison still works correctly (e.g., -256 >= -158 is false, meaning progress)
                    if (maxTimelineIdUsed != null && oldestInResponse >= maxTimelineIdUsed) {
                        android.util.Log.w("Andromuks", "AppViewModel: ⚠️ PAGINATION STUCK: Response's oldest event ($oldestInResponse) >= max_timeline_id used ($maxTimelineIdUsed). No progress made!")
                        // If we got duplicates and made no progress, stop pagination to prevent infinite loop
                        if (eventsAdded == 0) {
                            android.util.Log.w("Andromuks", "AppViewModel: Setting hasMoreMessages=false to prevent infinite pagination loop (no progress + duplicates)")
                            hasMoreMessages = false
                            appContext?.let { context ->
                                android.widget.Toast.makeText(context, "No more messages to load", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Store it for next pull-to-refresh (can be positive or negative)
                        oldestRowIdPerRoom[roomId] = oldestInResponse
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracked oldest timelineRowId=$oldestInResponse for room $roomId (from ${timelineList.size} events, max_timeline_id used was $maxTimelineIdUsed)")
                    }
                }
            }
        }
        
        // FIX: If all events were duplicates (eventsAdded == 0), we need to handle this case
        // If we got events but none were added, and the oldestRowId didn't change, it means
        // we're stuck requesting the same range. We should set hasMoreMessages = false to prevent
        // infinite pagination loops, unless the backend explicitly says has_more = false (which
        // is already handled). However, if backend says has_more = true but we got no new events,
        // we should still respect that and let the next pagination try with a different max_timeline_id.
        if (eventsAdded == 0 && timelineList.isNotEmpty()) {
            android.util.Log.w("Andromuks", "AppViewModel: ⚠️ Pagination returned ${timelineList.size} events but all were duplicates (oldestRowId: $oldestRowIdBefore -> $oldestRowIdAfter)")
            // If oldestRowId didn't change, we made no progress - this could indicate we've reached the end
            // or that we need to adjust our pagination strategy. For now, we'll let the backend's has_more
            // flag control this, but log a warning.
            if (oldestRowIdBefore == oldestRowIdAfter && oldestRowIdBefore != -1L) {
                android.util.Log.w("Andromuks", "AppViewModel: ⚠️ No progress made in pagination (oldestRowId unchanged). If this persists, consider setting hasMoreMessages = false.")
            }
        }
        
        // No local persistence - using cache only
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Timeline events AFTER merge: ${timelineEvents.size}")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cache AFTER merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events")
        
        val newSmallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: smallestRowId BEFORE: $smallestRowId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: smallestRowId AFTER: $newSmallestRowId")
        smallestRowId = newSmallestRowId
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        
    }
    
    /**
     * Handle initial timeline build
     */
    private fun handleInitialTimelineBuild(
        roomId: String,
        timelineList: List<TimelineEvent>
    ) {
        if (BuildConfig.DEBUG) {
            val mismatched = timelineList.filter { it.roomId != roomId }
            if (mismatched.isNotEmpty()) {
                val distinctRooms = mismatched.asSequence().map { it.roomId }.distinct().take(5).toList()
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: ⚠️ Mismatched room_id in initial timeline build for room $roomId, events from rooms: $distinctRooms (count=${mismatched.size})"
                )
                appContext?.let { context ->
                    android.widget.Toast.makeText(
                        context,
                        "Debug: Dropped ${mismatched.size} events with wrong room_id for room $roomId",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        // Track the oldest timelineRowId from initial pagination response
        // This is the lowest (smallest) timelineRowId, which represents the oldest event
        // Note: timelineRowId can be positive or negative - both are valid according to Webmucks backend
        if (timelineList.isNotEmpty()) {
            val oldestInResponse = timelineList.minOfOrNull { it.timelineRowid }
            if (oldestInResponse != null) {
                // timelineRowId of 0 is a bug (should never happen)
                if (oldestInResponse == 0L) {
                    android.util.Log.e("Andromuks", "AppViewModel: ⚠️ BUG: Initial paginate contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!")
                } else {
                    // Store it for pull-to-refresh (can be positive or negative)
                    oldestRowIdPerRoom[roomId] = oldestInResponse
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracked oldest timelineRowId=$oldestInResponse for room $roomId from initial paginate in handleInitialTimelineBuild (${timelineList.size} events)")
                }
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Seeding cache with ${timelineList.size} paginated events for room $roomId")
        RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineList)
        
        // CRITICAL FIX: Restore reactions from cache and apply aggregated reactions from events
        // This ensures reactions are visible when paginate response rebuilds the timeline
        // Force reload to ensure reactions are loaded even if they were loaded earlier from cache
        // Note: messageReactions is a global map keyed by eventId, so it's safe to update even for background rooms
        loadReactionsForRoom(roomId, timelineList, forceReload = true)
        applyAggregatedReactionsFromEvents(timelineList, "handleInitialTimelineBuild")
        
        // CRITICAL FIX: Only update timeline state if this is the currently open room
        // This prevents race conditions where a background pagination for a previous room
        // clobbers the timeline of the current room or corrupts loading state.
        if (roomId == currentRoomId) {
            buildTimelineFromChain()
            val timelineSizeBefore = timelineEvents.size
            isTimelineLoading = false
            val timelineSizeAfter = timelineEvents.size
            android.util.Log.d("Andromuks", "🟡 handleInitialTimelineBuild: Timeline built - roomId=$roomId, timelineList.size=${timelineList.size}, timelineEvents.size=$timelineSizeAfter (was $timelineSizeBefore), isTimelineLoading=$isTimelineLoading")
        } else {
             android.util.Log.d("Andromuks", "🟡 handleInitialTimelineBuild: Skipping timeline build - roomId ($roomId) != currentRoomId ($currentRoomId). Cache seeded only.")
        }
        
        // Persist initial paginated events to cache
        // No local persistence - using cache only
        
        smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
    }
    /**
     * Get cache statistics for display in settings
     * Returns a map with cache size information for various caches
     */
    fun getCacheStatistics(context: android.content.Context): Map<String, String> {
        val stats = mutableMapOf<String, String>()
        
        // 1. Current app RAM usage (with detailed diagnostics)
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val memoryUsagePercent = (usedMemory.toDouble() / maxMemory.toDouble() * 100).toInt()
        
        stats["app_ram_usage"] = formatBytes(usedMemory)
        stats["app_ram_max"] = formatBytes(maxMemory)
        stats["app_ram_free"] = formatBytes(freeMemory)
        stats["app_ram_total"] = formatBytes(totalMemory)
        stats["app_ram_usage_percent"] = "$memoryUsagePercent%"
        
        // Log memory diagnostics for debugging
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "Memory Stats: Used=${formatBytes(usedMemory)}, Free=${formatBytes(freeMemory)}, Max=${formatBytes(maxMemory)}, Usage=$memoryUsagePercent%")
        
        // 2. Room timeline memory cache size
        val timelineCacheStats = RoomTimelineCache.getCacheStats()
        val totalTimelineEvents = timelineCacheStats["total_events_cached"] as? Int ?: 0
        // Estimate memory: each TimelineEvent with all fields is roughly 1-2KB
        val estimatedTimelineMemory = totalTimelineEvents * 1.5 * 1024 // 1.5KB per event estimate
        stats["timeline_memory_cache"] = formatBytes(estimatedTimelineMemory.toLong())
        stats["timeline_event_count"] = "$totalTimelineEvents events"
        
        // 3. User profiles memory cache size
        val flattenedCount = ProfileCache.getFlattenedCacheSize()
        val roomMemberCount = RoomMemberCache.getAllMembers().values.sumOf { it.size }
        val globalCount = ProfileCache.getGlobalCacheSize()
        // Estimate: MemberProfile with strings is roughly 200-500 bytes
        val estimatedProfileMemory = (flattenedCount + roomMemberCount + globalCount) * 350L // 350 bytes per profile estimate
        stats["user_profiles_memory_cache"] = formatBytes(estimatedProfileMemory)
        stats["user_profiles_count"] = "${flattenedCount + roomMemberCount + globalCount} profiles"
        
        // 4. User profile disk cache size (no disk cache)
        val profileDiskSize = 0L
        stats["user_profiles_disk_cache"] = formatBytes(profileDiskSize)
        
        // 5. Media memory cache size (from Coil)
        // NOTE: Coil's MemoryCache doesn't expose actual current usage, only max size configuration
        // We show the maximum configured size (25% of max memory)
        val mediaMemoryCacheSize = try {
            val imageLoader = net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context)
            val memoryCache = imageLoader.memoryCache
            // MemoryCache uses 25% of available memory (from ImageLoaderSingleton.MEMORY_CACHE_PERCENT)
            val runtime = Runtime.getRuntime()
            (runtime.maxMemory() * 0.25).toLong()
        } catch (e: Exception) {
            0L
        }
        stats["media_memory_cache"] = formatBytes(mediaMemoryCacheSize)
        stats["media_memory_cache_max"] = "Max: ${formatBytes(mediaMemoryCacheSize)}" // Store max for display
        
        // 6. Media disk cache size (from Coil)
        val mediaDiskCacheSize = try {
            val cacheDir = java.io.File(context.cacheDir, "image_cache")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        stats["media_disk_cache"] = formatBytes(mediaDiskCacheSize)
        
        return stats
    }
    
    /**
     * @deprecated Room data is in-memory only - only in-memory caches need clearing
     */
    /**
     * Format bytes to human-readable string (e.g., "1.5 MB")
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
    
}


data class SingleEventLoadResult(
    val event: TimelineEvent?,
    val contextEvents: List<TimelineEvent> = emptyList(),
    val error: String? = null
)

// Helper to safely access application context from extensions