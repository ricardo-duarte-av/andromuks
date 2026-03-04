package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Batches sync_complete messages to reduce battery drain when app is backgrounded.
 * 
 * **Background Mode**: Batches messages and processes periodically (configurable)
 * **Foreground Mode**: Processes immediately + flushes any pending batch
 * 
 * Periodic background purge is governed by two user-configurable knobs:
 *   • [batchIntervalMs]  – elapsed time since last purge (default 5 min)
 *   • [maxBatchSize]     – max buffered messages before forced purge (default 500)
 */
class SyncBatchProcessor(
    private val scope: CoroutineScope,
    private val processSyncImmediately: suspend (JSONObject, Int, String) -> Unit,
    private val onBatchComplete: (suspend () -> Unit)? = null
) {
    private val batchQueue = mutableListOf<SyncMessage>()
    private val batchLock = Mutex()
    private var batchJob: Job? = null
    private var isAppVisible = true
    
    // CRASH FIX: Track batch processing state to prevent animations during flush
    private var _isProcessingBatch = MutableStateFlow(false)
    val isProcessingBatch = _isProcessingBatch.asStateFlow()
    
    // ── Configurable knobs (updated from AppViewModel.loadSettings) ──────────
    @Volatile var batchIntervalMs: Long = DEFAULT_BATCH_INTERVAL_MS
    @Volatile var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
    
    private data class SyncMessage(
        val syncJson: JSONObject,
        val requestId: Int,
        val runId: String
    )
    
    companion object {
        private const val TAG = "SyncBatchProcessor"

        // Defaults – also used as fallbacks in SharedPreferences
        const val DEFAULT_BATCH_INTERVAL_MS = 5L * 60_000L   // 5 minutes
        const val DEFAULT_MAX_BATCH_SIZE = 500
        
        // CRASH FIX: Timeout guard for batch processing.
        // This is a safety valve against deadlocks/stalls, NOT a performance budget.
        // When batches contain hundreds/thousands of messages, processing can legitimately take > 5s.
        private const val BATCH_PROCESSING_TIMEOUT_MIN_MS = 10_000L
        private const val BATCH_PROCESSING_TIMEOUT_MAX_MS = 10L * 60_000L // 10 minutes cap
        private const val BATCH_PROCESSING_TIMEOUT_PER_MESSAGE_MS = 2_000L // heuristic upper bound
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
                if (batchQueue.size >= maxBatchSize) {
                    Log.w(TAG, "Batch size limit reached (${batchQueue.size}/$maxBatchSize) - forcing flush")
                    flushBatchLocked()
                }
            }
        }
    }
    
    /**
     * Schedule batch processing after [batchIntervalMs] delay.
     */
    private fun scheduleBatchProcessing() {
        batchJob = scope.launch {
            delay(batchIntervalMs)
            batchLock.withLock {
                if (batchQueue.isNotEmpty() && !isAppVisible) {
                    Log.i(TAG, "Periodic background purge triggered (interval=${batchIntervalMs}ms)")
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
        if (isAppVisible) {
            withContext(Dispatchers.Main) {
                _isProcessingBatch.value = true
                Log.i(TAG, "Batch processing STARTED - isProcessingBatch set to true (foreground)")
            }
        } else {
            Log.i(TAG, "Batch processing STARTED (background - skipping UI state updates)")
        }
        
        try {
            val batch = batchQueue.toList()
            batchQueue.clear()
            
            // CRASH FIX: Add timeout to prevent getting stuck in RUSH state
            // If processing hangs or takes too long, timeout and reset state
            var processedCount = 0
            val computedTimeoutMs = run {
                val estimate = batchSize.toLong() * BATCH_PROCESSING_TIMEOUT_PER_MESSAGE_MS
                estimate.coerceIn(BATCH_PROCESSING_TIMEOUT_MIN_MS, BATCH_PROCESSING_TIMEOUT_MAX_MS)
            }

            val timeoutOccurred = withTimeoutOrNull(computedTimeoutMs) {
                batch.forEach { msg ->
                    try {
                        processSyncImmediately(msg.syncJson, msg.requestId, msg.runId)
                        processedCount++
                    } catch (e: Exception) {
                        // CRASH FIX: Handle exceptions per message to prevent entire batch from failing
                        Log.e(TAG, "Error processing sync message (requestId=${msg.requestId}): ${e.message}", e)
                        // Continue processing remaining messages even if one fails
                        processedCount++ // Count failed messages too
                    }
                }
            } == null
            
            val elapsed = System.currentTimeMillis() - startTime
            
            if (timeoutOccurred) {
                // Timeout occurred - batch processing took too long
                Log.w(TAG, "Batch processing TIMEOUT after ${elapsed}ms - processed $processedCount/$batchSize messages before timeout")
                Log.w(TAG, "This may indicate a performance issue or deadlock in processSyncImmediately")
            } else {
                Log.i(TAG, "Batch processed: $processedCount/$batchSize messages in ${elapsed}ms (${if (processedCount > 0) elapsed / processedCount else 0}ms/msg)")
            }
            
            // CRASH FIX: Ensure minimum visible duration so UI can observe state change
            if (isAppVisible) {
                val minVisibleDuration = 200L
                val remainingTime = minVisibleDuration - elapsed
                if (remainingTime > 0) {
                    delay(remainingTime)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // This shouldn't happen since we use withTimeoutOrNull, but handle it just in case
            Log.e(TAG, "Batch processing timeout exception: ${e.message}", e)
        } catch (e: Exception) {
            // CRASH FIX: Catch any unexpected exceptions to ensure state is reset
            Log.e(TAG, "Unexpected error during batch processing: ${e.message}", e)
        } finally {
            // CRASH FIX: Always reset processing state, even if processing failed or timed out
            if (isAppVisible) {
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
     * Called when app visibility changes.
     *
     * @return a Pair of (batchSize, Job?) — batchSize is the number of messages
     *         that will be flushed (0 if nothing to flush), and the Job completes
     *         when the flush finishes.  The caller can use batchSize to show a
     *         Toast *before* the flush blocks the main thread.
     */
    fun onAppVisibilityChanged(visible: Boolean): Pair<Int, Job?> {
        // Peek at queue size BEFORE launching, so caller can show a toast synchronously.
        val pendingCount: Int
        val job: Job?

        // We need to read batchQueue.size while setting isAppVisible.  Because
        // batchLock is a Mutex (suspend-only), we use a quick synchronised block
        // on the list itself for the non-suspend read, then launch the real flush.
        synchronized(batchQueue) {
            val wasVisible = isAppVisible
            isAppVisible = visible
            pendingCount = if (visible && !wasVisible) batchQueue.size else 0
        }

        if (pendingCount > 0) {
            job = scope.launch {
                try {
                    // Small delay to allow UI animations to settle
                    delay(500)
                    batchLock.withLock {
                        if (batchQueue.isNotEmpty()) {
                            Log.i(TAG, "App foregrounded - processing ${batchQueue.size} batched messages")
                            flushBatchLocked()
                        }
                    }
                } catch (e: Exception) {
                    // CRASH FIX: If job is cancelled or fails, ensure state is reset
                    Log.e(TAG, "Error in batch flush job: ${e.message}", e)
                    if (isAppVisible) {
                        withContext(Dispatchers.Main) {
                            _isProcessingBatch.value = false
                            Log.w(TAG, "Batch processing state RESET due to job cancellation/failure")
                        }
                    }
                }
            }
        } else {
            // Still need to update isAppVisible under the mutex for correctness
            job = scope.launch {
                batchLock.withLock {
                    isAppVisible = visible
                }
            }
        }

        return Pair(pendingCount, job)
    }
    
    /**
     * Force flush batched messages for notification navigation.
     * Sets isAppVisible = true because the app IS coming to the foreground.
     *
     * @return Job that completes when batch flush finishes
     */
    fun forceFlushBatch(): Job? {
        return scope.launch {
            batchLock.withLock {
                if (batchQueue.isEmpty()) {
                    isAppVisible = true
                    return@launch
                }
                
                val batchSize = batchQueue.size
                Log.i(TAG, "Force flushing $batchSize batched messages (notification navigation)")
                
                isAppVisible = true
            }
            
            batchLock.withLock {
                flushBatchLocked()
            }
        }
    }

    /**
     * Background flush triggered by an FCM notification while the app is backgrounded.
     * Processes all buffered messages so the app state is up-to-date *before* the user
     * taps the notification.  Unlike [forceFlushBatch], this keeps isAppVisible = false
     * so subsequent sync_complete messages continue to be batched.
     *
     * @return Job that completes when the flush finishes, or null if the queue is empty.
     */
    fun backgroundFlush(): Job? {
        // Quick non-blocking size check
        val size = synchronized(batchQueue) { batchQueue.size }
        if (size == 0) return null

        Log.i(TAG, "Background flush requested by FCM notification ($size buffered messages)")

        return scope.launch {
            batchLock.withLock {
                if (batchQueue.isNotEmpty()) {
                    flushBatchLocked()
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
