package net.vrkknn.andromuks.utils

import android.content.Context
import android.os.Build
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
 * Configured with larger memory cache to reduce reloading of avatars
 */
object ImageLoaderSingleton {
    @Volatile
    private var instance: ImageLoader? = null
    
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
                    .header("User-Agent", "Andromuks/1.0-alpha (Android; Coil)")
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
                    // Use 25% of available memory for image cache (default is 20%)
                    .maxSizePercent(0.25)
                    // Keep strong references to prevent GC from clearing cache too quickly
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    // 100 MB disk cache
                    .maxSizeBytes(100 * 1024 * 1024)
                    .build()
            }
            // Default cache policies - enable both memory and disk cache
            .respectCacheHeaders(false) // Ignore server cache headers
            .build()
    }
}

