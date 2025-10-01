package net.vrkknn.andromuks.database

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.database.migration.MigrationHelpers

/**
 * Database initialization helper
 * 
 * Handles database setup, migration, and initialization
 * for the Andromuks Matrix client.
 */
class DatabaseInitializer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    private val database = AndromuksDatabase.getDatabase(context)
    private val repository = MatrixRepository(
        database.eventDao(),
        database.roomDao(),
        database.syncStateDao(),
        database.reactionDao(),
        database.userProfileDao()
    )
    private val migrationHelpers = MigrationHelpers(context, repository, scope)
    
    /**
     * Initialize database and perform any necessary migrations
     */
    suspend fun initialize() {
        try {
            android.util.Log.d("DatabaseInitializer", "Initializing database")
            
            // Check if migration is needed
            val needsMigration = migrationHelpers.isMigrationNeeded()
            
            if (needsMigration) {
                android.util.Log.d("DatabaseInitializer", "Migration needed, performing migration")
                // Migration will be handled by the AppViewModel when it has data to migrate
            } else {
                android.util.Log.d("DatabaseInitializer", "No migration needed, database ready")
            }
            
            android.util.Log.d("DatabaseInitializer", "Database initialization complete")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseInitializer", "Failed to initialize database", e)
        }
    }
    
    /**
     * Get the repository instance
     */
    fun getRepository(): MatrixRepository = repository
    
    /**
     * Get the migration helpers instance
     */
    fun getMigrationHelpers(): MigrationHelpers = migrationHelpers
    
    /**
     * Perform migration from existing data
     */
    suspend fun performMigration(
        rooms: List<net.vrkknn.andromuks.RoomItem>,
        events: Map<String, List<net.vrkknn.andromuks.TimelineEvent>>,
        runId: String?,
        lastReceivedId: Long,
        userId: String?,
        deviceId: String?,
        homeserverUrl: String?,
        imageAuthToken: String?
    ) {
        try {
            android.util.Log.d("DatabaseInitializer", "Starting migration from existing data")
            
            scope.launch(Dispatchers.IO) {
                migrationHelpers.performCompleteMigration(
                    rooms = rooms,
                    events = events,
                    runId = runId,
                    lastReceivedId = lastReceivedId,
                    userId = userId,
                    deviceId = deviceId,
                    homeserverUrl = homeserverUrl,
                    imageAuthToken = imageAuthToken
                )
            }
            
            android.util.Log.d("DatabaseInitializer", "Migration completed")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseInitializer", "Failed to perform migration", e)
        }
    }
    
    /**
     * Clean up old data after successful migration
     */
    suspend fun cleanupOldData() {
        try {
            scope.launch(Dispatchers.IO) {
                migrationHelpers.cleanupOldData()
                android.util.Log.d("DatabaseInitializer", "Old data cleanup completed")
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseInitializer", "Failed to cleanup old data", e)
        }
    }
}
