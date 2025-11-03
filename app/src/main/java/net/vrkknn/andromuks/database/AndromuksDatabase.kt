package net.vrkknn.andromuks.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.ReceiptEntity
import net.vrkknn.andromuks.database.entities.RoomStateEntity
import net.vrkknn.andromuks.database.entities.RoomSummaryEntity
import net.vrkknn.andromuks.database.entities.SpaceEntity
import net.vrkknn.andromuks.database.entities.SpaceRoomEntity
import net.vrkknn.andromuks.database.entities.SyncMetaEntity

@Database(
    entities = [
        EventEntity::class,
        RoomStateEntity::class,
        ReceiptEntity::class,
        RoomSummaryEntity::class,
        SyncMetaEntity::class,
        SpaceEntity::class,
        SpaceRoomEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AndromuksDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun roomStateDao(): RoomStateDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun roomSummaryDao(): RoomSummaryDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun spaceDao(): SpaceDao
    abstract fun spaceRoomDao(): SpaceRoomDao

    companion object {
        @Volatile private var instance: AndromuksDatabase? = null

        fun getInstance(context: Context): AndromuksDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndromuksDatabase::class.java,
                    "andromuks.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
    
    /**
     * Vacuum the database to reclaim space after deletions
     */
    fun vacuum() {
        openHelper.writableDatabase.execSQL("VACUUM")
    }
}


