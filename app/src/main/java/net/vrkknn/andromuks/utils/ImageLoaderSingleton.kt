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

/**
 * Singleton ImageLoader for the entire app
 * Configured with optimized memory and disk cache for avatar performance
 */
object ImageLoaderSingleton {
    @Volatile
    private var instance: ImageLoader? = null
    
    // QUALITY IMPROVEMENT: Optimized cache settings for better quality
    private const val MEMORY_CACHE_PERCENT = 0.25 // Increased to 25% for better quality caching
    private const val DISK_CACHE_SIZE_MB = 2048L // Increased to 2GB for higher quality images
    private const val MAX_DISK_CACHE_ENTRIES = 2000 // Increased for more high-quality images
    
    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: createImageLoader(context).also { instance = it }
        }
    }
    
    private fun createImageLoader(context: Context): ImageLoader {
        // Create custom OkHttpClient with Andromuks User-Agent
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", getUserAgent())
                    .build()
                chain.proceed(requestWithUserAgent)
            }
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
                    // AVATAR LOADING OPTIMIZATION: Optimized memory cache size
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

