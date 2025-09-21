package net.vrkknn.andromuks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.utils.SpaceRoomParser
import org.json.JSONObject

class AppViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)

    // List of spaces, each with their rooms
    var spaceList by mutableStateOf(listOf<SpaceItem>())
        private set

    var spacesLoaded by mutableStateOf(false)
        private set

    fun setSpaces(spaces: List<SpaceItem>) {
        spaceList = spaces
    }

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }

    private val allRooms = mutableListOf<RoomItem>()
    private var syncMessageCount = 0
    
    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        val rooms = SpaceRoomParser.parseRooms(syncJson)
        // Use a Set to avoid duplicates based on room ID
        val existingIds = allRooms.map { it.id }.toSet()
        val newRooms = rooms.filter { !existingIds.contains(it.id) }
        allRooms.addAll(newRooms)
        syncMessageCount++
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${allRooms.size} (added ${newRooms.size} new) - sync message #$syncMessageCount")
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = allRooms.toList())))
        
        // Temporary workaround: navigate after 3 sync messages if we have rooms
        if (syncMessageCount >= 3 && allRooms.isNotEmpty() && !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: Workaround - navigating after $syncMessageCount sync messages")
            spacesLoaded = true
            if (onNavigateToRoomList != null) {
                onNavigateToRoomList?.invoke()
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
                pendingNavigation = true
            }
        }
    }
    
    fun onInitComplete() {
        android.util.Log.d("Andromuks", "AppViewModel: onInitComplete called - setting spacesLoaded = true")
        spacesLoaded = true
        android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        if (onNavigateToRoomList != null) {
            onNavigateToRoomList?.invoke()
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
            pendingNavigation = true
        }
    }
    
    // Navigation callback
    var onNavigateToRoomList: (() -> Unit)? = null
    private var pendingNavigation = false
    
    fun setNavigationCallback(callback: () -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Navigation callback set")
        onNavigateToRoomList = callback
        
        // If we have a pending navigation, trigger it now
        if (pendingNavigation) {
            android.util.Log.d("Andromuks", "AppViewModel: Triggering pending navigation")
            pendingNavigation = false
            callback()
        }
    }
}
