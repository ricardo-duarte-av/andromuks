@file:Suppress("DEPRECATION")

package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.getUserAgent
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced ExoPlayer lifecycle management with smart player pooling.
 * 
 * This system prevents memory leaks by:
 * - Limiting concurrent players to 3
 * - Reusing players when possible
 * - Properly disposing unused players
 * - Tracking player usage and states
 */
object AdvancedExoPlayerManager {
    private const val TAG = "AdvancedExoPlayerManager"
    private const val MAX_PLAYERS = 3
    private const val INACTIVE_THRESHOLD = 5 * 60 * 1000L // 5 minutes
    
    data class PlayerState(
        val isPlaying: Boolean,
        val lastUsed: Long,
        val mediaUrl: String,
        val accessCount: Int = 0
    )
    
    // Thread-safe collections for player management
    private val playerPool = ConcurrentHashMap<String, ExoPlayer>()
    private val playerUsage = ConcurrentHashMap<String, Long>()
    private val playerStates = ConcurrentHashMap<String, PlayerState>()
    private val playerMutex = Mutex()
    
    /**
     * Get or create an ExoPlayer instance with smart pooling.
     * 
     * @param playerId Unique identifier for the player
     * @param context Android context
     * @param mediaUrl Media URL for the player
     * @return ExoPlayer instance
     */
    suspend fun getOrCreatePlayer(
        playerId: String,
        context: Context,
        mediaUrl: String
    ): ExoPlayer = playerMutex.withLock {
        // Reuse existing player if available
        playerPool[playerId]?.let { existingPlayer ->
            updatePlayerUsage(playerId)
            if (BuildConfig.DEBUG) Log.d(TAG, "Reusing existing player: $playerId")
            return@withLock existingPlayer
        }
        
        // Create new player if pool not full
        if (playerPool.size < MAX_PLAYERS) {
            val newPlayer = createOptimizedPlayer(context, mediaUrl)
            playerPool[playerId] = newPlayer
            updatePlayerUsage(playerId)
            playerStates[playerId] = PlayerState(
                isPlaying = false,
                lastUsed = System.currentTimeMillis(),
                mediaUrl = mediaUrl
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "Created new player: $playerId (pool size: ${playerPool.size})")
            return@withLock newPlayer
        }
        
        // Replace least used player
        val leastUsedPlayer = playerUsage.minByOrNull { it.value }?.key
        leastUsedPlayer?.let { oldPlayerId ->
            if (BuildConfig.DEBUG) Log.d(TAG, "Replacing least used player: $oldPlayerId")
            releasePlayer(oldPlayerId)
        }
        
        val newPlayer = createOptimizedPlayer(context, mediaUrl)
        playerPool[playerId] = newPlayer
        updatePlayerUsage(playerId)
        playerStates[playerId] = PlayerState(
            isPlaying = false,
            lastUsed = System.currentTimeMillis(),
            mediaUrl = mediaUrl
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "Created replacement player: $playerId")
        return@withLock newPlayer
    }
    
