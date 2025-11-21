package net.vrkknn.andromuks.database

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao


import java.util.concurrent.TimeUnit

/**
 * DatabaseMaintenance - Handles periodic cleanup and maintenance of Room database
 * 
 * Features:
 * - TTL cleanup: Deletes events older than 1 year
 * - Cleanup orphaned data (receipts for deleted events, room summaries for deleted rooms)
 * - Compaction: Vacuum database to reclaim space
 */
class DatabaseMaintenance(private val context: Context) {
    private val database = AndromuksDatabase.getInstance(context)
    private val eventDao = database.eventDao()
    private val receiptDao = database.receiptDao()
    private val roomStateDao = database.roomStateDao()
    private val roomSummaryDao = database.roomSummaryDao()
    private val spaceDao = database.spaceDao()
    private val spaceRoomDao = database.spaceRoomDao()
    
    private val TAG = "DatabaseMaintenance"
    
    companion object {
        // TTL: Delete events older than 1 year
        private const val TTL_ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000
    }
    
    /**
     * Perform full database maintenance:
     * 1. Delete old events (older than 1 year)
     * 2. Clean up orphaned receipts
     * 3. Clean up orphaned room summaries
     * 4. Vacuum database (SQLite compaction)
     */
    suspend fun performMaintenance(): MaintenanceResult = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Starting database maintenance...")
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Delete old events (older than 1 year)
            val cutoffTime = System.currentTimeMillis() - TTL_ONE_YEAR_MS
            val deletedEvents = eventDao.deleteEventsOlderThan(cutoffTime)
            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted $deletedEvents events older than 1 year")
            
            // 2. Clean up orphaned receipts (receipts for events that no longer exist)
            val deletedOrphanedReceipts = receiptDao.deleteOrphanedReceipts()
            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted $deletedOrphanedReceipts orphaned receipts")
            
            // 3. Clean up orphaned room summaries (for rooms that no longer exist)
            val deletedOrphanedSummaries = roomSummaryDao.deleteOrphanedSummaries()
            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted $deletedOrphanedSummaries orphaned room summaries")
            
            // 4. Clean up orphaned space-room relationships
            val deletedOrphanedSpaceRooms = spaceRoomDao.deleteOrphanedSpaceRooms()
            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted $deletedOrphanedSpaceRooms orphaned space-room relationships")
            
            // 5. Vacuum database to reclaim space
            database.vacuum()
            if (BuildConfig.DEBUG) Log.d(TAG, "Database vacuum completed")
            
            val duration = System.currentTimeMillis() - startTime
            val result = MaintenanceResult(
                deletedEvents = deletedEvents,
                deletedOrphanedReceipts = deletedOrphanedReceipts,
                deletedOrphanedSummaries = deletedOrphanedSummaries,
                deletedOrphanedSpaceRooms = deletedOrphanedSpaceRooms,
                durationMs = duration,
                success = true
            )
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Database maintenance completed in ${duration}ms: ${result.deletedEvents} events, ${result.deletedOrphanedReceipts} receipts, ${result.deletedOrphanedSummaries} summaries deleted")
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error during database maintenance: ${e.message}", e)
            val duration = System.currentTimeMillis() - startTime
            return@withContext MaintenanceResult(
                deletedEvents = 0,
                deletedOrphanedReceipts = 0,
                deletedOrphanedSummaries = 0,
                deletedOrphanedSpaceRooms = 0,
                durationMs = duration,
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Result of maintenance operation
     */
    data class MaintenanceResult(
        val deletedEvents: Int,
        val deletedOrphanedReceipts: Int,
        val deletedOrphanedSummaries: Int,
        val deletedOrphanedSpaceRooms: Int,
        val durationMs: Long,
        val success: Boolean,
        val error: String? = null
    )
}

