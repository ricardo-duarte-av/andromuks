package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WebSocketHealthCheckWorker - Periodic WorkManager job to ensure WebSocket service stays alive
 * 
 * This worker runs periodically (every 15-30 minutes) to:
 * - Check if WebSocketService is running
 * - Check if WebSocket connection is active
 * - Restart service if it was killed by Android
 * - Trigger reconnection if service is running but disconnected
 * 
 * This is a backup mechanism - the primary connection is maintained by the foreground service.
 * WorkManager ensures the service gets restarted even if Android kills it aggressively.
 */
class WebSocketHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "WebSocketHealthCheckWorker"
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "WebSocket health check worker started")
        
        try {
            // Check if we have credentials (user is logged in)
            val prefs = applicationContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
            val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
            
            if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                Log.d(TAG, "No credentials found - user not logged in, skipping health check")
                return@withContext Result.success()
            }
            
            // Check if service is running
            val isServiceRunning = isServiceRunning(applicationContext)
            val isWebSocketConnected = WebSocketService.isConnected()
            
            Log.d(TAG, "Health check: serviceRunning=$isServiceRunning, websocketConnected=$isWebSocketConnected")
            
            if (!isServiceRunning) {
                // Service was killed - restart it
                Log.w(TAG, "WebSocketService not running - restarting service")
                restartService(applicationContext)
                return@withContext Result.success()
            }
            
            if (!isWebSocketConnected) {
                // Service is running but WebSocket is disconnected - trigger reconnection
                Log.w(TAG, "WebSocketService running but WebSocket disconnected - triggering reconnection")
                
                // Use safe reconnection which has fallback logic if AppViewModel is not available
                WebSocketService.triggerReconnectionSafely("WorkManager health check - connection lost")
                
                return@withContext Result.success()
            }
            
            // Everything is healthy
            Log.d(TAG, "WebSocket health check passed - service running and connected")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in WebSocket health check worker: ${e.message}", e)
            // Don't retry immediately - wait for next scheduled run
            Result.success()
        }
    }
    
    /**
     * Check if WebSocketService is currently running
     */
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        return runningServices.any { service ->
            service.service.className == WebSocketService::class.java.name
        }
    }
    
    /**
     * Restart the WebSocketService
     */
    private fun restartService(context: Context) {
        try {
            val intent = Intent(context, WebSocketService::class.java)
            context.startForegroundService(intent)
            Log.d(TAG, "WebSocketService restart initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart WebSocketService", e)
        }
    }
    
    companion object {
        private const val WORK_NAME = "websocket_health_check"
        private const val REPEAT_INTERVAL_MINUTES = 15L // Minimum allowed by WorkManager
        private const val FLEX_INTERVAL_MINUTES = 5L // Flex window for execution
        
        /**
         * Schedule periodic WebSocket health checks
         * Runs every 15 minutes (minimum allowed by WorkManager)
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            val healthCheckWork = PeriodicWorkRequestBuilder<WebSocketHealthCheckWorker>(
                repeatInterval = REPEAT_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = FLEX_INTERVAL_MINUTES,
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .addTag("websocket_health_check")
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            // Enqueue unique work (replaces any existing work with same name)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                healthCheckWork
            )
            
            Log.d("WebSocketHealthCheckWorker", "Scheduled periodic WebSocket health checks (every $REPEAT_INTERVAL_MINUTES minutes)")
        }
        
        /**
         * Cancel scheduled health checks
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d("WebSocketHealthCheckWorker", "Cancelled WebSocket health checks")
        }
    }
}

