package net.vrkknn.andromuks

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

class RoomListViewModel : ViewModel() {
    private val _roomList = MutableStateFlow<List<RoomItem>>(emptyList())
    val roomList: StateFlow<List<RoomItem>> = _roomList.asStateFlow()

    init {
        viewModelScope.launch {
            WebSocketEvents.messages.collect { message ->
//                Log.d("RoomListViewModel", "Received WebSocket message: $message")
                parseWebSocketMessage(message)
            }
        }
    }

    private fun parseWebSocketMessage(message: String) {
        try {
            val jsonMessage = JSONObject(message)
            when (jsonMessage.optString("command")) {
                "sync_complete" -> {
                    val data = jsonMessage.optJSONObject("data")
                    if (data != null) {
                        val roomsObject = data.optJSONObject("rooms")
                        if (roomsObject != null) {
                            updateRoomListFromObject(roomsObject)
                        } else {
                            Log.w("RoomListViewModel", "'rooms' object not found in 'sync_complete' data.")
                        }
                    } else {
                        Log.w("RoomListViewModel", "'data' object not found in 'sync_complete' message.")
                    }
                }

                "client_state", "EventClientState" -> {
                    val data = jsonMessage.optJSONObject("data")
                    if (data != null) {
                        val roomsObject = data.optJSONObject("rooms")
                        if (roomsObject != null) {
                            updateRoomListFromObject(roomsObject)
                        } else {
                            val roomsArray = data.optJSONArray("rooms")
                            if (roomsArray != null) {
                                Log.w("RoomListViewModel", "Parsed 'rooms' as array from client_state, implement if needed.")
                            } else {
                                Log.w("RoomListViewModel", "'rooms' not found in 'client_state' data.")
                            }
                        }
                    } else {
                        Log.w("RoomListViewModel", "'data' object not found in 'client_state' message.")
                    }
                }

                else -> {
                    Log.v("RoomListViewModel", "Unhandled command: ${jsonMessage.optString("command")}")
                }
            }
        } catch (e: Exception) {
            Log.e("RoomListViewModel", "Error parsing WebSocket message: $message", e)
        }
    }

    private fun updateRoomListFromObject(roomsJsonObject: JSONObject) {
        val newRoomList = mutableListOf<RoomItem>()
        val roomIds = roomsJsonObject.keys()

        while (roomIds.hasNext()) {
            val roomId = roomIds.next()
            val roomDetails = roomsJsonObject.optJSONObject(roomId)
            if (roomDetails != null) {
                val meta = roomDetails.optJSONObject("meta")
                if (meta != null) {
                    val roomName = meta.optString("name", "Unknown Room")
                    newRoomList.add(RoomItem(roomId = roomId, name = roomName))
                } else {
                    Log.w("RoomListViewModel", "Room '$roomId' missing 'meta' object")
                    newRoomList.add(RoomItem(roomId = roomId, name = "Unknown Room"))
                }
            }
        }
        _roomList.value = newRoomList.sortedBy { it.name }
        Log.d("RoomListViewModel", "Updated room list: ${_roomList.value.size} rooms")
    }

//    private fun updateRoomList(roomsJsonArray: JSONArray) {
//        val newRoomList = mutableListOf<RoomItem>()
//        for (i in 0 until roomsJsonArray.length()) {
//            val roomJson = roomsJsonArray.optJSONObject(i)
//            if (roomJson != null) {
//                val roomId = roomJson.optString("room_id", "") // From React: room.room_id
//                val roomName = roomJson.optString("name", "Unknown Room") // From React: room.name
//                if (roomId.isNotEmpty()) {
//                    newRoomList.add(RoomItem(roomId = roomId, name = roomName))
//                }
//            }
//        }
//        _roomList.value = newRoomList
//        Log.d("RoomListViewModel", "Updated room list: ${_roomList.value.size} rooms")
//    }

    override fun onCleared() {
        super.onCleared()
        Log.d("RoomListViewModel", "ViewModel cleared.")
    }
}
