package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for RedactionUtils to verify that the latest redaction is found correctly.
 */
class RedactionUtilsTest {
    
    @Test
    fun testFindLatestRedactionEvent() {
        // Create test timeline events
        val targetEventId = "original_message_123"
        val timelineEvents = listOf(
            // Original message
            createTimelineEvent("original_message_123", "m.room.message", 1000L),
            
            // First redaction
            createRedactionEvent("redaction_1", targetEventId, "First reason", 2000L),
            
            // Second redaction (later timestamp)
            createRedactionEvent("redaction_2", targetEventId, "Second reason", 3000L),
            
            // Third redaction (latest)
            createRedactionEvent("redaction_3", targetEventId, "Latest reason", 4000L),
            
            // Redaction for different event (should be ignored)
            createRedactionEvent("redaction_other", "other_message_456", "Other reason", 3500L)
        )
        
        // Test finding the latest redaction
        val latestRedaction = RedactionUtils.findLatestRedactionEvent(targetEventId, timelineEvents)
        
        assertNotNull("Latest redaction should be found", latestRedaction)
        assertEquals("Should find the latest redaction", "redaction_3", latestRedaction.eventId)
        assertEquals("Should have the latest reason", "Latest reason", 
            latestRedaction.content?.optString("reason"))
        assertEquals("Should have the latest timestamp", 4000L, latestRedaction.timestamp)
    }
    
    @Test
    fun testCreateDeletionMessageForEvent() {
        val targetEventId = "original_message_123"
        val timelineEvents = listOf(
            createTimelineEvent(targetEventId, "m.room.message", 1000L),
            createRedactionEvent("redaction_1", targetEventId, "First reason", 2000L),
            createRedactionEvent("redaction_2", targetEventId, "Latest reason", 3000L)
        )
        
        val redactedEvent = createTimelineEvent(targetEventId, "m.room.message", 1000L, redactedBy = "redaction_1")
        val userProfileCache = mapOf("user1" to MemberProfile("User One", null))
        
        val deletionMessage = RedactionUtils.createDeletionMessageForEvent(
            redactedEvent, timelineEvents, userProfileCache
        )
        
        // Should use the latest redaction (redaction_2) not the first one (redaction_1)
        assertTrue("Should contain latest reason", deletionMessage.contains("Latest reason"))
        assertFalse("Should not contain first reason", deletionMessage.contains("First reason"))
    }
    
    @Test
    fun testNoRedactionFound() {
        val timelineEvents = listOf(
            createTimelineEvent("message_123", "m.room.message", 1000L)
        )
        
        val latestRedaction = RedactionUtils.findLatestRedactionEvent("message_123", timelineEvents)
        
        assertNull("Should return null when no redaction is found", latestRedaction)
    }
    
    private fun createTimelineEvent(
        eventId: String, 
        type: String, 
        timestamp: Long, 
        redactedBy: String? = null
    ): TimelineEvent {
        val content = JSONObject().apply {
            put("body", "Test message")
        }
        
        return TimelineEvent(
            rowid = 1L,
            timelineRowid = 1L,
            roomId = "test_room",
            eventId = eventId,
            sender = "user1",
            type = type,
            timestamp = timestamp,
            content = content,
            redactedBy = redactedBy
        )
    }
    
    private fun createRedactionEvent(
        eventId: String,
        redactsEventId: String,
        reason: String,
        timestamp: Long
    ): TimelineEvent {
        val content = JSONObject().apply {
            put("reason", reason)
            put("redacts", JSONObject().apply {
                put("event_id", redactsEventId)
            })
        }
        
        return TimelineEvent(
            rowid = 1L,
            timelineRowid = 1L,
            roomId = "test_room",
            eventId = eventId,
            sender = "user1",
            type = "m.room.redaction",
            timestamp = timestamp,
            content = content
        )
    }
}
