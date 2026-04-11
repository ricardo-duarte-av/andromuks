package net.vrkknn.andromuks.utils

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object VideoCache {
    private const val MAX_BYTES = 1024L * 1024 * 1024 // 1 GB

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "exo_video_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
