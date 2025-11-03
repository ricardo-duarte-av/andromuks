package net.vrkknn.andromuks.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.vrkknn.andromuks.database.dao.EventDao
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Performance benchmarks for database operations
 * 
 * Tests:
 * - Bulk insert performance
 * - Query performance with large datasets
 * - TTL deletion performance
 */
@RunWith(AndroidJUnit4::class)
class DatabasePerformanceTest {
    
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
    fun testBulkInsertPerformance() = runBlocking {
        // Given - 1000 events to insert
        val events = (1..1000).map { i ->
            DatabaseTestUtils.createTestEvent(
                eventId = "event_$i",
                roomId = "!room1:example.com",
                timelineRowId = i * 1000L,
                timestamp = System.currentTimeMillis() + i * 1000L
            )
        }
        
        // When - bulk insert
        val startTime = System.currentTimeMillis()
        eventDao.upsertAll(events)
        val duration = System.currentTimeMillis() - startTime
        
        // Then - should complete reasonably quickly (< 5 seconds for 1000 events)
        assertTrue("Bulk insert should be fast (${duration}ms)", duration < 5000)
        
        // Verify all events were inserted
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 10000)
        assertEquals(1000, retrieved.size)
        
        println("Bulk insert: 1000 events in ${duration}ms (${duration / 1000.0}ms per event)")
    }
    
    @Test
    fun testQueryPerformanceWithLargeDataset() = runBlocking {
        // Given - 5000 events across 10 rooms
        val rooms = (1..10).map { "!room$it:example.com" }
        val events = mutableListOf<net.vrkknn.andromuks.database.entities.EventEntity>()
        
        for (roomId in rooms) {
            for (i in 1..500) {
                events.add(
                    DatabaseTestUtils.createTestEvent(
                        eventId = "event_${roomId}_$i",
                        roomId = roomId,
                        timelineRowId = i * 1000L,
                        timestamp = System.currentTimeMillis() + i * 1000L
                    )
                )
            }
        }
        
        val insertStart = System.currentTimeMillis()
        eventDao.upsertAll(events)
        val insertDuration = System.currentTimeMillis() - insertStart
        println("Inserted 5000 events in ${insertDuration}ms")
        
        // When - query events for one room
        val queryStart = System.currentTimeMillis()
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 100)
        val queryDuration = System.currentTimeMillis() - queryStart
        
        // Then - should be fast (< 100ms for query)
        assertTrue("Query should be fast (${queryDuration}ms)", queryDuration < 100)
        assertEquals(100, retrieved.size)
        
        println("Query: 100 events from 5000 total in ${queryDuration}ms")
    }
    
    @Test
    fun testTTLDeletionPerformance() = runBlocking {
        // Given - mix of old and new events
        val now = System.currentTimeMillis()
        val events = mutableListOf<net.vrkknn.andromuks.database.entities.EventEntity>()
        
        // 1000 old events (400+ days old)
        for (i in 1..1000) {
            events.add(
                DatabaseTestUtils.createTestEvent(
                    eventId = "old_event_$i",
                    roomId = "!room1:example.com",
                    timestamp = now - 400 * 24 * 60 * 60 * 1000L
                )
            )
        }
        
        // 1000 new events (recent)
        for (i in 1..1000) {
            events.add(
                DatabaseTestUtils.createTestEvent(
                    eventId = "new_event_$i",
                    roomId = "!room1:example.com",
                    timestamp = now - 10 * 24 * 60 * 60 * 1000L
                )
            )
        }
        
        eventDao.upsertAll(events)
        
        // When - delete old events
        val cutoffTime = now - 365 * 24 * 60 * 60 * 1000L
        val deleteStart = System.currentTimeMillis()
        val deletedCount = eventDao.deleteEventsOlderThan(cutoffTime)
        val deleteDuration = System.currentTimeMillis() - deleteStart
        
        // Then - should delete only old events and be reasonably fast
        assertEquals(1000, deletedCount)
        assertTrue("TTL deletion should be fast (${deleteDuration}ms)", deleteDuration < 2000)
        
        val remaining = eventDao.getEventsForRoomDesc("!room1:example.com", 10000)
        assertEquals(1000, remaining.size)
        assertTrue("All remaining should be new events", remaining.all { it.eventId.startsWith("new_event") })
        
        println("TTL deletion: 1000 events in ${deleteDuration}ms")
    }
    
    @Test
    fun testEventOrderingWithLargeDataset() = runBlocking {
        // Given - 1000 events inserted in random order
        val events = (1..1000).shuffled().map { i ->
            DatabaseTestUtils.createTestEvent(
                eventId = "event_$i",
                roomId = "!room1:example.com",
                timelineRowId = i * 1000L,
                timestamp = i * 1000000L
            )
        }
        
        eventDao.upsertAll(events)
        
        // When - retrieve events
        val startTime = System.currentTimeMillis()
        val retrieved = eventDao.getEventsForRoomDesc("!room1:example.com", 1000)
        val duration = System.currentTimeMillis() - startTime
        
        // Then - should be ordered correctly despite random insert order
        assertEquals(1000, retrieved.size)
        for (i in 0 until retrieved.size - 1) {
            assertTrue(
                "Events should be ordered descending",
                retrieved[i].timelineRowId >= retrieved[i + 1].timelineRowId
            )
        }
        
        println("Ordered retrieval: 1000 events in ${duration}ms")
    }
}

