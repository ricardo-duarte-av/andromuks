package net.vrkknn.andromuks.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Tests for SyncIngestor
 * 
 * Tests:
 * - Event persistence from sync_complete
 * - Event deduplication
 * - Edit event handling
 * - Redaction handling
 * - Run ID change detection and data clearing
 * 
 * Note: These tests use the actual database singleton pattern.
 * For more isolated testing, consider dependency injection in SyncIngestor.
 */
@RunWith(AndroidJUnit4::class)
class SyncIngestorTest {
    
    private lateinit var syncIngestor: SyncIngestor
    private lateinit var database: AndromuksDatabase
    private lateinit var eventDao: EventDao
    private lateinit var syncMetaDao: SyncMetaDao
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Before
    fun setUp() {
        syncIngestor = SyncIngestor(context)
        // Get the actual database instance (singleton)
        database = AndromuksDatabase.getInstance(context)
        eventDao = database.eventDao()
        syncMetaDao = database.syncMetaDao()
        val roomStateDao = database.roomStateDao()
        val roomSummaryDao = database.roomSummaryDao()
        val receiptDao = database.receiptDao()
        
        // Clear all existing data before each test
        runBlocking {
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            syncMetaDao.deleteAll()
        }
    }
    
    @After
    fun tearDown() {
        // Clean up test data after each test
        runBlocking {
            val roomStateDao = database.roomStateDao()
            val roomSummaryDao = database.roomSummaryDao()
            val receiptDao = database.receiptDao()
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            syncMetaDao.deleteAll()
        }
    }
    
    @Test
    fun testIngestSyncComplete() = runBlocking {
        // Given - a sync_complete JSON with events
        val runId = "run_1"
        val requestId = 12345
        val syncJson = DatabaseTestUtils.createTestSyncCompleteJson(
            runId = runId,
            lastReceivedId = requestId.toString(),
            rooms = mapOf(
                "!room1:example.com" to DatabaseTestUtils.createTestRoomJson(
                    roomId = "!room1:example.com",
                    eventCount = 3
                )
            )
        )
        
        // When - ingest the sync
        syncIngestor.ingestSyncComplete(syncJson, requestId, runId)
        
        // Then - events should be persisted
        val events = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertTrue("Should have persisted events", events.isNotEmpty())
        
        // Verify run_id was stored
        val storedRunId = syncMetaDao.get("run_id")
        assertEquals(runId, storedRunId)
    }
    
    @Test
    fun testEventDeduplication() = runBlocking {
        // Given - same event ID in two sync messages
        val eventId = "duplicate_event_1"
        val roomId = "!room1:example.com"
        val runId = "run_1"
        
        // First sync
        val sync1 = createSyncWithEvent(roomId, eventId, "First message", 1000L)
        syncIngestor.ingestSyncComplete(sync1, 1001, runId)
        
        // Second sync with same event ID but different content
        val sync2 = createSyncWithEvent(roomId, eventId, "Updated message", 2000L)
        syncIngestor.ingestSyncComplete(sync2, 1002, runId)
        
        // Then - should only have one event (last one wins via upsert)
        val events = eventDao.getEventsForRoomDesc(roomId, 10)
        assertEquals(1, events.size)
        assertEquals(eventId, events[0].eventId)
        // Should have the updated content (last write wins)
        assertTrue(events[0].rawJson.contains("Updated"))
    }
    
    @Test
    fun testRunIdChangeClearsData() = runBlocking {
        // Given - initial sync with run_id_1
        val runId1 = "run_id_1"
        val sync1 = DatabaseTestUtils.createTestSyncCompleteJson(
            runId = runId1,
            rooms = mapOf(
                "!room1:example.com" to DatabaseTestUtils.createTestRoomJson(
                    roomId = "!room1:example.com",
                    eventCount = 5
                )
            )
        )
        syncIngestor.ingestSyncComplete(sync1, 2001, runId1)
        
        // Verify events exist
        var events = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(5, events.size)
        
        // When - new sync with different run_id
        val runId2 = "run_id_2"
        val sync2 = DatabaseTestUtils.createTestSyncCompleteJson(
            runId = runId2, // Different run_id
            rooms = mapOf(
                "!room1:example.com" to DatabaseTestUtils.createTestRoomJson(
                    roomId = "!room1:example.com",
                    eventCount = 2
                )
            )
        )
        syncIngestor.ingestSyncComplete(sync2, 2002, runId2)
        
        // Then - old events should be cleared, only new ones remain
        events = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(2, events.size) // Only new events from sync2
        
        // Verify run_id was updated
        val runId = syncMetaDao.get("run_id")
        assertEquals("run_id_2", runId)
    }
    
    @Test
    fun testEditEventHandling() = runBlocking {
        // Given - original message and edit event
        val roomId = "!room1:example.com"
        val originalEventId = "original_event_1"
        val editEventId = "edit_event_1"
        val runId = "run_1"
        
        // Create original message
        val originalSync = createSyncWithEvent(
            roomId, originalEventId, "Original message", 1000L
        )
        syncIngestor.ingestSyncComplete(originalSync, 3001, runId)
        
        // Create edit event that relates to original
        val editSync = createSyncWithEditEvent(
            roomId, editEventId, originalEventId, "Edited message", 2000L
        )
        syncIngestor.ingestSyncComplete(editSync, 3002, runId)
        
        // Then - both events should be persisted
        val events = eventDao.getEventsForRoomDesc(roomId, 10)
        assertEquals(2, events.size)
        
        // Verify edit event has relatesToEventId
        val editEvent = events.find { it.eventId == editEventId }
        assertNotNull("Edit event should exist", editEvent)
        assertEquals(originalEventId, editEvent?.relatesToEventId)
    }
    
