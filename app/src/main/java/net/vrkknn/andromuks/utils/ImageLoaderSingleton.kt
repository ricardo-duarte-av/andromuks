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
    private const val DISK_CACHE_SIZE_MB = 2048L // Increased to 2GB for higher quality images
    private const val MAX_DISK_CACHE_ENTRIES = 2000 // Increased for more high-quality images
    
    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: createImageLoader(context).also { instance = it }
        }
    }
    
    private fun createImageLoader(context: Context): ImageLoader {
        // PERFORMANCE FIX: Limit concurrent image loads to prevent crashes during fast scrolling
        // This queues requests instead of loading them all simultaneously
        // CRITICAL FIX: All MXC URLs are converted to the same host (gomuks backend)
        // Example: mxc://example.com/media -> https://gomuks-backend/_gomuks/media/example.com/media
        // This means ALL media requests (avatars, inline images, thumbnails) go to the same host
        // Therefore maxRequestsPerHost is the real bottleneck, not maxRequests
        val dispatcher = Dispatcher().apply {
            maxRequests = 20 // Increased to handle more concurrent requests overall
            maxRequestsPerHost = 20 // CRITICAL: Set equal to maxRequests since all requests go to same host
            // This allows 20 concurrent requests to the gomuks backend, which handles:
            // - Avatars (many per room)
            // - Inline images/custom emojis (many per message)
            // - Media thumbnails (many per room)
            // All competing for the same host slots
        }
        
        // Create custom OkHttpClient with Andromuks User-Agent and limited concurrency
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
                    .directory(context.cacheDir.resolve("image_cache"))
                    // AVATAR LOADING OPTIMIZATION: Larger disk cache with size limits
                    .maxSizeBytes(DISK_CACHE_SIZE_MB * 1024 * 1024)
                    .build()
            }
            // Default cache policies - enable both memory and disk cache
            .respectCacheHeaders(false) // Ignore server cache headers
            .build()
    }
}

