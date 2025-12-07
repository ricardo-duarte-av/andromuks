package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object NotificationVisibilityHelper {
    /**
     * Returns true if notification listener access is granted for this app.
     * Android Auto can ignore notifications when listener access is missing.
     */
    fun hasListenerAccess(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    /**
     * Intent to open notification listener settings so the user can enable it.
     */
    fun listenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

