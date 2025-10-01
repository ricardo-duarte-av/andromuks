package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.TimelineEvent

/**
 * Utility functions for handling redaction events and creating deletion messages.
 */
object RedactionUtils {
    
    /**
     * Finds the latest redaction event for a given event ID.
     * 
     * This function searches through all timeline events to find redaction events
     * that target the specified event ID, and returns the most recent one based on timestamp.
     * This is important because a message can be redacted multiple times, and we want to
     * show the latest redaction information.
     * 
     * @param targetEventId The event ID that was redacted
     * @param timelineEvents List of all timeline events to search through
     * @return The latest redaction event, or null if no redaction is found
     */
    fun findLatestRedactionEvent(
        targetEventId: String,
        timelineEvents: List<TimelineEvent>
    ): TimelineEvent? {
        return timelineEvents
            .filter { event ->
                event.type == "m.room.redaction" && 
                event.content?.optJSONObject("redacts")?.optString("event_id") == targetEventId
            }
            .maxByOrNull { it.timestamp }
    }
    
    /**
     * Creates a deletion message for a redacted event using the latest redaction information.
     * 
     * This function finds the latest redaction event for the given event and creates
     * a formatted deletion message showing who deleted it, when, and why (if provided).
     * 
     * @param redactedEvent The redacted timeline event
     * @param timelineEvents List of all timeline events to find the redaction event
     * @param userProfileCache Map of user IDs to MemberProfile objects for display names
     * @return Formatted deletion message string
     */
    fun createDeletionMessageForEvent(
        redactedEvent: TimelineEvent,
        timelineEvents: List<TimelineEvent>,
        userProfileCache: Map<String, MemberProfile>
    ): String {
        // Find the latest redaction event instead of just using redactedBy
        val redactionEvent = findLatestRedactionEvent(redactedEvent.eventId, timelineEvents)
        val redactionReason = redactionEvent?.content?.optString("reason", "")?.takeIf { it.isNotBlank() }
        val redactionSender = redactionEvent?.sender
        
        val senderDisplayName = redactionSender?.let { userId ->
            userProfileCache[userId]?.displayName ?: userId
        } ?: "Unknown user"
        
        val timestamp = redactionEvent?.timestamp ?: System.currentTimeMillis()
        val timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        
        return if (!redactionReason.isNullOrBlank()) {
            "Removed by $senderDisplayName for $redactionReason at $timeString"
        } else {
            "Removed by $senderDisplayName at $timeString"
        }
    }
    
    /**
     * Creates a deletion message using provided redaction information.
     * 
     * This is a simpler version that doesn't need to search for redaction events,
     * used when the redaction information is already known.
     * 
     * @param redactionSender The user ID who sent the redaction event
     * @param redactionReason The reason for deletion (can be null)
     * @param redactionTimestamp The timestamp when the deletion occurred
     * @param userProfileCache Map of user IDs to MemberProfile objects for display names
     * @return Formatted deletion message string
     */
    fun createDeletionMessage(
        redactionSender: String?, 
        redactionReason: String?, 
        redactionTimestamp: Long?,
        userProfileCache: Map<String, MemberProfile>
    ): String {
        val senderDisplayName = redactionSender?.let { userId ->
            userProfileCache[userId]?.displayName ?: userId
        } ?: "Unknown user"
        val timestamp = redactionTimestamp ?: System.currentTimeMillis()
        
        // Format timestamp to readable format
        val timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        
        return if (!redactionReason.isNullOrBlank()) {
            "Removed by $senderDisplayName for $redactionReason at $timeString"
        } else {
            "Removed by $senderDisplayName at $timeString"
        }
    }
}
