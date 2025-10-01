package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity for sync state management
 * 
 * Tracks the current sync state for reconnection and delta sync
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val id: String = "current", // Single row for current sync state
    
    // Server sync identifiers
    val runId: String? = null,
    val lastReceivedId: Long = 0,
    
    // Client state
    val currentUserId: String? = null,
    val deviceId: String? = null,
    val homeserverUrl: String? = null,
    val imageAuthToken: String? = null,
    
    // Sync timestamps
    val lastSyncTimestamp: Long = 0,
    val lastInitCompleteTimestamp: Long = 0,
    
    // Connection state
    val isConnected: Boolean = false,
    val lastConnectionTimestamp: Long = 0,
    
    // Local metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
