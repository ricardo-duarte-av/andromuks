package net.vrkknn.andromuks.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.R
import net.vrkknn.andromuks.RoomSectionType
import net.vrkknn.andromuks.database.AndromuksDatabase
import net.vrkknn.andromuks.database.ProfileRepository
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.RoomMemberDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import android.util.Log

/**
 * Android Auto screen showing the list of rooms/conversations filtered by section type or space.
 */
class CarRoomListScreen(
    carContext: CarContext,
    private val sectionType: RoomSectionType = RoomSectionType.HOME,
    private val spaceId: String? = null,
    private val spaceName: String? = null
) : Screen(carContext) {
    
    companion object {
        private const val TAG = "CarRoomListScreen"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rooms: List<RoomItem> = emptyList()
    private var homeserverUrl: String = ""
    private var authToken: String = ""
    private val avatarCache = mutableMapOf<String, CarIcon>() // Cache avatars by roomId
    
    init {
        // Load homeserver URL and auth token from SharedPreferences first, then observe rooms
        scope.launch {
            loadCredentials()
            // Ensure data is refreshed when screen is opened (similar to RoomListScreen startup)
            // Get initial value immediately, then continue observing for updates
            refreshRooms()
            // Start observing room summaries for real-time updates after credentials are loaded
            observeRooms()
        }
    }
    
    private suspend fun loadCredentials() {
        try {
            val sharedPrefs = carContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
            authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded credentials - homeserverUrl: ${if (homeserverUrl.isNotEmpty()) "present" else "missing"}, authToken: ${if (authToken.isNotEmpty()) "present" else "missing"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading credentials", e)
        }
    }
    
    /**
     * Refresh rooms data when screen is opened (similar to RoomListScreen startup refresh).
     * This ensures we have the latest data immediately when navigating to the screen.
     */
    private suspend fun refreshRooms() {
        try {
            val database = AndromuksDatabase.getInstance(carContext)
            val roomSummaryDao = database.roomSummaryDao()
            val roomStateDao = database.roomStateDao()
            val roomMemberDao = database.roomMemberDao()
            val spaceDao = database.spaceDao()
            val spaceRoomDao = database.spaceRoomDao()
            
            // Get initial room summaries immediately
            val roomSummaries = withContext(Dispatchers.IO) {
                roomSummaryDao.getAllRoomsFlow().first()
            }
            
            // Process and update rooms immediately
            processRoomSummaries(roomSummaries, roomStateDao, roomMemberDao, spaceDao, spaceRoomDao)
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Refreshed ${rooms.size} rooms for section $sectionType${if (spaceId != null) " space $spaceId" else ""} on screen open")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing rooms on screen open", e)
        }
    }
    
    /**
     * Process room summaries and update the rooms list.
     * Shared between refreshRooms() and observeRooms() to avoid code duplication.
     * Filters rooms based on the section type or spaceId.
     */
    private suspend fun processRoomSummaries(
        roomSummaries: List<net.vrkknn.andromuks.database.entities.RoomSummaryEntity>,
        roomStateDao: RoomStateDao,
        roomMemberDao: RoomMemberDao,
        spaceDao: SpaceDao,
        spaceRoomDao: SpaceRoomDao
    ) {
        try {
            // Get room states to get names and avatars
            val roomIds = roomSummaries.map { it.roomId }
            val roomStates = withContext(Dispatchers.IO) {
                if (roomIds.isNotEmpty()) {
                    roomStateDao.getRoomStatesByIds(roomIds).associateBy { it.roomId }
                } else {
                    emptyMap()
                }
            }
            
            // Build map of all member profiles for sender name lookups
            // First, collect all unique sender user IDs from room summaries
            val allSenderIds = roomSummaries.mapNotNull { it.messageSender }.distinct()
            
            // Load profiles from ProfileRepository (more up-to-date than RoomMemberDao)
            val profileRepository = ProfileRepository(carContext)
            val globalProfiles = withContext(Dispatchers.IO) {
                if (allSenderIds.isNotEmpty()) {
                    profileRepository.loadProfiles(allSenderIds)
                } else {
                    emptyMap()
                }
            }
            
            // Build map of all member profiles for sender name lookups (used as fallback if globalProfiles doesn't have the user)
            // Use ProfileRepository first, then RoomMemberDao, then fallback to username localpart
            val memberProfilesMap = mutableMapOf<String, Map<String, String>>() // roomId -> (userId -> displayName)
            for (roomId in roomIds) {
                val members = withContext(Dispatchers.IO) {
                    roomMemberDao.getMembersForRoom(roomId)
                        .associate { member ->
                            val userId = member.userId
                            // Priority: 1. ProfileRepository, 2. RoomMember displayName, 3. username localpart
                            val displayName = globalProfiles[userId]?.displayName
                                ?: member.displayName
                                ?: userId.substringAfter("@").substringBefore(":")
                            userId to displayName
                        }
                }
                memberProfilesMap[roomId] = members
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded ${globalProfiles.size} profiles from ProfileRepository for ${allSenderIds.size} unique senders")
            }
            
            // Get space IDs to filter them out for HOME section, or get rooms for spaceId
            val spaceIds = withContext(Dispatchers.IO) {
                if (sectionType == RoomSectionType.HOME) {
                    spaceDao.getAllSpaces().map { it.spaceId }.toSet()
                } else {
                    emptySet()
                }
            }
            
            // If spaceId is provided, get room IDs for that space
            val spaceRoomIds = if (spaceId != null) {
                withContext(Dispatchers.IO) {
                    spaceRoomDao.getRoomsForSpace(spaceId).map { it.childId }.toSet()
                }
            } else {
                emptySet()
            }
            
            // Convert to RoomItems with sender information and filter by section type or spaceId
            rooms = roomSummaries.mapNotNull { summary ->
                val roomState = roomStates[summary.roomId]
                
                // Filter based on spaceId (if provided) or section type
                if (spaceId != null) {
                    // SPACE: only rooms in this space
                    if (summary.roomId !in spaceRoomIds) return@mapNotNull null
                } else {
                    // Filter based on section type
                    when (sectionType) {
                        RoomSectionType.HOME -> {
                            // HOME: exclude spaces
                            if (summary.roomId in spaceIds) return@mapNotNull null
                        }
                        RoomSectionType.DIRECT_CHATS -> {
                            // DIRECT: only direct messages
                            if (roomState?.isDirect != true) return@mapNotNull null
                        }
                        RoomSectionType.MENTIONS -> {
                            // MENTIONS: exclude from car screen (handled by main app)
                            return@mapNotNull null
                        }
                        RoomSectionType.UNREAD -> {
                            // UNREAD: only rooms with unread or highlights
                            if ((summary.unreadCount ?: 0) == 0 && (summary.highlightCount ?: 0) == 0) {
                                return@mapNotNull null
                            }
                        }
                        RoomSectionType.FAVOURITES -> {
                            // FAVOURITES: only favourite rooms
                            if (roomState?.isFavourite != true) return@mapNotNull null
                        }
                        RoomSectionType.SPACES -> {
                            // SPACES: handled by CarSpacesListScreen, should not be here
                            return@mapNotNull null
                        }
                    }
                }
                
                // Use globalProfiles directly for sender display name (more reliable than memberProfilesMap)
                // Priority: 1. ProfileRepository (globalProfiles), 2. RoomMember displayName, 3. username localpart
                val senderDisplayName = if (summary.messageSender != null) {
                    globalProfiles[summary.messageSender]?.displayName?.takeIf { it.isNotBlank() }
                        ?: memberProfilesMap[summary.roomId]?.get(summary.messageSender)?.takeIf { it.isNotBlank() }
                        ?: summary.messageSender.substringAfter("@").substringBefore(":")
                } else {
                    null
                }
                
                if (BuildConfig.DEBUG) {
                    if (summary.messageSender != null && senderDisplayName == summary.messageSender.substringAfter("@").substringBefore(":")) {
                        Log.d(TAG, "Using fallback username for sender ${summary.messageSender} in room ${summary.roomId} (no profile found)")
                    }
                    // Debug logging for encrypted room summaries
                    if (summary.messagePreview.isNullOrBlank() && summary.messageSender != null) {
                        Log.d(TAG, "Room ${summary.roomId} has sender ${summary.messageSender} but empty messagePreview - may be encrypted room with no decrypted content")
                    }
                }
                
                RoomItem(
                    roomId = summary.roomId,
                    name = roomState?.name ?: summary.roomId,
                    avatarUrl = roomState?.avatarUrl,
                    lastMessage = summary.messagePreview?.takeIf { it.isNotBlank() } ?: "",
                    messageSender = senderDisplayName,
                    lastTimestamp = summary.lastTimestamp,
                    unreadCount = summary.unreadCount
                )
            }.sortedByDescending { it.lastTimestamp }
            
            invalidate() // Refresh the UI
            
            // Preload avatars for rooms in background (after UI is invalidated)
            preloadAvatars(rooms)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing room summaries", e)
        }
    }
    
    private suspend fun observeRooms() {
        try {
            val database = AndromuksDatabase.getInstance(carContext)
            val roomSummaryDao = database.roomSummaryDao()
            val roomStateDao = database.roomStateDao()
            val roomMemberDao = database.roomMemberDao()
            val spaceDao = database.spaceDao()
            val spaceRoomDao = database.spaceRoomDao()
            
            // Observe room summaries reactively - updates automatically when database changes
            // Skip first emission since we already loaded it in refreshRooms()
            val flow = if (spaceId != null) {
                // For space rooms, also observe space_rooms changes
                kotlinx.coroutines.flow.combine(
                    roomSummaryDao.getAllRoomsFlow(),
                    spaceRoomDao.getRoomsForSpaceFlow(spaceId)
                ) { summaries, _ -> summaries }
            } else {
                roomSummaryDao.getAllRoomsFlow()
            }
            
            flow.distinctUntilChanged()
                .collect { roomSummaries ->
                    processRoomSummaries(roomSummaries, roomStateDao, roomMemberDao, spaceDao, spaceRoomDao)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Updated ${rooms.size} rooms for section $sectionType${if (spaceId != null) " space $spaceId" else ""} (reactive update)")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error observing rooms", e)
        }
    }
    
    override fun onGetTemplate(): Template {
        val exitAction = Action.Builder()
            .setTitle(carContext.getString(R.string.car_action_close))
            .setOnClickListener {
                carContext.finishCarApp()
            }
            .build()
        
        val actionStrip = ActionStrip.Builder()
            .addAction(exitAction)
            .build()
        
        // Build the list of rooms
        val itemListBuilder = ItemList.Builder()
        
        if (rooms.isEmpty()) {
            // Show empty state
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("No conversations")
                    .addText("You don't have any conversations yet.")
                    .build()
            )
        } else {
            // Add each room as a row
            rooms.take(20).forEach { room -> // Limit to 20 for performance
                val roomName = if (room.name.length > 30) {
                    room.name.take(27) + "..."
                } else {
                    room.name
                }
                
                // Format message preview: "SenderName: message" (matching main app format)
                val messagePreview = if (room.messageSender != null && room.lastMessage.isNotEmpty()) {
                    val senderPrefix = "${room.messageSender}: "
                    val maxMessageLength = 50 - senderPrefix.length
                    val messageText = if (room.lastMessage.length > maxMessageLength) {
                        room.lastMessage.take(maxMessageLength - 3) + "..."
                    } else {
                        room.lastMessage
                    }
                    "$senderPrefix$messageText"
                } else {
                    if (room.lastMessage.length > 50) {
                        room.lastMessage.take(47) + "..."
                    } else {
                        room.lastMessage
                    }
                }
                
                val unreadBadge = if (room.unreadCount > 0) {
                    " (${room.unreadCount})"
                } else {
                    ""
                }
                
                // Build row with avatar if available in cache
                val rowBuilder = Row.Builder()
                    .setTitle("$roomName$unreadBadge")
                    .addText(messagePreview)
                    .setOnClickListener {
                        // Navigate to room timeline
                        screenManager.push(CarRoomTimelineScreen(carContext, room.roomId, room.name))
                    }
                
                // Add avatar if cached (avatars are preloaded in background)
                avatarCache[room.roomId]?.let { carIcon ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Adding cached avatar for room ${room.roomId}")
                    rowBuilder.setImage(carIcon)
                } ?: run {
                    if (BuildConfig.DEBUG && room.avatarUrl != null) {
                        Log.d(TAG, "Avatar not yet cached for room ${room.roomId}, will load in background")
                    }
                }
                
                itemListBuilder.addItem(rowBuilder.build())
            }
        }
        
        val title = if (spaceId != null && spaceName != null) {
            if (spaceName.length > 30) spaceName.take(27) + "..." else spaceName
        } else {
            when (sectionType) {
                RoomSectionType.HOME -> "All Rooms"
                RoomSectionType.DIRECT_CHATS -> "Direct Messages"
                RoomSectionType.UNREAD -> "Unread"
                RoomSectionType.FAVOURITES -> "Favourites"
                RoomSectionType.SPACES -> "Spaces" // Should not appear, handled by CarSpacesListScreen
                RoomSectionType.MENTIONS -> "Mentions"
            }
        }
        
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
    
    /**
     * Preload avatars for rooms in background and cache them
     */
    private fun preloadAvatars(rooms: List<RoomItem>) {
        scope.launch {
            // Only proceed if credentials are loaded
            if (homeserverUrl.isEmpty()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cannot preload avatars - homeserverUrl not loaded yet, will retry later")
                return@launch
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Starting to preload avatars for ${rooms.take(20).size} rooms")
            
            rooms.take(20).forEach { room -> // Only preload for visible rooms
                if (room.avatarUrl != null && !avatarCache.containsKey(room.roomId)) {
                    try {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Loading avatar for room ${room.roomId} from ${room.avatarUrl}")
                        val carIcon = loadRoomAvatar(room.avatarUrl, room.roomId, room.name)
                        if (carIcon != null) {
                            avatarCache[room.roomId] = carIcon
                            if (BuildConfig.DEBUG) Log.d(TAG, "✓ Successfully loaded and cached avatar for room ${room.roomId}")
                            invalidate() // Refresh UI when avatar is loaded
                        } else {
                            if (BuildConfig.DEBUG) Log.d(TAG, "✗ Failed to load avatar for room ${room.roomId} (returned null)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading avatar for room ${room.roomId}", e)
                    }
                } else if (BuildConfig.DEBUG) {
                    if (room.avatarUrl == null) {
                        Log.d(TAG, "Room ${room.roomId} has no avatar URL")
                    } else if (avatarCache.containsKey(room.roomId)) {
                        Log.d(TAG, "Room ${room.roomId} avatar already cached")
                    }
                }
            }
        }
    }
    
    /**
     * Load room avatar as CarIcon for display in Android Auto
     */
    private suspend fun loadRoomAvatar(avatarUrl: String?, roomId: String, roomName: String): CarIcon? = withContext(Dispatchers.IO) {
        try {
            if (avatarUrl.isNullOrEmpty() || homeserverUrl.isEmpty()) {
                return@withContext null
            }
            
            // Check cache first
            val cachedFile = MediaCache.getCachedFile(carContext, avatarUrl)
            val bitmap = if (cachedFile != null && cachedFile.exists()) {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            } else {
                // Convert MXC URL to HTTP URL
                val httpUrl = when {
                    avatarUrl.startsWith("mxc://") -> {
                        AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                    }
                    avatarUrl.startsWith("_gomuks/") -> {
                        "$homeserverUrl/$avatarUrl"
                    }
                    else -> {
                        avatarUrl
                    }
                }
                
                if (httpUrl == null) {
                    return@withContext null
                }
                
                // Download and cache
                val downloadedFile = MediaCache.downloadAndCache(carContext, avatarUrl, httpUrl, authToken)
                if (downloadedFile != null && downloadedFile.exists()) {
                    BitmapFactory.decodeFile(downloadedFile.absolutePath)
                } else {
                    null
                }
            }
            
            if (bitmap != null) {
                // Create circular bitmap for avatar
                val circularBitmap = createCircularBitmap(bitmap)
                // CarIcon requires regular bitmap, not adaptive bitmap (which creates type 5/CUSTOM)
                val iconCompat = IconCompat.createWithBitmap(circularBitmap)
                CarIcon.Builder(iconCompat).build()
            } else {
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error loading room avatar for $roomId", e)
            }
            null
        }
    }
    
    /**
     * Create a circular bitmap from a source bitmap (for avatar display)
     */
    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        // Convert hardware bitmap to software bitmap if needed
        val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        
        val size = Math.min(softwareBitmap.width, softwareBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)
        
        // Clean up the software bitmap if we created a copy
        if (softwareBitmap != bitmap) {
            softwareBitmap.recycle()
        }
        
        return output
    }
    
    private data class RoomItem(
        val roomId: String,
        val name: String,
        val avatarUrl: String?,
        val lastMessage: String,
        val messageSender: String?,
        val lastTimestamp: Long,
        val unreadCount: Int
    )
}

