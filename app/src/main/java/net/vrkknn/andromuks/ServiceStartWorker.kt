package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig

/**
 * ServiceStartWorker - One-off WorkManager job to start WebSocketService
 * 
 * This worker is used to start the WebSocketService at higher priority than
 * starting it directly from a BroadcastReceiver. WorkManager ensures the service
 * gets started even if the app process was killed.
 * 
 * Used by:
 * - BootStartReceiver (on device boot)
 * - AutoRestartReceiver (when service is destroyed)
 * - WebSocketHealthCheckWorker (periodic service health check - combined with auto-restart)
 */
class ServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "ServiceStartWorker"
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            NOTIFICATION_ID,
            createRecoveryNotification()
        )
    }
    
    private fun createRecoveryNotification(): Notification {
        val channelId = "service_recovery_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Service Recovery"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Andromuks")
            .setContentText("Restoring WebSocket connection...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reason = inputData.getString(EXTRA_REASON) ?: "Unknown"
        if (BuildConfig.DEBUG) Log.d(TAG, "ServiceStartWorker started: $reason")
        
        try {
            // Check if we have credentials (user is logged in)
            val prefs = applicationContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
            val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
            
            if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "No credentials found - user not logged in, skipping service start")
                return@withContext Result.success()
            }
            
            // Check if service is already running
            if (WebSocketService.isServiceRunning()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "WebSocketService already running - skipping start")
                return@withContext Result.success()
            }
            
            // Start the foreground service
            val intent = Intent(applicationContext, WebSocketService::class.java)
            try {
                // Use startService() even on Android O+; WebSocketService will promote itself
                // to a foreground service when allowed. This avoids the strict timing
                // requirement of startForegroundService(), which can crash the app if
                // startForeground() is rejected or delayed.
                applicationContext.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("ServiceStartWorker", "Failed to start WebSocketService from worker", e)
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "WebSocketService start initiated: $reason")
            WebSocketService.logActivity("Service Start Worker: Service Started - $reason", null)
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocketService: ${e.message}", e)
            WebSocketService.logActivity("Service Start Worker: Failed - ${e.message}", null)
            // Retry on failure (WorkManager will handle backoff)
            Result.retry()
        }
    }
    
    companion object {
        private const val EXTRA_REASON = "reason"
        private const val WORK_TAG = "service_start"
        private const val NOTIFICATION_ID = 1002 // Distinct from WebSocketService.NOTIFICATION_ID
        
        /**
         * Enqueue a one-off work request to start the service
         */
        fun enqueue(context: Context, reason: String) {
            val workRequest = OneTimeWorkRequestBuilder<ServiceStartWorker>()
                .addTag(WORK_TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(EXTRA_REASON, reason)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            if (BuildConfig.DEBUG) Log.d("ServiceStartWorker", "Enqueued service start work: $reason")
        }
    }
}

