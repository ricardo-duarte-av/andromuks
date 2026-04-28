package net.vrkknn.andromuks.utils

import android.content.Context
import android.os.Build
import net.vrkknn.andromuks.utils.getUserAgent
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * Singleton ImageLoader for the entire app
 * Configured with optimized memory and disk cache for avatar performance
 */
object ImageLoaderSingleton {
    @Volatile
    private var instance: ImageLoader? = null
    
    // QUALITY IMPROVEMENT: Optimized cache settings for better quality
    // PERFORMANCE: Increased memory cache to keep more images loaded (supports 20 items above/below viewport)
    private const val MEMORY_CACHE_PERCENT = 0.35 // Increased to 35% to keep more avatars in memory
    private const val DISK_CACHE_SIZE_MB = 512L // Persistent storage — keep reasonable
    private const val MAX_DISK_CACHE_ENTRIES = 2000
    
    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: createImageLoader(context).also { instance = it }
        }
    }
    
    /**
     * Clear Coil's memory cache.
     * 
     * This should be called when memory pressure is detected (e.g., in onTrimMemory)
     * to prevent cache corruption when Android's LMK kills cached bitmaps.
     */
    fun clearMemoryCache(context: Context) {
        synchronized(this) {
            instance?.memoryCache?.clear()
        }
    }
    
    private fun createImageLoader(context: Context): ImageLoader {
        // PERFORMANCE: Cap concurrent image loads to avoid runaway parallelism; queue the rest.
        // All MXC URLs map to the same host (backend/_gomuks/media/...), so maxRequestsPerHost
        // would otherwise cap effective concurrency at the per-host limit only.
        // Relaxed to 100 so avatars + inline images + full media can proceed in parallel
        // without artificial queuing when everything hits one host.
        val dispatcher = Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 100 // Same as maxRequests: single backend host for all media
        }
        
        // Create custom OkHttpClient with Andromuks User-Agent and dispatcher concurrency above
        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", getUserAgent())
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        
        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    // PERFORMANCE: Increased memory cache to keep more images loaded
                    // Supports keeping ~20 items above/below viewport in memory
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    // Keep strong references for frequently accessed avatars
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.filesDir.resolve("image_cache"))
                    // AVATAR LOADING OPTIMIZATION: Larger disk cache with size limits
                    .maxSizeBytes(DISK_CACHE_SIZE_MB * 1024 * 1024)
                    .build()
            }
            // Default cache policies - enable both memory and disk cache
            .respectCacheHeaders(false) // Ignore server cache headers
            .build()
    }
}

