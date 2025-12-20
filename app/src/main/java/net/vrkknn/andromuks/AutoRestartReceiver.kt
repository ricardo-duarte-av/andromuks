package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

/**
 * AutoRestartReceiver - Restarts WebSocketService when it's destroyed
 * 
 * This receiver is triggered by WebSocketService.onDestroy() to restart the service
 * via WorkManager. Using WorkManager instead of starting directly from a receiver
 * ensures the service starts at higher priority.
 */
class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTART_SERVICE) {
            val reason = intent.getStringExtra(EXTRA_REASON) ?: "Service destroyed"
            if (BuildConfig.DEBUG) Log.d("AutoRestartReceiver", "Auto-restart triggered: $reason")
            
            // Use WorkManager to restart service (higher priority than BroadcastReceiver)
            ServiceStartWorker.enqueue(context, reason)
            
            WebSocketService.logActivity("Auto Restart - Service Restart Scheduled: $reason", null)
        }
    }
    
    companion object {
        const val ACTION_RESTART_SERVICE = "net.vrkknn.andromuks.RESTART_SERVICE"
        const val EXTRA_REASON = "reason"
        
        /**
         * Send restart intent to AutoRestartReceiver
         */
        fun sendRestartIntent(context: Context, reason: String) {
            val intent = Intent(context, AutoRestartReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
                putExtra(EXTRA_REASON, reason)
            }
            context.sendBroadcast(intent)
        }
    }
}

