package net.vrkknn.andromuks.database

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.RoomStateEntity
import net.vrkknn.andromuks.database.entities.RoomSummaryEntity
import org.json.JSONObject
import java.util.UUID

/**
 * Test utilities for database tests
 */
object DatabaseTestUtils {
    
    /**
     * Create an in-memory database for testing (faster than disk-based)
     */
    fun createInMemoryDatabase(context: Context): AndromuksDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            AndromuksDatabase::class.java
        )
            .allowMainThreadQueries() // For testing only
            .build()
    }
    
    /**
     * Create a test event entity
     */
    fun createTestEvent(
        eventId: String = UUID.randomUUID().toString(),
        roomId: String = "!test:example.com",
        timelineRowId: Long = System.currentTimeMillis(),
        timestamp: Long = System.currentTimeMillis(),
        type: String = "m.room.message",
        sender: String = "@test:example.com",
        decryptedType: String? = null,
        relatesToEventId: String? = null,
        threadRootEventId: String? = null,
        isRedaction: Boolean = false,
        rawJson: String = """{"type":"$type","sender":"$sender","event_id":"$eventId"}"""
    ): EventEntity {
        return EventEntity(
            eventId = eventId,
            roomId = roomId,
            timelineRowId = timelineRowId,
            timestamp = timestamp,
            type = type,
            sender = sender,
            decryptedType = decryptedType,
            relatesToEventId = relatesToEventId,
            threadRootEventId = threadRootEventId,
            isRedaction = isRedaction,
            rawJson = rawJson
        )
    }
    
    /**
     * Create a test room state entity
     */
    fun createTestRoomState(
        roomId: String = "!test:example.com",
        name: String? = "Test Room",
        topic: String? = "Test Topic",
        avatarUrl: String? = null,
        canonicalAlias: String? = null,
        isDirect: Boolean = false,
        isFavourite: Boolean = false,
        isLowPriority: Boolean = false
    ): RoomStateEntity {
        return RoomStateEntity(
            roomId = roomId,
            name = name,
            topic = topic,
            avatarUrl = avatarUrl,
            canonicalAlias = canonicalAlias,
            isDirect = isDirect,
            isFavourite = isFavourite,
            isLowPriority = isLowPriority,
            bridgeInfoJson = null,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Create a test room summary entity
     */
    fun createTestRoomSummary(
        roomId: String = "!test:example.com",
        lastEventId: String? = null,
        lastTimestamp: Long = System.currentTimeMillis(),
        unreadCount: Int = 0,
        highlightCount: Int = 0,
        messageSender: String? = null,
        messagePreview: String? = null
    ): RoomSummaryEntity {
        return RoomSummaryEntity(
            roomId = roomId,
            lastEventId = lastEventId,
            lastTimestamp = lastTimestamp,
            unreadCount = unreadCount,
            highlightCount = highlightCount,
            messageSender = messageSender,
            messagePreview = messagePreview
        )
    }
    
    /**
     * Create a minimal sync_complete JSON for testing
     * Note: The actual ingestSyncComplete requires requestId and runId as separate parameters
     */
    fun createTestSyncCompleteJson(
        runId: String = "test_run_${System.currentTimeMillis()}",
        lastReceivedId: String = "last_${System.currentTimeMillis()}",
        rooms: Map<String, JSONObject> = emptyMap()
    ): JSONObject {
        val syncJson = JSONObject()
        
        // Create data object (required by ingestSyncComplete)
        val data = JSONObject()
        data.put("run_id", runId)
        data.put("last_received_id", lastReceivedId)
        
        if (rooms.isNotEmpty()) {
            val roomsObj = JSONObject()
            rooms.forEach { (roomId, roomData) ->
                roomsObj.put(roomId, roomData)
            }
            data.put("rooms", roomsObj)
        }
        
        syncJson.put("data", data)
        return syncJson
    }
    
    /**
     * Create a test room JSON object for sync_complete
     */
    fun createTestRoomJson(
        roomId: String = "!test:example.com",
        hasTimeline: Boolean = true,
        eventCount: Int = 3
    ): JSONObject {
        val roomObj = JSONObject()
        
        // Meta
        val meta = JSONObject()
        meta.put("name", "Test Room")
        meta.put("topic", "Test Topic")
        meta.put("dm_user_id", "") // Not a DM
        roomObj.put("meta", meta)
        
        // Timeline
        if (hasTimeline) {
            val timeline = org.json.JSONArray()
            for (i in 0 until eventCount) {
                val timelineEntry = JSONObject()
                val event = JSONObject()
                event.put("event_id", "event_${roomId}_$i")
                event.put("type", "m.room.message")
                event.put("sender", "@test:example.com")
                event.put("origin_server_ts", System.currentTimeMillis() - (eventCount - i) * 1000)
                event.put("timeline_rowid", System.currentTimeMillis() - (eventCount - i) * 1000)
                
                val content = JSONObject()
                content.put("body", "Test message $i")
                content.put("msgtype", "m.text")
                event.put("content", content)
                
                timelineEntry.put("event", event)
                timeline.put(timelineEntry)
            }
            roomObj.put("timeline", timeline)
        }
        
        // Room summary
        val summary = JSONObject()
        summary.put("last_event_id", "event_${roomId}_${eventCount - 1}")
        summary.put("last_timestamp", System.currentTimeMillis())
        summary.put("unread_messages", 0)
        summary.put("unread_highlights", 0)
        roomObj.put("summary", summary)
        
        return roomObj
    }
}

