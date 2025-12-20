package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * AutoRestartWorker - Periodic WorkManager job to ensure WebSocketService stays running
 * 
 * This worker runs periodically (every 30 minutes) to check if the service is running
 * and restart it if needed. This is a backup mechanism in addition to:
 * - WebSocketHealthCheckWorker (connection health)
 * - BootStartReceiver (boot restart)
 * - AutoRestartReceiver (onDestroy restart)
 * 
 * Multiple recovery triggers ensure the service stays alive even under aggressive killing.
 */
class AutoRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "AutoRestartWorker"
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.d(TAG, "AutoRestartWorker started")
        
        try {
            // Check if we have credentials (user is logged in)
            val prefs = applicationContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
            val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
            
            if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "No credentials found - user not logged in, skipping auto-restart check")
                return@withContext Result.success()
            }
            
            // Check if service is running
            if (!WebSocketService.isServiceRunning()) {
                Log.w(TAG, "WebSocketService not running - scheduling restart")
                WebSocketService.logActivity("Auto Restart Worker: Service Not Running - Restarting", null)
                ServiceStartWorker.enqueue(applicationContext, "AutoRestartWorker periodic check")
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "WebSocketService is running - no action needed")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in AutoRestartWorker: ${e.message}", e)
            // Don't retry immediately - wait for next scheduled run
            Result.success()
        }
    }
    
    companion object {
        private const val WORK_NAME = "websocket_auto_restart"
        private const val REPEAT_INTERVAL_MINUTES = 30L // 30 minutes (WorkManager minimum is 15, but we use 30 to avoid conflicts with health check)
        private const val FLEX_INTERVAL_MINUTES = 5L // Flex window for execution
        
        /**
         * Schedule periodic auto-restart checks
         * Runs every 30 minutes
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            val autoRestartWork = PeriodicWorkRequestBuilder<AutoRestartWorker>(
                repeatInterval = REPEAT_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = FLEX_INTERVAL_MINUTES,
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .addTag("websocket_auto_restart")
                .build()
            
            // Enqueue unique work (replaces any existing work with same name)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                autoRestartWork
            )
            
            if (BuildConfig.DEBUG) Log.d("AutoRestartWorker", "Scheduled periodic auto-restart checks (every $REPEAT_INTERVAL_MINUTES minutes)")
        }
        
        /**
         * Cancel scheduled auto-restart checks
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (BuildConfig.DEBUG) Log.d("AutoRestartWorker", "Cancelled auto-restart checks")
        }
    }
}

