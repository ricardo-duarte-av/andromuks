package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * DatabaseMaintenanceWorker - Periodic WorkManager job for database maintenance
 * 
 * Runs daily at night (2 AM) to:
 * - Delete events older than 1 year
 * - Clean up orphaned data
 * - Vacuum database
 */
class DatabaseMaintenanceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val TAG = "DatabaseMaintenanceWorker"
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Database maintenance worker started")
        
        try {
            val maintenance = DatabaseMaintenance(applicationContext)
            val result = maintenance.performMaintenance()
            
            if (result.success) {
                Log.d(TAG, "Database maintenance completed successfully: ${result.deletedEvents} events deleted")
                Result.success()
            } else {
                Log.e(TAG, "Database maintenance failed: ${result.error}")
                // Retry on failure (WorkManager will handle backoff)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in database maintenance worker: ${e.message}", e)
            // Retry on exception
            Result.retry()
        }
    }
    
    companion object {
        private const val WORK_NAME = "database_maintenance"
        
        /**
         * Schedule daily database maintenance at 2 AM
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            // Create periodic work request: runs every 24 hours with initial delay to 2 AM
            val currentTime = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 2)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            
            // If it's already past 2 AM today, schedule for tomorrow
            if (calendar.timeInMillis < currentTime) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            
            val initialDelay = calendar.timeInMillis - currentTime
            
            val maintenanceWork = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .addTag("database_maintenance")
                .build()
            
            // Enqueue unique work (replaces any existing work with same name)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                maintenanceWork
            )
            
            Log.d("DatabaseMaintenanceWorker", "Scheduled daily database maintenance starting at 2 AM (${initialDelay / 1000 / 60 / 60} hours from now)")
        }
        
        /**
         * Cancel scheduled maintenance
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d("DatabaseMaintenanceWorker", "Cancelled database maintenance")
        }
    }
}

