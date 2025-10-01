package net.vrkknn.andromuks.database.migration

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject

/**
 * Migration helpers for transitioning from in-memory to database storage
 * 
 * Provides utilities to gradually migrate existing data and functionality
 * to the new database layer while maintaining backward compatibility.
 */
class MigrationHelpers(
    private val context: Context,
    private val repository: MatrixRepository,
    private val scope: CoroutineScope
) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    
    /**
     * Migrate existing room data from in-memory to database
     */
    suspend fun migrateRoomsToDatabase(rooms: List<RoomItem>) {
        try {
            scope.launch(Dispatchers.IO) {
                repository.insertRooms(rooms)
                android.util.Log.d("MigrationHelpers", "Migrated ${rooms.size} rooms to database")
            }
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to migrate rooms to database", e)
        }
    }
    
    /**
     * Migrate existing timeline events to database
     */
    suspend fun migrateEventsToDatabase(events: List<TimelineEvent>, roomId: String) {
        try {
            scope.launch(Dispatchers.IO) {
                repository.insertEvents(events)
                android.util.Log.d("MigrationHelpers", "Migrated ${events.size} events for room $roomId to database")
            }
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to migrate events to database", e)
        }
    }
    
    /**
     * Migrate user profiles from SharedPreferences to database
     */
    suspend fun migrateUserProfilesFromSharedPreferences() {
        try {
            scope.launch(Dispatchers.IO) {
                val allKeys = sharedPreferences.all.keys.filter { it.startsWith("profile_") }
                var migratedCount = 0
                
                for (key in allKeys) {
                    val userId = key.removePrefix("profile_")
                    val profileJsonString = sharedPreferences.getString(key, null)
                    
                    if (profileJsonString != null) {
                        try {
                            val profileJson = JSONObject(profileJsonString)
                            val displayName = profileJson.optString("displayName").takeIf { it.isNotBlank() }
                            val avatarUrl = profileJson.optString("avatarUrl").takeIf { it.isNotBlank() }
                            
                            repository.insertUserProfile(userId, displayName, avatarUrl)
                            migratedCount++
                        } catch (e: Exception) {
                            android.util.Log.w("MigrationHelpers", "Failed to parse profile for $userId", e)
                        }
                    }
                }
                
                android.util.Log.d("MigrationHelpers", "Migrated $migratedCount user profiles from SharedPreferences to database")
            }
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to migrate user profiles from SharedPreferences", e)
        }
    }
    
    /**
     * Migrate sync state from in-memory to database
     */
    suspend fun migrateSyncStateToDatabase(
        runId: String?,
        lastReceivedId: Long,
        userId: String?,
        deviceId: String?,
        homeserverUrl: String?,
        imageAuthToken: String?
    ) {
        try {
            scope.launch(Dispatchers.IO) {
                repository.updateSyncState(runId, lastReceivedId)
                
                if (userId != null && deviceId != null && homeserverUrl != null) {
                    repository.updateClientState(userId, deviceId, homeserverUrl, imageAuthToken ?: "")
                }
                
                android.util.Log.d("MigrationHelpers", "Migrated sync state to database")
            }
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to migrate sync state to database", e)
        }
    }
    
    /**
     * Check if migration is needed
     */
    suspend fun isMigrationNeeded(): Boolean {
        return try {
            // Check if database has any data
            val roomCount = repository.getAllRooms().let { flow ->
                var count = 0
                flow.collect { rooms ->
                    count = rooms.size
                }
                count
            }
            
            // If no rooms in database but we have data in SharedPreferences, migration is needed
            val hasSharedPrefsData = sharedPreferences.all.keys.any { it.startsWith("profile_") }
            
            roomCount == 0 && hasSharedPrefsData
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Error checking migration status", e)
            false
        }
    }
    
    /**
     * Perform complete migration from old system to database
     */
    suspend fun performCompleteMigration(
        rooms: List<RoomItem>,
        events: Map<String, List<TimelineEvent>>,
        runId: String?,
        lastReceivedId: Long,
        userId: String?,
        deviceId: String?,
        homeserverUrl: String?,
        imageAuthToken: String?
    ) {
        try {
            android.util.Log.d("MigrationHelpers", "Starting complete migration to database")
            
            // Migrate rooms
            migrateRoomsToDatabase(rooms)
            
            // Migrate events by room
            for ((roomId, roomEvents) in events) {
                migrateEventsToDatabase(roomEvents, roomId)
            }
            
            // Migrate user profiles
            migrateUserProfilesFromSharedPreferences()
            
            // Migrate sync state
            migrateSyncStateToDatabase(runId, lastReceivedId, userId, deviceId, homeserverUrl, imageAuthToken)
            
            android.util.Log.d("MigrationHelpers", "Complete migration to database finished")
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to perform complete migration", e)
        }
    }
    
    /**
     * Clean up old SharedPreferences data after successful migration
     */
    suspend fun cleanupOldData() {
        try {
            scope.launch(Dispatchers.IO) {
                val editor = sharedPreferences.edit()
                
                // Remove old profile cache
                val allKeys = sharedPreferences.all.keys.filter { it.startsWith("profile_") }
                for (key in allKeys) {
                    editor.remove(key)
                }
                
                editor.apply()
                android.util.Log.d("MigrationHelpers", "Cleaned up old SharedPreferences data")
            }
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to cleanup old data", e)
        }
    }
    
    /**
     * Dual-write helper: Write to both old system and database
     */
    suspend fun dualWriteEvent(event: TimelineEvent) {
        try {
            // Write to database
            repository.insertEvent(event)
            
            // Also update in-memory state (for backward compatibility)
            // This will be handled by the existing AppViewModel code
            
            android.util.Log.d("MigrationHelpers", "Dual-wrote event ${event.eventId}")
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to dual-write event", e)
        }
    }
    
    /**
     * Dual-write helper: Write to both old system and database
     */
    suspend fun dualWriteRoom(room: RoomItem) {
        try {
            // Write to database
            repository.insertRoom(room)
            
            // Also update in-memory state (for backward compatibility)
            // This will be handled by the existing AppViewModel code
            
            android.util.Log.d("MigrationHelpers", "Dual-wrote room ${room.id}")
        } catch (e: Exception) {
            android.util.Log.e("MigrationHelpers", "Failed to dual-write room", e)
        }
    }
}
