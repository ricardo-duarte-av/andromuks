package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Helper for requesting auto-start permissions on various Android devices
 * 
 * Different manufacturers have different implementations:
 * - Xiaomi/MIUI: "Autostart" permission
 * - Huawei/EMUI: "Protected apps" or "Startup manager"
 * - Oppo/ColorOS: "Startup manager"
 * - Vivo/FuntouchOS: "Background running"
 * - OnePlus/OxygenOS: "Battery optimization"
 * - Samsung: Generally no additional permission needed
 */
object AutoStartPermissionHelper {
    private const val TAG = "AutoStartPermission"
    
    /**
     * Detected device manufacturer types
     */
    enum class Manufacturer {
        XIAOMI,
        HUAWEI,
        OPPO,
        VIVO,
        ONEPLUS,
        SAMSUNG,
        ASUS,
        NOKIA,
        OTHER
    }
    
    /**
     * Detect the device manufacturer
     */
    fun getManufacturer(): Manufacturer {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Manufacturer.XIAOMI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Manufacturer.HUAWEI
            manufacturer.contains("oppo") -> Manufacturer.OPPO
            manufacturer.contains("vivo") -> Manufacturer.VIVO
            manufacturer.contains("oneplus") -> Manufacturer.ONEPLUS
            manufacturer.contains("samsung") -> Manufacturer.SAMSUNG
            manufacturer.contains("asus") -> Manufacturer.ASUS
            manufacturer.contains("nokia") -> Manufacturer.NOKIA
            else -> Manufacturer.OTHER
        }
    }
    
    /**
     * Check if auto-start permission is likely needed for this device
     */
    fun isAutoStartPermissionNeeded(): Boolean {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI,
            Manufacturer.HUAWEI,
            Manufacturer.OPPO,
            Manufacturer.VIVO,
            Manufacturer.ASUS -> true
            Manufacturer.ONEPLUS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            else -> false
        }
    }
    
    /**
     * Open auto-start settings for the current device
     * Returns true if settings page was opened successfully
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = getManufacturer()
        val intent = getAutoStartIntent(context, manufacturer)
        
        return try {
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                if (BuildConfig.DEBUG) Log.d(TAG, "Opened auto-start settings for $manufacturer")
                true
            } ?: run {
                Log.w(TAG, "No auto-start settings available for $manufacturer")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open auto-start settings for $manufacturer", e)
            // Try fallback
            tryFallbackIntent(context)
        }
    }
    
    /**
     * Get the Intent to open auto-start settings for a specific manufacturer
     */
    private fun getAutoStartIntent(context: Context, manufacturer: Manufacturer): Intent? {
        val packageName = context.packageName
        
        return when (manufacturer) {
            Manufacturer.XIAOMI -> {
                // Try MIUI-specific autostart
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
                    // Fallback to general permissions
                    ?: Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        putExtra("extra_pkgname", packageName)
                    }.takeIf { isIntentAvailable(context, it) }
            }
            
            Manufacturer.HUAWEI -> {
                // Huawei protected apps
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
                    // Fallback
                    ?: Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity"
                        )
                    }.takeIf { isIntentAvailable(context, it) }
            }
            
            Manufacturer.OPPO -> {
                // ColorOS startup manager
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
                    // Fallback
                    ?: Intent().apply {
                        component = android.content.ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    }.takeIf { isIntentAvailable(context, it) }
            }
            
            Manufacturer.VIVO -> {
                // Vivo background running
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
                    // Fallback
                    ?: Intent().apply {
                        component = android.content.ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                        )
                    }.takeIf { isIntentAvailable(context, it) }
            }
            
            Manufacturer.ASUS -> {
                // ASUS auto-start manager
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.asus.mobilemanager",
                        "com.asus.mobilemanager.entry.FunctionActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
            }
            
            Manufacturer.NOKIA -> {
                // Nokia battery optimization
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.evenwell.powersaving.g3",
                        "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"
                    )
                }.takeIf { isIntentAvailable(context, it) }
            }
            
            else -> null
        }
    }
    
    /**
     * Try fallback intent (app settings)
     */
    private fun tryFallbackIntent(context: Context): Boolean {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (BuildConfig.DEBUG) Log.d(TAG, "Opened app settings as fallback")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open fallback settings", e)
            false
        }
    }
    
    /**
     * Check if an intent can be resolved
     */
    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }
    
    /**
     * Get human-readable description of auto-start permission for current device
     */
    fun getAutoStartDescription(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> "Enable 'Autostart' permission in MIUI Security app to keep Andromuks connected when the screen is off."
            Manufacturer.HUAWEI -> "Add Andromuks to 'Protected apps' in Huawei Phone Manager to maintain connection in background."
            Manufacturer.OPPO -> "Enable Andromuks in 'Startup Manager' to allow background connections."
            Manufacturer.VIVO -> "Allow 'Background running' for Andromuks in i Manager to maintain connection."
            Manufacturer.ONEPLUS -> "Disable battery optimization for Andromuks to maintain background connection."
            Manufacturer.ASUS -> "Add Andromuks to auto-start list in Mobile Manager to keep connection alive."
            Manufacturer.NOKIA -> "Add Andromuks to battery optimization exceptions to maintain background connection."
            Manufacturer.SAMSUNG -> "Samsung devices generally work well with foreground services. No additional permission needed."
            Manufacturer.OTHER -> "Your device may require disabling battery optimization or allowing auto-start for background connections."
        }
    }
    
    /**
     * Get the name of the permission/setting for current device
     */
    fun getAutoStartPermissionName(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> "Autostart"
            Manufacturer.HUAWEI -> "Protected Apps"
            Manufacturer.OPPO -> "Startup Manager"
            Manufacturer.VIVO -> "Background Running"
            Manufacturer.ONEPLUS -> "Battery Optimization"
            Manufacturer.ASUS -> "Auto-start"
            Manufacturer.NOKIA -> "Battery Optimization"
            Manufacturer.SAMSUNG -> "N/A"
            Manufacturer.OTHER -> "Auto-start/Battery Optimization"
        }
    }
}

