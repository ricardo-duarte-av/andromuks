package net.vrkknn.andromuks.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.RoomEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Simple test to verify database compilation and basic operations
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    
    private lateinit var database: AndromuksDatabase
    
    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndromuksDatabase::class.java
        ).build()
    }
    
    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }
    
    @Test
    fun testDatabaseCreation() {
        // This test just verifies the database can be created
        // which means all the entities and DAOs compile correctly
        assert(database.eventDao() != null)
        assert(database.roomDao() != null)
        assert(database.syncStateDao() != null)
        assert(database.reactionDao() != null)
        assert(database.userProfileDao() != null)
    }
}
