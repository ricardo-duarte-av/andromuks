package net.vrkknn.andromuks.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import net.vrkknn.andromuks.database.entities.*
import net.vrkknn.andromuks.database.dao.*

/**
 * Room database for Andromuks Matrix client
 * 
 * Provides persistent storage for:
 * - Timeline events with redaction support
 * - Room metadata and state
 * - User profiles with caching
 * - Message reactions
 * - Sync state for reconnection
 */
@Database(
    entities = [
        EventEntity::class,
        RoomEntity::class,
        SyncStateEntity::class,
        ReactionEntity::class,
        UserProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AndromuksDatabase : RoomDatabase() {
    
    abstract fun eventDao(): EventDao
    abstract fun roomDao(): RoomDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun reactionDao(): ReactionDao
    abstract fun userProfileDao(): UserProfileDao
    
    companion object {
        @Volatile
        private var INSTANCE: AndromuksDatabase? = null
        
        fun getDatabase(context: Context): AndromuksDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AndromuksDatabase::class.java,
                    "andromuks_database"
                )
                .fallbackToDestructiveMigration() // For development - remove in production
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
