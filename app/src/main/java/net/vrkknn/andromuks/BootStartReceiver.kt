package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

/**
 * BootStartReceiver - Restarts WebSocketService on device boot
 * 
 * This receiver is triggered when the device boots (RECEIVE_BOOT_COMPLETED permission).
 * It uses WorkManager to restart the service instead of starting it directly,
 * which ensures the service starts at higher priority.
 */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (BuildConfig.DEBUG) Log.d("BootStartReceiver", "Device boot completed - restarting WebSocketService via WorkManager")
            
            // Use WorkManager to restart service (higher priority than BroadcastReceiver)
            ServiceStartWorker.enqueue(context, "Boot completed")
            
            WebSocketService.logActivity("Device Boot - Service Restart Scheduled", null)
        }
    }
}