    @Test
    fun testRedactionHandling() = runBlocking {
        // Given - original message and redaction
        val roomId = "!room1:example.com"
        val originalEventId = "original_event_1"
        val redactionEventId = "redaction_event_1"
        val runId = "run_1"
        
        // Create original message
        val originalSync = createSyncWithEvent(
            roomId, originalEventId, "Message to redact", 1000L
        )
        syncIngestor.ingestSyncComplete(originalSync, 4001, runId)
        
        // Create redaction event
        val redactionSync = createSyncWithRedactionEvent(
            roomId, redactionEventId, originalEventId, 2000L
        )
        syncIngestor.ingestSyncComplete(redactionSync, 4002, runId)
        
        // Then - redaction event should be persisted with isRedaction=true
        val events = eventDao.getEventsForRoomDesc(roomId, 10)
        val redactionEvent = events.find { it.eventId == redactionEventId }
        assertNotNull("Redaction event should exist", redactionEvent)
        assertTrue("Should be marked as redaction", redactionEvent?.isRedaction == true)
        assertEquals(originalEventId, redactionEvent?.relatesToEventId)
    }
    
    // Helper functions
    
    private fun createSyncWithEvent(
        roomId: String,
        eventId: String,
        messageBody: String,
        timestamp: Long
    ): JSONObject {
        val roomObj = JSONObject()
        val meta = JSONObject()
        meta.put("name", "Test Room")
        roomObj.put("meta", meta)
        
        val timeline = JSONArray()
        val timelineEntry = JSONObject()
        val event = JSONObject()
        event.put("event_id", eventId)
        event.put("type", "m.room.message")
        event.put("sender", "@test:example.com")
        event.put("origin_server_ts", timestamp)
        event.put("timeline_rowid", timestamp)
        
        val content = JSONObject()
        content.put("body", messageBody)
        content.put("msgtype", "m.text")
        event.put("content", content)
        
        timelineEntry.put("event", event)
        timeline.put(timelineEntry)
        roomObj.put("timeline", timeline)
        
        val roomsObj = JSONObject()
        roomsObj.put(roomId, roomObj)
        
        val syncJson = DatabaseTestUtils.createTestSyncCompleteJson()
        // Rooms should be inside the "data" object
        val data = syncJson.getJSONObject("data")
        data.put("rooms", roomsObj)
        
        return syncJson
    }
    
    private fun createSyncWithEditEvent(
        roomId: String,
        editEventId: String,
        relatesToEventId: String,
        messageBody: String,
        timestamp: Long
    ): JSONObject {
        val roomObj = JSONObject()
        val meta = JSONObject()
        meta.put("name", "Test Room")
        roomObj.put("meta", meta)
        
        val timeline = JSONArray()
        val timelineEntry = JSONObject()
        val event = JSONObject()
        event.put("event_id", editEventId)
        event.put("type", "m.room.message")
        event.put("sender", "@test:example.com")
        event.put("timestamp", timestamp)
        event.put("timeline_rowid", timestamp)
        
        val content = JSONObject()
        content.put("body", messageBody)
        content.put("msgtype", "m.text")
        
        // Add edit relation
        val relatesTo = JSONObject()
        relatesTo.put("rel_type", "m.replace")
        relatesTo.put("event_id", relatesToEventId)
        content.put("m.relates_to", relatesTo)
        
        event.put("content", content)
        timelineEntry.put("event", event)
        timeline.put(timelineEntry)
        roomObj.put("timeline", timeline)
        
        val roomsObj = JSONObject()
        roomsObj.put(roomId, roomObj)
        
        val syncJson = DatabaseTestUtils.createTestSyncCompleteJson()
        // Rooms should be inside the "data" object
        val data = syncJson.getJSONObject("data")
        data.put("rooms", roomsObj)
        
        return syncJson
    }
    
    private fun createSyncWithRedactionEvent(
        roomId: String,
        redactionEventId: String,
        redactsEventId: String,
        timestamp: Long
    ): JSONObject {
        val roomObj = JSONObject()
        val meta = JSONObject()
        meta.put("name", "Test Room")
        roomObj.put("meta", meta)
        
        val timeline = JSONArray()
        val timelineEntry = JSONObject()
        val event = JSONObject()
        event.put("event_id", redactionEventId)
        event.put("type", "m.room.redaction")
        event.put("sender", "@test:example.com")
        event.put("origin_server_ts", timestamp)
        event.put("timeline_rowid", timestamp)
        
        val content = JSONObject()
        content.put("redacts", redactsEventId)
        event.put("content", content)
        
        timelineEntry.put("event", event)
        timeline.put(timelineEntry)
        roomObj.put("timeline", timeline)
        
        val roomsObj = JSONObject()
        roomsObj.put(roomId, roomObj)
        
        val syncJson = DatabaseTestUtils.createTestSyncCompleteJson()
        // Rooms should be inside the "data" object
        val data = syncJson.getJSONObject("data")
        data.put("rooms", roomsObj)
        
        return syncJson
    }
}

