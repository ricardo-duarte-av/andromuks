package net.vrkknn.andromuks.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

/**
 * Global throttling mechanism for avatar loading during fast scrolling.
 * 
 * This prevents simultaneous image decoding that causes crashes/ANRs by:
 * 1. Limiting concurrent avatar loads
 * 2. Adding progressive delays based on load order
 * 3. Tracking active loads to prevent overload
 */
object AvatarLoadThrottle {
    private const val MAX_CONCURRENT_LOADS = 5 // Limit to 5 concurrent avatar decodings
    private const val BASE_DELAY_MS = 20L // Base delay between loads (20ms)
    
    private val loadMutex = Mutex()
    private var activeLoads = 0
    private var loadCounter = 0L
    
    /**
     * Request permission to load an avatar with throttling.
     * Returns the delay to wait before loading.
     */
    suspend fun requestLoad(): Long {
        // Wait if we're at max concurrent loads
        while (true) {
            val result = loadMutex.withLock {
                if (activeLoads >= MAX_CONCURRENT_LOADS) {
                    // Need to wait - return null to indicate waiting needed
                    null
                } else {
                    // Can proceed - increment and calculate delay
                    activeLoads++
                    val currentCounter = loadCounter++
                    // Calculate progressive delay: 0ms, 20ms, 40ms, 60ms, 80ms
                    val calculatedDelay = (currentCounter % MAX_CONCURRENT_LOADS) * BASE_DELAY_MS
                    calculatedDelay
                }
            }
            
            if (result != null) {
                return result
            }
            
            // Wait before checking again (mutex is unlocked here)
            delay(BASE_DELAY_MS)
        }
    }
    
    /**
     * Release the load slot when avatar loading completes.
     */
    suspend fun releaseLoad() = loadMutex.withLock {
        activeLoads = (activeLoads - 1).coerceAtLeast(0)
    }
}

