@file:Suppress("DEPRECATION")

package net.vrkknn.andromuks.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import android.util.Log

/**
 * Android Auto screen showing the list of top-level spaces.
 */
class CarSpacesListScreen(carContext: CarContext) : Screen(carContext) {
    
    companion object {
        private const val TAG = "CarSpacesListScreen"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var spaces: List<SpaceItem> = emptyList()
    private var homeserverUrl: String = ""
    private var authToken: String = ""
    private val avatarCache = mutableMapOf<String, CarIcon>() // Cache avatars by spaceId
    
    init {
        scope.launch {
            loadCredentials()
            refreshSpaces()
            observeSpaces()
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
    
    private suspend fun refreshSpaces() {
        try {
            val database = AndromuksDatabase.getInstance(carContext)
            val spaceDao = database.spaceDao()
            val spaceRoomDao = database.spaceRoomDao()
            
            // Get initial spaces immediately
            val allSpaces = withContext(Dispatchers.IO) {
                spaceDao.getAllSpacesFlow().first()
            }
            val allSpaceRooms = withContext(Dispatchers.IO) {
                spaceRoomDao.getAllRoomsForAllSpacesFlow().first()
            }
            
            // Filter to top-level spaces (spaces that are NOT children of other spaces)
            val allSpaceIds = allSpaces.map { it.spaceId }.toSet()
            val childSpaceIds = allSpaceRooms
                .map { it.childId }
                .filter { it in allSpaceIds } // Only consider children that are themselves spaces
                .toSet()
            
            val topLevelSpaces = allSpaces
                .filter { it.spaceId !in childSpaceIds }
                .sortedWith(compareBy({ it.order }, { it.spaceId }))
            
            spaces = topLevelSpaces.map { spaceEntity ->
                SpaceItem(
                    spaceId = spaceEntity.spaceId,
                    name = spaceEntity.name ?: spaceEntity.spaceId,
                    avatarUrl = spaceEntity.avatarUrl
                )
            }
            
            invalidate()
            preloadAvatars(spaces)
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Refreshed ${spaces.size} top-level spaces on screen open")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing spaces on screen open", e)
        }
    }
    
    private suspend fun observeSpaces() {
        try {
            val database = AndromuksDatabase.getInstance(carContext)
            val spaceDao = database.spaceDao()
            val spaceRoomDao = database.spaceRoomDao()
            
            // Observe spaces reactively
            combine(
                spaceDao.getAllSpacesFlow(),
                spaceRoomDao.getAllRoomsForAllSpacesFlow()
            ) { allSpaces, allSpaceRooms ->
                // Filter to top-level spaces (spaces that are NOT children of other spaces)
                val allSpaceIds = allSpaces.map { it.spaceId }.toSet()
                val childSpaceIds = allSpaceRooms
                    .map { it.childId }
                    .filter { it in allSpaceIds }
                    .toSet()
                
                val topLevelSpaces = allSpaces
                    .filter { it.spaceId !in childSpaceIds }
                    .sortedWith(compareBy({ it.order }, { it.spaceId }))
                
                topLevelSpaces.map { spaceEntity ->
                    SpaceItem(
                        spaceId = spaceEntity.spaceId,
                        name = spaceEntity.name ?: spaceEntity.spaceId,
                        avatarUrl = spaceEntity.avatarUrl
                    )
                }
            }
                .distinctUntilChanged()
                .collect { newSpaces ->
                    spaces = newSpaces
                    invalidate()
                    preloadAvatars(spaces)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Updated ${spaces.size} top-level spaces (reactive update)")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error observing spaces", e)
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
        
        val itemListBuilder = ItemList.Builder()
        
        if (spaces.isEmpty()) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("No spaces")
                    .addText("You don't have any spaces yet.")
                    .build()
            )
        } else {
            spaces.forEach { space ->
                val spaceName = if (space.name.length > 30) {
                    space.name.take(27) + "..."
                } else {
                    space.name
                }
                
                val rowBuilder = Row.Builder()
                    .setTitle(spaceName)
                    .setOnClickListener {
                        // Navigate to rooms in this space using CarRoomListScreen with spaceId
                        screenManager.push(CarRoomListScreen(carContext, RoomSectionType.SPACES, space.spaceId, space.name))
                    }
                
                // Add avatar if cached
                avatarCache[space.spaceId]?.let { carIcon ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Adding cached avatar for space ${space.spaceId}")
                    rowBuilder.setImage(carIcon)
                }
                
                itemListBuilder.addItem(rowBuilder.build())
            }
        }
        
        return ListTemplate.Builder()
            .setTitle("Spaces")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
    
    private fun preloadAvatars(spaces: List<SpaceItem>) {
        scope.launch {
            if (homeserverUrl.isEmpty()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cannot preload avatars - homeserverUrl not loaded yet")
                return@launch
            }
            
            spaces.forEach { space ->
                if (space.avatarUrl != null && !avatarCache.containsKey(space.spaceId)) {
                    try {
                        val carIcon = loadSpaceAvatar(space.avatarUrl, space.spaceId, space.name)
                        if (carIcon != null) {
                            avatarCache[space.spaceId] = carIcon
                            invalidate()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading avatar for space ${space.spaceId}", e)
                    }
                }
            }
        }
    }
    
    private suspend fun loadSpaceAvatar(avatarUrl: String?, spaceId: String, spaceName: String): CarIcon? = withContext(Dispatchers.IO) {
        try {
            if (avatarUrl.isNullOrEmpty() || homeserverUrl.isEmpty()) {
                return@withContext null
            }
            
            val cachedFile = MediaCache.getCachedFile(carContext, avatarUrl)
            val bitmap = if (cachedFile != null && cachedFile.exists()) {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            } else {
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
                
                val downloadedFile = MediaCache.downloadAndCache(carContext, avatarUrl, httpUrl, authToken)
                if (downloadedFile != null && downloadedFile.exists()) {
                    BitmapFactory.decodeFile(downloadedFile.absolutePath)
                } else {
                    null
                }
            }
            
            if (bitmap != null) {
                val circularBitmap = createCircularBitmap(bitmap)
                val iconCompat = IconCompat.createWithBitmap(circularBitmap)
                CarIcon.Builder(iconCompat).build()
            } else {
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error loading space avatar for $spaceId", e)
            }
            null
        }
    }
    
    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
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
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)
        
        if (softwareBitmap != bitmap) {
            softwareBitmap.recycle()
        }
        
        return output
    }
    
    private data class SpaceItem(
        val spaceId: String,
        val name: String,
        val avatarUrl: String?
    )
}
