package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Batches sync_complete messages to reduce battery drain when app is backgrounded.
 * 
 * **Background Mode**: Batches messages and processes every 10 seconds
 * **Foreground Mode**: Processes immediately + flushes any pending batch
 * 
 * This reduces CPU wake-ups from 480/min (8 Hz) to 6/min (every 10s) = 98.75% reduction
 */
class SyncBatchProcessor(
    private val scope: CoroutineScope,
    private val processSyncImmediately: suspend (JSONObject, Int, String) -> Unit,
    private val onBatchComplete: (suspend () -> Unit)? = null // Callback after batch completes (for batched UI updates)
) {
    private val batchQueue = mutableListOf<SyncMessage>()
    private val batchLock = Mutex()
    private var batchJob: Job? = null
    private var isAppVisible = true
    
    // CRASH FIX: Track batch processing state to prevent animations during flush
    private var _isProcessingBatch = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isProcessingBatch = _isProcessingBatch.asStateFlow()
    
    // Configuration
    private val BATCH_INTERVAL_MS = 3_600_000L // 1 hour (3600 seconds)
    private val MAX_BATCH_SIZE = 10_000 // Maximum messages to buffer in memory
    
    private data class SyncMessage(
        val syncJson: JSONObject,
        val requestId: Int,
        val runId: String
    )
    
    companion object {
        private const val TAG = "SyncBatchProcessor"
    }
    
    
    /**
     * Process a sync_complete message.
     * - If app is visible: process immediately + flush any pending batch
     * - If app is backgrounded: add to batch queue
     */
    suspend fun processSyncComplete(syncJson: JSONObject, requestId: Int, runId: String) {
        batchLock.withLock {
            if (isAppVisible) {
                // FOREGROUND: Process immediately
                processSyncImmediately(syncJson, requestId, runId)
                
                // RUSH: Also flush any pending batch
                if (batchQueue.isNotEmpty()) {
                    Log.i(TAG, "App foregrounded - flushing ${batchQueue.size} batched messages")
                    flushBatchLocked()
                }
            } else {
                // BACKGROUND: Add to batch
                batchQueue.add(SyncMessage(syncJson, requestId, runId))
                
                // Start batch timer if not running
                if (batchJob == null || !batchJob!!.isActive) {
                    scheduleBatchProcessing()
                }
                
                // Safety: Force flush if batch gets too large
                if (batchQueue.size >= MAX_BATCH_SIZE) {
                    Log.w(TAG, "Batch size limit reached (${batchQueue.size}) - forcing flush")
                    flushBatchLocked()
                }
            }
        }
    }
    
    /**
     * Schedule batch processing after BATCH_INTERVAL_MS delay
     */
    private fun scheduleBatchProcessing() {
        batchJob = scope.launch {
            delay(BATCH_INTERVAL_MS)
            batchLock.withLock {
                if (batchQueue.isNotEmpty() && !isAppVisible) {
                    flushBatchLocked()
                }
            }
        }
    }
    
    /**
     * Flush all batched messages (must be called with batchLock held)
     */
    private suspend fun flushBatchLocked() {
        if (batchQueue.isEmpty()) return
        
        val startTime = System.currentTimeMillis()
        val batchSize = batchQueue.size
        
        Log.i(TAG, "Processing batch of $batchSize sync_complete messages")
        
        // BATTERY OPTIMIZATION: Only update UI state when foregrounded
        // When backgrounded, skip StateFlow updates (UI won't observe them anyway)
        if (isAppVisible) {
            // CRASH FIX: Mark batch processing as started (on main thread for Compose)
            withContext(Dispatchers.Main) {
                _isProcessingBatch.value = true
                Log.i(TAG, "Batch processing STARTED - isProcessingBatch set to true (foreground)")
            }
        } else {
            Log.i(TAG, "Batch processing STARTED (background - skipping UI state updates)")
        }
        
        try {
            // Process all batched messages
            val batch = batchQueue.toList()
            batchQueue.clear()
            
            // Process in order (FIFO)
            batch.forEach { msg ->
                processSyncImmediately(msg.syncJson, msg.requestId, msg.runId)
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Batch processed: $batchSize messages in ${elapsed}ms (${elapsed/batchSize}ms/msg)")
            
            // BATTERY OPTIMIZATION: Don't trigger UI updates when backgrounded
            // When app comes to foreground, refreshUIState() will refresh UI from current state
            // This saves CPU/battery by avoiding unnecessary state updates when UI won't recompose
            // Note: onBatchComplete is only used for foreground batches now (if needed in future)
            
            // CRASH FIX: Ensure minimum visible duration (200ms) so UI can observe the state change
            // This is especially important for fast batches (<100ms) where UI might miss the state change
            // Only delay when foregrounded (no point delaying when backgrounded)
            if (isAppVisible) {
                val minVisibleDuration = 200L
                val remainingTime = minVisibleDuration - elapsed
                if (remainingTime > 0) {
                    delay(remainingTime)
                }
            }
        } finally {
            // BATTERY OPTIMIZATION: Only update UI state when foregrounded
            if (isAppVisible) {
                // CRASH FIX: Mark batch processing as complete (on main thread for Compose)
                withContext(Dispatchers.Main) {
                    _isProcessingBatch.value = false
                    Log.i(TAG, "Batch processing COMPLETED - isProcessingBatch set to false (foreground)")
                }
            } else {
                Log.i(TAG, "Batch processing COMPLETED (background - skipped UI state updates)")
            }
        }
        
        // Reschedule if more messages arrived during processing
        if (batchQueue.isNotEmpty() && !isAppVisible) {
            scheduleBatchProcessing()
        }
    }
    
    /**
     * Called when app visibility changes
     * @return Job that completes when batch flush finishes (if a flush was triggered), null otherwise
     */
    fun onAppVisibilityChanged(visible: Boolean): Job? {
        return scope.launch {
            val shouldFlushBatch = batchLock.withLock {
                val wasVisible = isAppVisible
                isAppVisible = visible
                
                if (visible && !wasVisible && batchQueue.isNotEmpty()) {
                    // RUSH TO PROCESS: App just came to foreground
                    // CRASH FIX: Small delay to allow UI animations to settle before processing batch
                    // This prevents rapid updates from interrupting tab animations
                    val batchSize = batchQueue.size
                    Log.i(TAG, "App foregrounded - will process ${batchSize} batched messages after brief delay")
                    true // Signal to flush after delay
                } else {
                    false
                }
            }
            
            if (shouldFlushBatch) {
                // Small delay (500ms) to allow any in-progress animations to complete
                delay(500)
                batchLock.withLock {
                    // Re-check queue size in case more messages arrived
                    if (batchQueue.isNotEmpty()) {
                        Log.i(TAG, "App foregrounded - processing ${batchQueue.size} batched messages")
                        flushBatchLocked()
                    }
                }
            }
        }
    }
    
    /**
     * Get current batch queue size (for debugging/metrics)
     */
    fun getBatchQueueSize(): Int {
        return batchQueue.size
    }
}
