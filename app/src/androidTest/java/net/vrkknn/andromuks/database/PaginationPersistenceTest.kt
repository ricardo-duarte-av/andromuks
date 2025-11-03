package net.vrkknn.andromuks.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.database.dao.EventDao
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Tests for pagination event persistence
 * 
 * Tests:
 * - Persisting paginated events to database
 * - Events are correctly stored and can be retrieved
 * - Paginated events integrate with sync_complete events
 */
@RunWith(AndroidJUnit4::class)
class PaginationPersistenceTest {
    
    private lateinit var database: AndromuksDatabase
    private lateinit var syncIngestor: SyncIngestor
    private lateinit var eventDao: EventDao
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Before
    fun setUp() {
        // Use the actual singleton database
        database = AndromuksDatabase.getInstance(context)
        syncIngestor = SyncIngestor(context)
        eventDao = database.eventDao()
        val roomStateDao = database.roomStateDao()
        val roomSummaryDao = database.roomSummaryDao()
        val receiptDao = database.receiptDao()
        val syncMetaDao = database.syncMetaDao()
        
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
            val syncMetaDao = database.syncMetaDao()
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            syncMetaDao.deleteAll()
        }
        // Note: Don't close the database as it's a singleton
    }
    
    @Test
    fun testPersistPaginatedEvents() = runBlocking {
        // Given - paginated events from TimelineEvent objects
        val roomId = "!room1:example.com"
        val timelineEvents = (1..50).map { i ->
            val eventJson = JSONObject()
            eventJson.put("event_id", "paginated_event_$i")
            eventJson.put("type", "m.room.message")
            eventJson.put("sender", "@test:example.com")
            eventJson.put("timestamp", System.currentTimeMillis() - (50 - i) * 1000L)
            eventJson.put("timeline_rowid", System.currentTimeMillis() - (50 - i) * 1000L)
            eventJson.put("room_id", roomId)
            
            val content = JSONObject()
            content.put("body", "Paginated message $i")
            content.put("msgtype", "m.text")
            eventJson.put("content", content)
            
            TimelineEvent.fromJson(eventJson)
        }
        
        // When - persist paginated events
        syncIngestor.persistPaginatedEvents(roomId, timelineEvents)
        
        // Then - events should be in database
        val retrieved = eventDao.getEventsForRoomDesc(roomId, 100)
        assertEquals(50, retrieved.size)
        
        // Verify events are in correct order (newest first)
        assertEquals("paginated_event_50", retrieved[0].eventId)
        assertEquals("paginated_event_1", retrieved[49].eventId)
    }
    
    @Test
    fun testPaginatedEventsDeduplicateWithSyncEvents() = runBlocking {
        // Given - some events from sync_complete
        val roomId = "!room1:example.com"
        val runId = "run_1"
        val syncJson = DatabaseTestUtils.createTestSyncCompleteJson(
            runId = runId,
            rooms = mapOf(
                roomId to DatabaseTestUtils.createTestRoomJson(
                    roomId = roomId,
                    eventCount = 10
                )
            )
        )
        syncIngestor.ingestSyncComplete(syncJson, 5001, runId)
        
        val initialCount = eventDao.getEventsForRoomDesc(roomId, 100).size
        
        // And - paginated events that overlap with sync events
        val timelineEvents = (5..15).map { i ->
            val eventJson = JSONObject()
            eventJson.put("event_id", "event_${roomId}_$i") // Overlaps with sync events
            eventJson.put("type", "m.room.message")
            eventJson.put("sender", "@test:example.com")
            eventJson.put("timestamp", System.currentTimeMillis() - (15 - i) * 1000L)
            eventJson.put("timeline_rowid", System.currentTimeMillis() - (15 - i) * 1000L)
            eventJson.put("room_id", roomId)
            
            val content = JSONObject()
            content.put("body", "Paginated message $i")
            content.put("msgtype", "m.text")
            eventJson.put("content", content)
            
            TimelineEvent.fromJson(eventJson)
        }
        
        // When - persist paginated events
        syncIngestor.persistPaginatedEvents(roomId, timelineEvents)
        
        // Then - should not have duplicates (upsert handles this)
        val finalCount = eventDao.getEventsForRoomDesc(roomId, 100).size
        // Should have at most 16 unique events (0-15), not 20 (10 from sync + 11 from paginate)
        assertTrue("Should not create duplicates", finalCount <= 16)
    }
}

