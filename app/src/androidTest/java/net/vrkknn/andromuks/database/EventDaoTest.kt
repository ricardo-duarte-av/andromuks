package net.vrkknn.andromuks.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.entities.EventEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Tests for EventDao
 * 
 * Tests:
 * - Event insertion and retrieval
 * - Event ordering (by timelineRowId and timestamp)
 * - Event deduplication
 * - TTL deletion (deleteEventsOlderThan)
 */
@RunWith(AndroidJUnit4::class)
class EventDaoTest {
    
    private lateinit var database: AndromuksDatabase
    private lateinit var eventDao: EventDao
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Before
    fun setUp() {
        database = DatabaseTestUtils.createInMemoryDatabase(context)
        eventDao = database.eventDao()
    }
    
    @After
    fun tearDown() {
        database.close()
    }
    
    @Test
    fun testInsertAndRetrieveEvent() = runBlocking {
        // Given
        val event = DatabaseTestUtils.createTestEvent(
            eventId = "event_1",
            roomId = "!room1:example.com",
            timelineRowId = 1000,
            timestamp = 1000000L
        )
        
        // When
        eventDao.upsert(event)
        
        // Then
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(1, retrieved.size)
        assertEquals("event_1", retrieved[0].eventId)
        assertEquals(1000, retrieved[0].timelineRowId)
    }
    
    @Test
    fun testEventOrdering() = runBlocking {
        // Given - insert events in reverse order
        val events = listOf(
            DatabaseTestUtils.createTestEvent(
                eventId = "event_3",
                roomId = "!room1:example.com",
                timelineRowId = 3000,
                timestamp = 3000000L
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_1",
                roomId = "!room1:example.com",
                timelineRowId = 1000,
                timestamp = 1000000L
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_2",
                roomId = "!room1:example.com",
                timelineRowId = 2000,
                timestamp = 2000000L
            )
        )
        
        // When
        eventDao.upsertAll(events)
        
        // Then - should be ordered by timelineRowId DESC
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(3, retrieved.size)
        assertEquals("event_3", retrieved[0].eventId)
        assertEquals("event_2", retrieved[1].eventId)
        assertEquals("event_1", retrieved[2].eventId)
    }
    
    @Test
    fun testEventDeduplication() = runBlocking {
        // Given - same event ID inserted twice
        val event1 = DatabaseTestUtils.createTestEvent(
            eventId = "event_1",
            roomId = "!room1:example.com",
            timelineRowId = 1000,
            rawJson = """{"type":"m.room.message","body":"First"}"""
        )
        val event2 = DatabaseTestUtils.createTestEvent(
            eventId = "event_1", // Same ID
            roomId = "!room1:example.com",
            timelineRowId = 2000,
            rawJson = """{"type":"m.room.message","body":"Second"}"""
        )
        
        // When - insert both (upsert should replace)
        eventDao.upsert(event1)
        eventDao.upsert(event2)
        
        // Then - should only have one event (last one wins)
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(1, retrieved.size)
        assertEquals("event_1", retrieved[0].eventId)
        assertEquals(2000, retrieved[0].timelineRowId) // Last update wins
        assertTrue(retrieved[0].rawJson.contains("Second"))
    }
    
    @Test
    fun testDeleteEventsOlderThan() = runBlocking {
        // Given - events with different timestamps
        val now = System.currentTimeMillis()
        val events = listOf(
            DatabaseTestUtils.createTestEvent(
                eventId = "event_old",
                roomId = "!room1:example.com",
                timestamp = now - 400 * 24 * 60 * 60 * 1000L // 400 days old
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_recent",
                roomId = "!room1:example.com",
                timestamp = now - 100 * 24 * 60 * 60 * 1000L // 100 days old
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_new",
                roomId = "!room1:example.com",
                timestamp = now - 10 * 24 * 60 * 60 * 1000L // 10 days old
            )
        )
        eventDao.upsertAll(events)
        
        // When - delete events older than 365 days
        val cutoffTime = now - 365 * 24 * 60 * 60 * 1000L
        val deletedCount = eventDao.deleteEventsOlderThan(cutoffTime)
        
        // Then
        assertEquals(1, deletedCount) // Only event_old should be deleted
        val remaining = eventDao.getEventsForRoomDesc("!room1:example.com", 10)
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.eventId == "event_recent" })
        assertTrue(remaining.any { it.eventId == "event_new" })
        assertFalse(remaining.any { it.eventId == "event_old" })
    }
    
    @Test
    fun testGetEventsForRoomAsc() = runBlocking {
        // Given
        val events = listOf(
            DatabaseTestUtils.createTestEvent(
                eventId = "event_3",
                roomId = "!room1:example.com",
                timelineRowId = 3000
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_1",
                roomId = "!room1:example.com",
                timelineRowId = 1000
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_2",
                roomId = "!room1:example.com",
                timelineRowId = 2000
            )
        )
        eventDao.upsertAll(events)
        
        // When - get ascending
        val retrieved = eventDao.getEventsForRoomAsc("!room1:example.com", 10)
        
        // Then - should be ordered ascending
        assertEquals(3, retrieved.size)
        assertEquals("event_1", retrieved[0].eventId)
        assertEquals("event_2", retrieved[1].eventId)
        assertEquals("event_3", retrieved[2].eventId)
    }
    
    @Test
    fun testDeleteAllForRoom() = runBlocking {
        // Given - events in multiple rooms
        val room1 = "!room1:example.com"
        val room2 = "!room2:example.com"
        
        val events = listOf(
            DatabaseTestUtils.createTestEvent(
                eventId = "event_1",
                roomId = room1,
                timelineRowId = 1000
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_2",
                roomId = room1,
                timelineRowId = 2000
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_3",
                roomId = room2,
                timelineRowId = 3000
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_4",
                roomId = room2,
                timelineRowId = 4000
            )
        )
        eventDao.upsertAll(events)
        
        // Verify initial state
        assertEquals(2, eventDao.getEventsForRoomDesc(room1, 10).size)
        assertEquals(2, eventDao.getEventsForRoomDesc(room2, 10).size)
        
        // When - delete all events for room1
        eventDao.deleteAllForRoom(room1)
        
        // Then - room1 should be empty, room2 should still have events
        assertEquals(0, eventDao.getEventsForRoomDesc(room1, 10).size)
        assertEquals(2, eventDao.getEventsForRoomDesc(room2, 10).size)
        assertTrue(eventDao.getEventsForRoomDesc(room2, 10).any { it.eventId == "event_3" })
        assertTrue(eventDao.getEventsForRoomDesc(room2, 10).any { it.eventId == "event_4" })
    }
}

