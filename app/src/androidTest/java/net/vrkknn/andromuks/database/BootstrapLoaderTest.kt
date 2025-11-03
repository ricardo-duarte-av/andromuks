package net.vrkknn.andromuks.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.SyncMetaEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Tests for BootstrapLoader
 * 
 * Tests:
 * - Loading rooms from database
 * - Loading events for a specific room
 * - Handling empty database
 * - Handling missing run_id
 */
@RunWith(AndroidJUnit4::class)
class BootstrapLoaderTest {
    
    private lateinit var database: AndromuksDatabase
    private lateinit var bootstrapLoader: BootstrapLoader
    private lateinit var eventDao: EventDao
    private lateinit var roomSummaryDao: RoomSummaryDao
    private lateinit var roomStateDao: RoomStateDao
    private lateinit var syncMetaDao: SyncMetaDao
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Before
    fun setUp() {
        // Use the actual singleton database (not in-memory)
        database = AndromuksDatabase.getInstance(context)
        bootstrapLoader = BootstrapLoader(context)
        eventDao = database.eventDao()
        roomSummaryDao = database.roomSummaryDao()
        roomStateDao = database.roomStateDao()
        syncMetaDao = database.syncMetaDao()
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
            val receiptDao = database.receiptDao()
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            syncMetaDao.deleteAll()
        }
        // Note: Don't close the database as it's a singleton
    }
    
    @Test
    fun testLoadBootstrapWithEmptyDatabase() = runBlocking {
        // Given - empty database (no run_id) - already cleared in setUp
        
        // When - try to load bootstrap
        val result = bootstrapLoader.loadBootstrap()
        
        // Then - should return invalid result
        assertFalse("Should be invalid with no run_id", result.isValid)
        assertTrue("Should have empty rooms", result.rooms.isEmpty())
    }
    
    @Test
    fun testLoadBootstrapWithRooms() = runBlocking {
        // Given - database with rooms and run_id
        syncMetaDao.upsert(SyncMetaEntity("run_id", "test_run_1"))
        
        val room1 = DatabaseTestUtils.createTestRoomSummary(
            roomId = "!room1:example.com",
            lastTimestamp = 1000000L,
            unreadCount = 5
        )
        val room2 = DatabaseTestUtils.createTestRoomSummary(
            roomId = "!room2:example.com",
            lastTimestamp = 2000000L,
            unreadCount = 0
        )
        roomSummaryDao.upsertAll(listOf(room1, room2))
        
        val roomState1 = DatabaseTestUtils.createTestRoomState(
            roomId = "!room1:example.com",
            name = "Room 1",
            isDirect = false
        )
        val roomState2 = DatabaseTestUtils.createTestRoomState(
            roomId = "!room2:example.com",
            name = "Room 2",
            isDirect = true
        )
        roomStateDao.upsertAll(listOf(roomState1, roomState2))
        
        // When - load bootstrap
        val result = bootstrapLoader.loadBootstrap()
        
        // Then - should load rooms
        assertTrue("Should be valid with run_id", result.isValid)
        assertEquals(2, result.rooms.size)
        
        val loadedRoom1 = result.rooms.find { it.id == "!room1:example.com" }
        assertNotNull("Room 1 should be loaded", loadedRoom1)
        assertEquals("Room 1", loadedRoom1?.name)
        assertEquals(5, loadedRoom1?.unreadCount)
        
        val loadedRoom2 = result.rooms.find { it.id == "!room2:example.com" }
        assertNotNull("Room 2 should be loaded", loadedRoom2)
        assertEquals("Room 2", loadedRoom2?.name)
        assertTrue("Room 2 should be direct", loadedRoom2?.isDirectMessage == true)
    }
    
    @Test
    fun testLoadRoomEvents() = runBlocking {
        // Given - room with events
        val roomId = "!room1:example.com"
        val events = listOf(
            DatabaseTestUtils.createTestEvent(
                eventId = "event_1",
                roomId = roomId,
                timelineRowId = 1000,
                timestamp = 1000000L
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_2",
                roomId = roomId,
                timelineRowId = 2000,
                timestamp = 2000000L
            ),
            DatabaseTestUtils.createTestEvent(
                eventId = "event_3",
                roomId = roomId,
                timelineRowId = 3000,
                timestamp = 3000000L
            )
        )
        eventDao.upsertAll(events)
        
        // When - load events for room
        val loadedEvents = bootstrapLoader.loadRoomEvents(roomId, limit = 10)
        
        // Then - should load events in descending order
        assertEquals(3, loadedEvents.size)
        assertEquals("event_3", loadedEvents[0].eventId)
        assertEquals("event_2", loadedEvents[1].eventId)
        assertEquals("event_1", loadedEvents[2].eventId)
    }
    
    @Test
    fun testLoadRoomEventsWithLimit() = runBlocking {
        // Given - room with many events
        val roomId = "!room1:example.com"
        val events = (1..50).map { i ->
            DatabaseTestUtils.createTestEvent(
                eventId = "event_$i",
                roomId = roomId,
                timelineRowId = i * 1000L,
                timestamp = i * 1000000L
            )
        }
        eventDao.upsertAll(events)
        
        // When - load with limit
        val loadedEvents = bootstrapLoader.loadRoomEvents(roomId, limit = 10)
        
        // Then - should only load 10 events (most recent)
        assertEquals(10, loadedEvents.size)
        assertEquals("event_50", loadedEvents[0].eventId)
        assertEquals("event_41", loadedEvents[9].eventId)
    }
}

