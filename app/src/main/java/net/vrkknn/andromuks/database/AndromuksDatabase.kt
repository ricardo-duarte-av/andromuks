package net.vrkknn.andromuks.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.vrkknn.andromuks.database.dao.AccountDataDao
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.InviteDao
import net.vrkknn.andromuks.database.dao.PendingRoomDao
import net.vrkknn.andromuks.database.dao.ReactionDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomMemberDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.AccountDataEntity
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.InviteEntity
import net.vrkknn.andromuks.database.entities.PendingRoomEntity
import net.vrkknn.andromuks.database.entities.ReactionEntity
import net.vrkknn.andromuks.database.entities.ReceiptEntity
import net.vrkknn.andromuks.database.entities.RoomMemberEntity
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
        SpaceRoomEntity::class,
        AccountDataEntity::class,
        InviteEntity::class,
        ReactionEntity::class,
        PendingRoomEntity::class,
        RoomMemberEntity::class
    ],
    version = 9,
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
    abstract fun accountDataDao(): AccountDataDao
    abstract fun inviteDao(): InviteDao
    abstract fun reactionDao(): ReactionDao
    abstract fun pendingRoomDao(): PendingRoomDao
    abstract fun roomMemberDao(): RoomMemberDao

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
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
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


