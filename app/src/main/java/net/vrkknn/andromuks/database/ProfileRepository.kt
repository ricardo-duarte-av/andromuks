package net.vrkknn.andromuks.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.MemberProfile

/**
 * Simple SQLite-based repository for managing user profile data.
 * This replaces SharedPreferences for better memory management and performance.
 */
class ProfileRepository(private val context: Context) {
    
    private val dbHelper = ProfileDatabaseHelper(context)
    
    /**
     * Saves a single profile to the database.
     */
    suspend fun saveProfile(userId: String, profile: MemberProfile) = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.writableDatabase
            val values = android.content.ContentValues().apply {
                put("user_id", userId)
                put("display_name", profile.displayName ?: "")
                put("avatar_url", profile.avatarUrl ?: "")
                put("last_updated", System.currentTimeMillis())
            }
            
            // Use INSERT OR REPLACE to handle conflicts
            db.insertWithOnConflict("user_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            android.util.Log.d("Andromuks", "ProfileRepository: Saved profile for $userId")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to save profile for $userId", e)
        }
    }
    
    /**
     * Saves multiple profiles in a single transaction.
     * This is much more efficient than individual saves.
     */
    suspend fun saveProfiles(profiles: Map<String, MemberProfile>) = withContext(Dispatchers.IO) {
        if (profiles.isEmpty()) return@withContext
        
        try {
            val startTime = System.currentTimeMillis()
            val db = dbHelper.writableDatabase
            
            db.beginTransaction()
            try {
                for ((userId, profile) in profiles) {
                    val values = android.content.ContentValues().apply {
                        put("user_id", userId)
                        put("display_name", profile.displayName ?: "")
                        put("avatar_url", profile.avatarUrl ?: "")
                        put("last_updated", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict("user_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "ProfileRepository: Batch saved ${profiles.size} profiles in ${duration}ms")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to batch save profiles", e)
        }
    }
    
    /**
     * Loads a profile from the database.
     */
    suspend fun loadProfile(userId: String): MemberProfile? = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "user_profiles",
                arrayOf("display_name", "avatar_url"),
                "user_id = ?",
                arrayOf(userId),
                null, null, null
            )
            
            val profile = if (cursor.moveToFirst()) {
                val displayName = cursor.getString(0).takeIf { it.isNotEmpty() }
                val avatarUrl = cursor.getString(1).takeIf { it.isNotEmpty() }
                MemberProfile(displayName, avatarUrl)
            } else null
            
            cursor.close()
            profile
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to load profile for $userId", e)
            null
        }
    }
    
    /**
     * Loads multiple profiles from the database.
     */
    suspend fun loadProfiles(userIds: List<String>): Map<String, MemberProfile> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext emptyMap()
        
        try {
            val db = dbHelper.readableDatabase
            val placeholders = userIds.joinToString(",") { "?" }
            val cursor = db.query(
                "user_profiles",
                arrayOf("user_id", "display_name", "avatar_url"),
                "user_id IN ($placeholders)",
                userIds.toTypedArray(),
                null, null, null
            )
            
            val profiles = mutableMapOf<String, MemberProfile>()
            while (cursor.moveToNext()) {
                val userId = cursor.getString(0)
                val displayName = cursor.getString(1).takeIf { it.isNotEmpty() }
                val avatarUrl = cursor.getString(2).takeIf { it.isNotEmpty() }
                profiles[userId] = MemberProfile(displayName, avatarUrl)
            }
            
            cursor.close()
            profiles
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to load profiles", e)
            emptyMap()
        }
    }
    
    /**
     * Gets the current number of stored profiles.
     */
    suspend fun getProfileCount(): Int = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM user_profiles", null)
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to get profile count", e)
            0
        }
    }
    
    /**
     * Cleans up old profiles to prevent database bloat.
     * Removes profiles older than the specified cutoff time.
     */
    suspend fun cleanupOldProfiles(cutoffTime: Long = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)) = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.writableDatabase
            val deletedCount = db.delete("user_profiles", "last_updated < ?", arrayOf(cutoffTime.toString()))
            if (deletedCount > 0) {
                android.util.Log.d("Andromuks", "ProfileRepository: Cleaned up $deletedCount old profiles")
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ProfileRepository: Failed to cleanup old profiles", e)
        }
    }
    
    /**
     * Database helper class for profile storage.
     */
    private class ProfileDatabaseHelper(context: Context) : SQLiteOpenHelper(
        context, "andromuks_profiles.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE user_profiles (
                    user_id TEXT PRIMARY KEY,
                    display_name TEXT,
                    avatar_url TEXT,
                    last_updated INTEGER
                )
            """.trimIndent())
        }
        
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // For now, just drop and recreate
            db.execSQL("DROP TABLE IF EXISTS user_profiles")
            onCreate(db)
        }
    }
}