package net.vrkknn.andromuks

import android.util.Log

/**
 * Singleton tracker for active chat bubbles.
 * 
 * Tracks which room IDs currently have open bubbles so we can avoid
 * dismissing notifications that would close active bubbles.
 * 
 * Note: Android doesn't provide a system API to check if a bubble UI
 * is currently displayed, so we track this manually via lifecycle callbacks.
 */
object BubbleTracker {
    private const val TAG = "BubbleTracker"
    
    // Thread-safe set of room IDs that currently have open bubbles
    private val openBubbles = mutableSetOf<String>()
    
    // Thread-safe set of room IDs that currently have visible/maximized bubbles
    // A bubble can be open but minimized (not visible)
    private val visibleBubbles = mutableSetOf<String>()
    
    /**
     * Called when a bubble is opened for a room.
     * Should be called from ChatBubbleActivity.onCreate() or when the bubble screen becomes visible.
     */
    fun onBubbleOpened(roomId: String) {
        synchronized(openBubbles) {
            openBubbles.add(roomId)
            Log.d(TAG, "Bubble opened for room: $roomId (total open: ${openBubbles.size})")
        }
    }
    
    /**
     * Called when a bubble is closed for a room.
     * Should be called from ChatBubbleActivity.onDestroy() or when the bubble screen is destroyed.
     */
    fun onBubbleClosed(roomId: String) {
        synchronized(openBubbles) {
            openBubbles.remove(roomId)
            visibleBubbles.remove(roomId) // Also remove from visible when closed
            Log.d(TAG, "Bubble closed for room: $roomId (total open: ${openBubbles.size})")
        }
    }
    
    /**
     * Called when a bubble becomes visible/maximized.
     * Should be called from ChatBubbleActivity.onResume().
     */
    fun onBubbleVisible(roomId: String) {
        synchronized(openBubbles) {
            if (roomId in openBubbles) {
                visibleBubbles.add(roomId)
                Log.d(TAG, "Bubble became visible for room: $roomId (total visible: ${visibleBubbles.size})")
            } else {
                Log.w(TAG, "Bubble visibility set for room $roomId but bubble is not tracked as open")
            }
        }
    }
    
    /**
     * Called when a bubble becomes invisible/minimized.
     * Should be called from ChatBubbleActivity.onPause().
     */
    fun onBubbleInvisible(roomId: String) {
        synchronized(openBubbles) {
            visibleBubbles.remove(roomId)
            Log.d(TAG, "Bubble became invisible for room: $roomId (total visible: ${visibleBubbles.size})")
        }
    }
    
    /**
     * Check if a bubble is currently open for the given room.
     * 
     * @param roomId The room ID to check
     * @return true if a bubble is open for this room, false otherwise
     */
    fun isBubbleOpen(roomId: String): Boolean {
        synchronized(openBubbles) {
            val isOpen = roomId in openBubbles
            Log.d(TAG, "Checking bubble state for room: $roomId -> $isOpen")
            return isOpen
        }
    }
    
    /**
     * Check if a bubble is currently visible/maximized for the given room.
     * A bubble can be open but minimized (not visible).
     * 
     * @param roomId The room ID to check
     * @return true if a bubble is visible for this room, false otherwise
     */
    fun isBubbleVisible(roomId: String): Boolean {
        synchronized(openBubbles) {
            val isVisible = roomId in visibleBubbles
            Log.d(TAG, "Checking bubble visibility for room: $roomId -> $isVisible")
            return isVisible
        }
    }
    
    /**
     * Get all currently open bubble room IDs (for debugging).
     */
    fun getOpenBubbles(): Set<String> {
        synchronized(openBubbles) {
            return openBubbles.toSet()
        }
    }
    
    /**
     * Get all currently visible bubble room IDs (for debugging).
     */
    fun getVisibleBubbles(): Set<String> {
        synchronized(openBubbles) {
            return visibleBubbles.toSet()
        }
    }
    
    /**
     * Clear all tracked bubbles (useful for testing or app reset).
     */
    fun clear() {
        synchronized(openBubbles) {
            val count = openBubbles.size
            val visibleCount = visibleBubbles.size
            openBubbles.clear()
            visibleBubbles.clear()
            Log.d(TAG, "Cleared all bubble tracking (was tracking $count bubbles, $visibleCount visible)")
        }
    }
}

