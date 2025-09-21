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

    fun updateSpacesFromSyncJson(syncJson: JSONObject) {
        val spaces = SpaceRoomParser.parseSpacesAndRooms(syncJson)
        setSpaces(spaces)
        spacesLoaded = true
    }
}
