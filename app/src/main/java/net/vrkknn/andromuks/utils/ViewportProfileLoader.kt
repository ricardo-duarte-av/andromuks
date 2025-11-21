package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.TimelineEvent



/**
 * Viewport-based profile loading system for optimized performance.
 * 
 * This system only loads user profiles for events that are actually visible
 * in the viewport, dramatically reducing memory usage and improving performance
 * for large rooms with many users.
 */
object ViewportProfileLoader {
    private const val TAG = "ViewportProfileLoader"
    
    // Track loaded profiles to avoid duplicate requests
    private val loadedProfiles = mutableSetOf<String>()
    private val loadingProfiles = mutableSetOf<String>()
    
    /**
     * Load profiles only for users in visible events.
     * 
     * This replaces the previous "opportunistic loading" that processed 50+ users
     * upfront. Now we only load 5-10 profiles for actually visible content.
     * 
     * @param visibleEventIds Set of event IDs currently visible in viewport
     * @param timelineEvents List of all timeline events
     * @param appViewModel AppViewModel instance for profile requests
     * @param roomId Current room ID
     */
    fun loadProfilesForViewport(
        visibleEventIds: Set<String>,
        timelineEvents: List<TimelineEvent>,
        appViewModel: AppViewModel,
        roomId: String
    ) {
        if (visibleEventIds.isEmpty() || timelineEvents.isEmpty()) {
            return
        }
        
        // PERFORMANCE: Only load profiles for actually visible events
        val visibleUsers = visibleEventIds
            .mapNotNull { eventId -> 
                timelineEvents.find { it.eventId == eventId }?.sender 
            }
            .distinct()
            .filter { it != appViewModel.currentUserId }
            .filter { !loadedProfiles.contains(it) }
            .filter { !loadingProfiles.contains(it) }
            .take(5) // Only 5 profiles at a time to avoid overwhelming
        
        if (visibleUsers.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "No new profiles to load for viewport")
            return
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Loading ${visibleUsers.size} profiles for viewport (was 50+ with opportunistic loading)")
        
        // Load profiles in background
        visibleUsers.forEach { userId ->
            loadingProfiles.add(userId)
            appViewModel.requestUserProfileOnDemand(userId, roomId)
        }
    }
    
    /**
     * Mark a profile as loaded to avoid duplicate requests.
     */
    fun markProfileLoaded(userId: String) {
        loadedProfiles.add(userId)
        loadingProfiles.remove(userId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Profile loaded: $userId (total loaded: ${loadedProfiles.size})")
    }
    
    /**
     * Check if a profile is already loaded or being loaded.
     */
    fun isProfileLoadedOrLoading(userId: String): Boolean {
        return loadedProfiles.contains(userId) || loadingProfiles.contains(userId)
    }
    
    /**
     * Get statistics for debugging and monitoring.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "loaded_profiles" to loadedProfiles.size,
            "loading_profiles" to loadingProfiles.size,
            "total_profiles" to (loadedProfiles.size + loadingProfiles.size)
        )
    }
    
    /**
     * Clear loaded profiles (useful when switching rooms).
     */
    fun clearLoadedProfiles() {
        loadedProfiles.clear()
        loadingProfiles.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all loaded profiles")
    }
    
    /**
     * Clean up old profiles to prevent memory leaks.
     * Keeps only the most recently used profiles.
     */
    fun cleanupOldProfiles(maxProfiles: Int = 100) {
        if (loadedProfiles.size > maxProfiles) {
            val profilesToRemove = loadedProfiles.take(loadedProfiles.size - maxProfiles)
            loadedProfiles.removeAll(profilesToRemove)
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleaned up ${profilesToRemove.size} old profiles")
        }
    }
}
