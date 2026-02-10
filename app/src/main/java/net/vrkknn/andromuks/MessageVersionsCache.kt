package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * MessageVersionsCache - Singleton cache for message edit/redaction history
 * 
 * This singleton stores message version history (edits and redactions).
 * It allows any AppViewModel instance to access edit history, even when opening from notifications.
 * 
 * Structure: originalEventId -> VersionedMessage
 */
object MessageVersionsCache {
    private const val TAG = "MessageVersionsCache"
    
    // Thread-safe map storing version history: originalEventId -> VersionedMessage
    private val versionsCache = ConcurrentHashMap<String, VersionedMessage>()
    
    // Maps edit event ID back to original event ID for quick lookup
    private val editToOriginal = ConcurrentHashMap<String, String>()
    
    // Maps redacted event ID to the redaction event
    private val redactionCache = ConcurrentHashMap<String, TimelineEvent>()
    
    private val cacheLock = Any()
    
    /**
     * Update or add version history for an event
     */
    fun updateVersion(originalEventId: String, versionedMessage: VersionedMessage) {
        synchronized(cacheLock) {
            versionsCache[originalEventId] = versionedMessage
            
            // Update editToOriginal mapping for all edit versions
            versionedMessage.versions.forEach { version ->
                if (!version.isOriginal && version.eventId != originalEventId) {
                    editToOriginal[version.eventId] = originalEventId
                }
            }
            
            // Update redaction cache if redacted
            if (versionedMessage.redactedBy != null && versionedMessage.redactionEvent != null) {
                redactionCache[originalEventId] = versionedMessage.redactionEvent
            }
        }
    }
    
    /**
     * Get version history for an event
     */
    fun getVersion(originalEventId: String): VersionedMessage? {
        return synchronized(cacheLock) {
            versionsCache[originalEventId]
        }
    }
    
    /**
     * Get original event ID from an edit event ID
     */
    fun getOriginalEventId(editEventId: String): String? {
        return synchronized(cacheLock) {
            editToOriginal[editEventId]
        }
    }
    
    /**
     * Get redaction event for an event
     */
    fun getRedactionEvent(eventId: String): TimelineEvent? {
        return synchronized(cacheLock) {
            redactionCache[eventId]
        }
    }
    
    /**
     * Get all versions from the cache
     */
    fun getAllVersions(): Map<String, VersionedMessage> {
        return synchronized(cacheLock) {
            HashMap(versionsCache) // Return a copy
        }
    }
    
    /**
     * Clear all versions from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            versionsCache.clear()
            editToOriginal.clear()
            redactionCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "MessageVersionsCache: Cleared all versions")
        }
    }
    
    /**
     * Clear versions for a specific room
     * Note: This requires tracking roomId -> eventIds, which may not be available
     * For now, we'll just clear all (caller should handle room-specific clearing)
     */
    fun clearForRoom(roomId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "MessageVersionsCache: clearForRoom called for $roomId (clearing all - room tracking not implemented)")
        clear()
    }
    
    /**
     * Clear versions for specific event IDs (used when evicting a room)
     * This clears both the versions cache and related edit/redaction mappings
     */
    fun clearForEventIds(eventIds: Set<String>) {
        synchronized(cacheLock) {
            var removedVersions = 0
            var removedEdits = 0
            var removedRedactions = 0
            
            // Clear versions for the specified event IDs
            eventIds.forEach { eventId ->
                if (versionsCache.remove(eventId) != null) {
                    removedVersions++
                }
                // Also remove from redaction cache
                if (redactionCache.remove(eventId) != null) {
                    removedRedactions++
                }
            }
            
            // Remove edit-to-original mappings where the original event was cleared
            val editKeysToRemove = editToOriginal.entries.filter { it.value in eventIds }.map { it.key }
            editKeysToRemove.forEach { editEventId ->
                editToOriginal.remove(editEventId)
                removedEdits++
            }
            
            // Also remove edit-to-original mappings where the edit event itself was cleared
            eventIds.forEach { eventId ->
                if (editToOriginal.remove(eventId) != null) {
                    removedEdits++
                }
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "MessageVersionsCache: Cleared versions for ${removedVersions} events, ${removedEdits} edit mappings, ${removedRedactions} redactions (out of ${eventIds.size} requested)")
        }
    }
}

