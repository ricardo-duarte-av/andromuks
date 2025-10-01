package net.vrkknn.andromuks.database

import androidx.room.TypeConverter
import org.json.JSONObject

/**
 * Type converters for Room database
 * 
 * Handles conversion between JSON objects and strings for database storage
 */
class Converters {
    
    @TypeConverter
    fun fromJsonObject(jsonObject: JSONObject?): String? {
        return jsonObject?.toString()
    }
    
    @TypeConverter
    fun toJsonObject(jsonString: String?): JSONObject? {
        return jsonString?.let { 
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        }
    }
}
