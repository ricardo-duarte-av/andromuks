package net.vrkknn.andromuks.utils

import android.util.Log
import coil.ImageLoader
import coil.memory.MemoryCache
import okhttp3.internal.http2.StreamResetException
import java.io.IOException

/**
 * Cache invalidation utilities for media content.
 * 
 * This provides smart cache invalidation that only clears cache when content is 
 * permanently invalid or corrupt, not for transient network/server errors.
 */
object CacheUtils {
    
    /**
     * Determines if cache should be invalidated based on the error type.
     * 
     * Cache is invalidated for:
     * - HTTP 404 (Not Found) - media deleted or invalid URL
     * - HTTP 410 (Gone) - media permanently removed
     * - Decode/corruption errors - cache file is damaged
     * 
     * Cache is NOT invalidated for:
     * - Network errors (timeout, no connection)
     * - Authentication errors (401, 403) - token might be refreshing
     * - Temporary server errors (500, 502, 503, 504)
     * 
     * @param throwable The error that occurred during image loading
     * @return true if cache should be cleared, false otherwise
     */
    fun shouldInvalidateCache(throwable: Throwable): Boolean {
        val errorMessage = throwable.message?.lowercase() ?: ""
        
        return when {
            // HTTP 404 - media not found (deleted or invalid)
            errorMessage.contains("404") || errorMessage.contains("not found") -> {
                Log.d("Andromuks", "CacheUtils: Should invalidate - 404 Not Found")
                true
            }
            
            // HTTP 410 - media permanently removed
            errorMessage.contains("410") || errorMessage.contains("gone") -> {
                Log.d("Andromuks", "CacheUtils: Should invalidate - 410 Gone")
                true
            }
            
            // Decode/corruption errors - cache file is damaged
            throwable is IOException && (
                errorMessage.contains("decode") ||
                errorMessage.contains("corrupt") ||
                errorMessage.contains("invalid") ||
                errorMessage.contains("malformed")
            ) -> {
                Log.d("Andromuks", "CacheUtils: Should invalidate - corrupt/decode error")
                true
            }
            
            // Stream reset - usually network issue, don't invalidate
            throwable is StreamResetException -> {
                Log.d("Andromuks", "CacheUtils: Not invalidating - stream reset (transient)")
                false
            }
            
            // Network errors - transient, don't invalidate
            errorMessage.contains("timeout") ||
            errorMessage.contains("unable to resolve host") ||
            errorMessage.contains("failed to connect") -> {
                Log.d("Andromuks", "CacheUtils: Not invalidating - network error (transient)")
                false
            }
            
            // Auth errors - might be refreshing token, don't invalidate
            errorMessage.contains("401") || errorMessage.contains("403") -> {
                Log.d("Andromuks", "CacheUtils: Not invalidating - auth error (transient)")
                false
            }
            
            // Server errors - transient, don't invalidate
            errorMessage.contains("500") ||
            errorMessage.contains("502") ||
            errorMessage.contains("503") ||
            errorMessage.contains("504") -> {
                Log.d("Andromuks", "CacheUtils: Not invalidating - server error (transient)")
                false
            }
            
            // For unknown errors, be conservative and don't invalidate
            else -> {
                Log.d("Andromuks", "CacheUtils: Not invalidating - unknown error (conservative)")
                false
            }
        }
    }
    
    /**
     * Invalidates (removes) an image from both memory and disk cache.
     * 
     * @param imageLoader The Coil ImageLoader instance
     * @param imageUrl The URL of the image to remove from cache
     */
    fun invalidateImageCache(imageLoader: ImageLoader, imageUrl: String) {
        try {
            // Remove from memory cache
            val memoryKey = MemoryCache.Key(imageUrl)
            imageLoader.memoryCache?.remove(memoryKey)
            
            // Remove from disk cache
            imageLoader.diskCache?.remove(imageUrl)
            
            Log.w("Andromuks", "CacheUtils: Cache invalidated for URL: $imageUrl")
        } catch (e: Exception) {
            Log.e("Andromuks", "CacheUtils: Error invalidating cache for URL: $imageUrl", e)
        }
    }
    
    /**
     * Handles image load errors with smart cache invalidation.
     * Logs the error and invalidates cache if appropriate.
     * 
     * @param imageUrl The URL of the image that failed to load (can be null)
     * @param throwable The error that occurred
     * @param imageLoader The Coil ImageLoader instance
     * @param context Additional context for logging (e.g., "Avatar", "Media", "Sticker")
     */
    fun handleImageLoadError(
        imageUrl: String?,
        throwable: Throwable,
        imageLoader: ImageLoader,
        context: String = "Image"
    ) {
        Log.e("Andromuks", "❌ $context load failed: ${imageUrl ?: "null"}")
        Log.e("Andromuks", "Error: ${throwable.message}", throwable)
        
        if (imageUrl != null && shouldInvalidateCache(throwable)) {
            invalidateImageCache(imageLoader, imageUrl)
        }
    }
}

