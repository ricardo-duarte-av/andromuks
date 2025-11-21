package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Global crash handler that catches uncaught exceptions and saves them to a file.
 * On app restart, shows a dialog asking the user if they want to email the crash log.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {
    private const val TAG = "CrashHandler"
    private const val CRASH_LOG_DIR = "crash_logs"
    private const val CRASH_LOG_PREFIX = "crash_"
    private const val CRASH_LOG_EXT = ".txt"
    private const val PREF_LAST_CRASH_TIME = "last_crash_time"
    private const val PREF_CRASH_LOG_PATH = "last_crash_log_path"
    private const val CRASH_DIALOG_COOLDOWN_MS = 5000L // Don't show dialog if crash was less than 5 seconds ago
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null
    
    /**
     * Initialize the crash handler. Should be called in Application.onCreate() or MainActivity.onCreate()
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        if (BuildConfig.DEBUG) Log.d(TAG, "Crash handler initialized")
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception caught", throwable)
        
        try {
            // Save crash log to file
            val crashLogPath = saveCrashLog(throwable, thread)
            
            // Save crash info to SharedPreferences for dialog on next app start
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(PREF_LAST_CRASH_TIME, System.currentTimeMillis())
                    .putString(PREF_CRASH_LOG_PATH, crashLogPath)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
        
        // Call default handler to show system crash dialog
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    /**
     * Save crash log to file and return the file path
     */
    private fun saveCrashLog(throwable: Throwable, thread: Thread): String? {
        val context = this.context ?: return null
        
        try {
            val crashDir = File(context.filesDir, CRASH_LOG_DIR)
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val crashFile = File(crashDir, "${CRASH_LOG_PREFIX}${timestamp}${CRASH_LOG_EXT}")
            
            FileWriter(crashFile).use { writer ->
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("=== ANDROMUKS CRASH REPORT ===")
                    printWriter.println("Timestamp: $timestamp")
                    printWriter.println("Thread: ${thread.name}")
                    printWriter.println()
                    
                    printWriter.println("=== DEVICE INFO ===")
                    printWriter.println("Manufacturer: ${Build.MANUFACTURER}")
                    printWriter.println("Model: ${Build.MODEL}")
                    printWriter.println("Android Version: ${Build.VERSION.RELEASE}")
                    printWriter.println("SDK Version: ${Build.VERSION.SDK_INT}")
                    printWriter.println()
                    
                    printWriter.println("=== EXCEPTION ===")
                    throwable.printStackTrace(printWriter)
                    printWriter.println()
                    
                    printWriter.println("=== STACK TRACE ===")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    printWriter.println(sw.toString())
                }
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Crash log saved to: ${crashFile.absolutePath}")
            return crashFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
            return null
        }
    }
    
    /**
     * Check if there's a recent crash and show dialog if needed.
     * Should be called from MainActivity.onCreate() or similar.
     */
    fun checkAndShowCrashDialog(activity: Activity): Boolean {
        val context = activity.applicationContext
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        
        val lastCrashTime = prefs.getLong(PREF_LAST_CRASH_TIME, 0)
        val crashLogPath = prefs.getString(PREF_CRASH_LOG_PATH, null)
        
        // Check if crash was recent (within cooldown period)
        val timeSinceCrash = System.currentTimeMillis() - lastCrashTime
        if (timeSinceCrash < CRASH_DIALOG_COOLDOWN_MS || crashLogPath == null) {
            // Clear crash info if it's old
            if (lastCrashTime > 0 && timeSinceCrash >= CRASH_DIALOG_COOLDOWN_MS) {
                prefs.edit()
                    .remove(PREF_LAST_CRASH_TIME)
                    .remove(PREF_CRASH_LOG_PATH)
                    .apply()
            }
            return false
        }
        
        // Clear crash info so dialog only shows once
        prefs.edit()
            .remove(PREF_LAST_CRASH_TIME)
            .remove(PREF_CRASH_LOG_PATH)
            .apply()
        
        // Show dialog
        return true
    }
    
    /**
     * Get the crash log file path if available
     */
    fun getLastCrashLogPath(context: Context): String? {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_CRASH_LOG_PATH, null)
    }
    
    /**
     * Email crash log using Intent.ACTION_SEND
     */
    fun emailCrashLog(context: Context, crashLogPath: String) {
        try {
            val crashFile = File(crashLogPath)
            if (!crashFile.exists()) {
                Log.w(TAG, "Crash log file does not exist: $crashLogPath")
                return
            }
            
            // Create email intent
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("support@andromuks.app")) // Change to your support email
                putExtra(Intent.EXTRA_SUBJECT, "Andromuks Crash Report - ${crashFile.name}")
                putExtra(Intent.EXTRA_TEXT, "Please find the crash report attached.\n\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}")
                
                // Attach crash log file using FileProvider
                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "pt.aguiarvieira.andromuks.fileprovider",
                    crashFile
                )
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(emailIntent, "Send crash report via email"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to email crash log", e)
        }
    }
}

/**
 * Composable dialog for crash report email prompt
 */
@Composable
fun CrashReportDialog(
    crashLogPath: String,
    onDismiss: () -> Unit,
    onEmail: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "App Crashed",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Text(
                    text = "The app encountered an error and crashed. Would you like to send the crash report to help us fix this issue?",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dismiss")
                    }
                    
                    Button(
                        onClick = onEmail,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send Report")
                    }
                }
            }
        }
    }
}

