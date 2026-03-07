package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AccountDataCache - Singleton cache for account data (m.direct, etc.)
 * 
 * This singleton stores account data so all AppViewModel instances can access it.
 * This ensures that when opening from external apps (like Contacts), secondary ViewModel
 * instances can access account_data that was loaded by the primary instance.
 */
object AccountDataCache {
    private const val TAG = "AccountDataCache"
    
    // Thread-safe cache for account data
    private val accountDataCache = ConcurrentHashMap<String, JSONObject>() // Key: account data type (e.g., "m.direct")
    private val cacheLock = Any()
    
    /**
     * Get account data for a specific type
     */
    fun getAccountData(type: String): JSONObject? {
        return synchronized(cacheLock) {
            accountDataCache[type]
        }
    }
    
    /**
     * Set account data for a specific type
     */
    fun setAccountData(type: String, data: JSONObject) {
        synchronized(cacheLock) {
            accountDataCache[type] = data
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "AccountDataCache: Stored account_data for type: $type")
            }
        }
    }
    
    /**
     * Get all account data
     */
    fun getAllAccountData(): Map<String, JSONObject> {
        return synchronized(cacheLock) {
            accountDataCache.toMap()
        }
    }
    
    /**
     * Set all account data (from sync_complete account_data object)
     */
    fun setAllAccountData(accountData: JSONObject) {
        synchronized(cacheLock) {
            accountDataCache.clear()
            val keys = accountData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val data = accountData.optJSONObject(key)
                if (data != null) {
                    accountDataCache[key] = data
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "AccountDataCache: Stored ${accountDataCache.size} account_data types")
            }
        }
    }
    
    /**
     * Clear all account data
     */
    fun clear() {
        synchronized(cacheLock) {
            accountDataCache.clear()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "AccountDataCache: Cleared all account_data")
            }
        }
    }
    
    /**
     * Check if account data exists for a type
     */
    fun hasAccountData(type: String): Boolean {
        return synchronized(cacheLock) {
            accountDataCache.containsKey(type)
        }
    }
}

