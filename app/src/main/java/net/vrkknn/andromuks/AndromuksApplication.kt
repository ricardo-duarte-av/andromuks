package net.vrkknn.andromuks

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.CircleAvatarCache
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.RoomTimelineCache

/**
 * Application class for Andromuks.
 * 
 * Handles memory pressure events from Android's Low Memory Killer (LMK)
 * to prevent cache corruption when the system trims memory.
 */
class AndromuksApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "AndromuksApplication: onCreate()")
        }
    }
    
    /**
     * Handle memory pressure events from Android's Low Memory Killer (LMK).
     * 
     * When Android trims memory, it may kill cached bitmaps/images while keeping
     * the foreground service alive. This can cause corruption where:
     * - Images fail to render
     * - Opening rooms stalls
     * - Cache entries reference dead bitmaps
     * 
     * This method proactively clears caches when memory pressure is detected,
     * preventing corruption by ensuring caches are cleared before bitmaps are killed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        if (BuildConfig.DEBUG) {
            val levelName = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
                else -> "UNKNOWN($level)"
            }
            Log.d("Andromuks", "AndromuksApplication: onTrimMemory($levelName)")
        }
        
        // Clear caches on memory pressure to prevent corruption
        // We clear on UI_HIDDEN and above to be proactive
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                clearImageCaches()
            }
        }
    }
    
    /**
     * Clear all image-related caches to prevent corruption.
     * 
     * This is called when memory pressure is detected to ensure caches
     * are cleared before Android's LMK kills the underlying bitmaps.
     */
    private fun clearImageCaches() {
        try {
            if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "AndromuksApplication: Clearing image caches due to memory pressure")
            }
            
            // 1. Clear Coil's memory cache (contains Bitmap objects)
            ImageLoaderSingleton.clearMemoryCache(this)
            
            // 2. Clear in-memory cache entry maps (these reference File objects,
            // but we clear them to avoid stale references after LMK kills bitmaps)
            IntelligentMediaCache.clearInMemoryCache()
            CircleAvatarCache.clearInMemoryCache()
            // 2b. Clear resolved mxc → path map so we don't AsyncImage dead paths after eviction
            AvatarUtils.clearResolvedUrlCache()
            
            // 3. Trim timeline event cache for non-opened rooms (keep newest 100 events per room)
            // This is lighter-weight than clearing entire rooms - keeps recent history
            // while reducing memory usage. Opened rooms are preserved (unbounded).
            // LRU eviction still acts as a hard backup if memory pressure continues.
            RoomTimelineCache.trimAllRoomsToMaxEvents()
            
            if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "AndromuksApplication: Image caches cleared successfully")
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "AndromuksApplication: Error clearing image caches", e)
        }
    }
}