    /**
     * Create an optimized ExoPlayer instance with performance settings.
     */
    private fun createOptimizedPlayer(context: Context, mediaUrl: String): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(createOptimizedMediaSourceFactory())
            .setLoadControl(createOptimizedLoadControl())
            .setRenderersFactory(createOptimizedRenderersFactory(context))
            .build()
            .apply {
                // Optimize audio attributes for media playback
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                
                // Set media item
                val mediaItem = androidx.media3.common.MediaItem.fromUri(mediaUrl)
                setMediaItem(mediaItem)
                prepare()
            }
    }
    
    /**
     * Create optimized media source factory with HTTP data source.
     */
    private fun createOptimizedMediaSourceFactory(): DefaultMediaSourceFactory {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(getUserAgent())
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
        
        return DefaultMediaSourceFactory(dataSourceFactory)
    }
    
    /**
     * Create optimized load control for better performance.
     */
    private fun createOptimizedLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    
    /**
     * Create optimized renderers factory.
     */
    private fun createOptimizedRenderersFactory(context: Context): RenderersFactory {
        return DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }
    
    /**
     * Update player usage statistics.
     */
    private fun updatePlayerUsage(playerId: String) {
        playerUsage[playerId] = System.currentTimeMillis()
        playerStates[playerId]?.let { state ->
            playerStates[playerId] = state.copy(
                lastUsed = System.currentTimeMillis(),
                accessCount = state.accessCount + 1
            )
        }
    }
    
    /**
     * Pause a specific player.
     */
    fun pausePlayer(playerId: String) {
        playerPool[playerId]?.pause()
        playerStates[playerId]?.let { state ->
            playerStates[playerId] = state.copy(isPlaying = false)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Paused player: $playerId")
    }
    
    /**
     * Stop a specific player.
     */
    fun stopPlayer(playerId: String) {
        playerPool[playerId]?.stop()
        playerStates[playerId]?.let { state ->
            playerStates[playerId] = state.copy(isPlaying = false)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Stopped player: $playerId")
    }
    
    /**
     * Release a specific player and remove from pool.
     */
    suspend fun releasePlayer(playerId: String) = playerMutex.withLock {
        playerPool[playerId]?.release()
        playerPool.remove(playerId)
        playerUsage.remove(playerId)
        playerStates.remove(playerId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Released player: $playerId (pool size: ${playerPool.size})")
    }
    
    /**
     * Clean up inactive players to prevent memory leaks.
     */
    suspend fun cleanupInactivePlayers() = playerMutex.withLock {
        val currentTime = System.currentTimeMillis()
        val inactivePlayers = playerUsage.entries.filter { (_, lastUsed) ->
            currentTime - lastUsed > INACTIVE_THRESHOLD
        }
        
        inactivePlayers.forEach { (playerId, _) ->
            releasePlayer(playerId)
        }
        
        if (inactivePlayers.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleaned up ${inactivePlayers.size} inactive players")
        }
    }
    
    /**
     * Pause all active players.
     */
    fun pauseAllPlayers() {
        playerPool.values.forEach { it.pause() }
        playerStates.values.forEach { state ->
            playerStates[state.mediaUrl] = state.copy(isPlaying = false)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Paused all ${playerPool.size} active players")
    }
    
    /**
     * Stop all active players.
     */
    fun stopAllPlayers() {
        playerPool.values.forEach { it.stop() }
        playerStates.values.forEach { state ->
            playerStates[state.mediaUrl] = state.copy(isPlaying = false)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Stopped all ${playerPool.size} active players")
    }
    
    /**
     * Release all players and clear the pool.
     */
    suspend fun releaseAllPlayers() = playerMutex.withLock {
        playerPool.values.forEach { it.release() }
        playerPool.clear()
        playerUsage.clear()
        playerStates.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "Released all players and cleared pool")
    }
    
    /**
     * Get statistics for debugging and monitoring.
     */
    fun getStats(): Map<String, Any> {
        val totalAccessCount = playerStates.values.sumOf { it.accessCount }
        val averageAccessCount = if (playerStates.isNotEmpty()) {
            totalAccessCount / playerStates.size
        } else 0
        
        return mapOf(
            "active_players" to playerPool.size,
            "max_players" to MAX_PLAYERS,
            "total_access_count" to totalAccessCount,
            "average_access_count" to averageAccessCount,
            "player_ids" to playerPool.keys.toList()
        )
    }
    
    /**
     * Check if a player is currently active.
     */
    fun isPlayerActive(playerId: String): Boolean {
        return playerPool.containsKey(playerId)
    }
    
    /**
     * Get the number of active players.
     */
    fun getActivePlayerCount(): Int {
        return playerPool.size
    }
    
    /**
     * Clean up all resources (call when app is destroyed).
     */
    suspend fun cleanup() {
        releaseAllPlayers()
        if (BuildConfig.DEBUG) Log.d(TAG, "AdvancedExoPlayerManager cleanup completed")
    }
}
