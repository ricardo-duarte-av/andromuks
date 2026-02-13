package net.vrkknn.andromuks

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val processSyncImmediately: suspend (JSONObject, Int, String) -> Unit
) {
    private val batchQueue = mutableListOf<SyncMessage>()
    private val batchLock = Mutex()
    private var batchJob: Job? = null
    private var isAppVisible = true
    
    // Configuration
    private val BATCH_INTERVAL_MS = 60_000L // 60 seconds (safe with FCM notifications)
    private val MAX_BATCH_SIZE = 480 // 8 Hz × 60s = 480 messages max
    
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
        
        // Process all batched messages
        val batch = batchQueue.toList()
        batchQueue.clear()
        
        // Process in order (FIFO)
        batch.forEach { msg ->
            processSyncImmediately(msg.syncJson, msg.requestId, msg.runId)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Batch processed: $batchSize messages in ${elapsed}ms (${elapsed/batchSize}ms/msg)")
        
        // Reschedule if more messages arrived during processing
        if (batchQueue.isNotEmpty() && !isAppVisible) {
            scheduleBatchProcessing()
        }
    }
    
    /**
     * Called when app visibility changes
     */
    fun onAppVisibilityChanged(visible: Boolean) {
        scope.launch {
            batchLock.withLock {
                val wasVisible = isAppVisible
                isAppVisible = visible
                
                if (visible && !wasVisible) {
                    // RUSH TO PROCESS: App just came to foreground
                    if (batchQueue.isNotEmpty()) {
                        Log.i(TAG, "App foregrounded - rushing to process ${batchQueue.size} batched messages")
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
