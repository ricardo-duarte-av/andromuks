package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized media cache with better memory management.
 * 
 * This system provides proper lifecycle management for ExoPlayer instances
 * and media components to prevent memory leaks and improve performance.
 */
object OptimizedMediaCache {
    private const val TAG = "OptimizedMediaCache"
    private const val MAX_ACTIVE_PLAYERS = 3
    
    // Track active players to prevent memory leaks
    private val activePlayers = ConcurrentHashMap<String, ExoPlayer>()
    private val playerUsageCount = ConcurrentHashMap<String, Int>()
    
    /**
     * Register an ExoPlayer instance for tracking and cleanup.
     * 
     * @param playerId Unique identifier for the player
     * @param player ExoPlayer instance to track
     */
    fun registerPlayer(playerId: String, player: ExoPlayer) {
        activePlayers[playerId] = player
        playerUsageCount[playerId] = 0
        if (BuildConfig.DEBUG) Log.d(TAG, "Registered player: $playerId (total: ${activePlayers.size})")
        
        // Clean up if we have too many players
        if (activePlayers.size > MAX_ACTIVE_PLAYERS) {
            cleanupLeastUsedPlayers()
        }
    }
    
    /**
     * Unregister an ExoPlayer instance and release resources.
     * 
     * @param playerId Unique identifier for the player
     */
    fun unregisterPlayer(playerId: String) {
        activePlayers[playerId]?.let { player ->
            player.release()
            activePlayers.remove(playerId)
            playerUsageCount.remove(playerId)
            if (BuildConfig.DEBUG) Log.d(TAG, "Unregistered player: $playerId (total: ${activePlayers.size})")
        }
    }
    
    /**
     * Update usage count for a player to track which ones are least used.
     * 
     * @param playerId Unique identifier for the player
     */
    fun updatePlayerUsage(playerId: String) {
        playerUsageCount[playerId] = (playerUsageCount[playerId] ?: 0) + 1
    }
    
    /**
     * Pause all active players to save resources.
     */
    fun pauseAllPlayers() {
        activePlayers.values.forEach { player ->
            if (player.isPlaying) {
                player.pause()
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Paused all ${activePlayers.size} active players")
    }
    
    /**
     * Stop all active players and release resources.
     */
    fun stopAllPlayers() {
        activePlayers.values.forEach { player ->
            player.stop()
            player.release()
        }
        activePlayers.clear()
        playerUsageCount.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "Stopped and released all players")
    }
    
    /**
     * Clean up least used players to prevent memory leaks.
     */
    private fun cleanupLeastUsedPlayers() {
        if (activePlayers.size <= MAX_ACTIVE_PLAYERS) return
        
        val playersToRemove = playerUsageCount
            .toList()
            .sortedBy { it.second } // Sort by usage count
            .take(activePlayers.size - MAX_ACTIVE_PLAYERS)
            .map { it.first }
        
        playersToRemove.forEach { playerId ->
            unregisterPlayer(playerId)
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleaned up ${playersToRemove.size} least used players")
    }
    
    /**
     * Get statistics for debugging and monitoring.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "active_players" to activePlayers.size,
            "max_players" to MAX_ACTIVE_PLAYERS,
            "player_ids" to activePlayers.keys.toList()
        )
    }
    
    /**
     * Check if a player is currently active.
     */
    fun isPlayerActive(playerId: String): Boolean {
        return activePlayers.containsKey(playerId)
    }
    
    /**
     * Get the number of active players.
     */
    fun getActivePlayerCount(): Int {
        return activePlayers.size
    }
    
    /**
     * Clean up all resources (call when app is destroyed).
     */
    fun cleanup() {
        stopAllPlayers()
        if (BuildConfig.DEBUG) Log.d(TAG, "Media cache cleanup completed")
    }
}
