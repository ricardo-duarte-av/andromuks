package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import net.vrkknn.andromuks.database.entities.RoomMemberEntity

@Dao
interface RoomMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: RoomMemberEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<RoomMemberEntity>)
    
    @Query("SELECT * FROM room_members WHERE roomId = :roomId ORDER BY displayName ASC, userId ASC")
    suspend fun getMembersForRoom(roomId: String): List<RoomMemberEntity>
    
    @Query("SELECT * FROM room_members WHERE roomId = :roomId AND userId = :userId LIMIT 1")
    suspend fun getMember(roomId: String, userId: String): RoomMemberEntity?
    
    @Query("DELETE FROM room_members WHERE roomId = :roomId")
    suspend fun deleteMembersForRoom(roomId: String)
    
    @Transaction
    suspend fun replaceRoomMembers(roomId: String, members: List<RoomMemberEntity>) {
        deleteMembersForRoom(roomId)
        if (members.isNotEmpty()) {
            insertAll(members)
        }
    }
    
    @Query("SELECT COUNT(*) FROM room_members WHERE roomId = :roomId")
    suspend fun getMemberCount(roomId: String): Int
}

