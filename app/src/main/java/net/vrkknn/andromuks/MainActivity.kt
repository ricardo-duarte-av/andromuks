package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.CrashHandler
import net.vrkknn.andromuks.utils.CrashReportDialog
import net.vrkknn.andromuks.BuildConfig

import androidx.lifecycle.Lifecycle
import net.vrkknn.andromuks.SharedMediaItem
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private lateinit var notificationBroadcastReceiver: BroadcastReceiver
    private lateinit var notificationActionReceiver: BroadcastReceiver
    private var viewModelVisibilitySynced = false
    private var pendingShareIntent: Intent? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash handler
        CrashHandler.initialize(this)
        
        enableEdgeToEdge()

        pendingShareIntent = intent.takeIf { isShareIntent(it) }
        
        setContent {
            AndromuksTheme {
                // Check for crash and show dialog if needed
                var showCrashDialog by remember { mutableStateOf(CrashHandler.checkAndShowCrashDialog(this@MainActivity)) }
                val crashLogPath = remember { CrashHandler.getLastCrashLogPath(this@MainActivity) }
                
                if (showCrashDialog && crashLogPath != null) {
                    CrashReportDialog(
                        crashLogPath = crashLogPath,
                        onDismiss = { showCrashDialog = false },
                        onEmail = {
                            CrashHandler.emailCrashLog(this@MainActivity, crashLogPath)
                            showCrashDialog = false
                        }
                    )
                }
                
                AppNavigation(
                    modifier = Modifier.fillMaxSize(),
                    onViewModelCreated = { viewModel ->
                        if (!::appViewModel.isInitialized) {
                            appViewModel = viewModel
                            appViewModel.markAsPrimaryInstance()
                            
                            // CRITICAL: Initialize FCM first to set appContext before loading profiles
                            // Get homeserver URL and auth token from SharedPreferences
                            val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                            val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                            val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                            if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                appViewModel.initializeFCM(this, homeserverUrl, authToken)
                            }
                            
                            // Load cached user profiles on app startup
                            // This restores previously saved user profile data from disk
                            appViewModel.loadCachedProfiles(this)
                            
                            // Load app settings from SharedPreferences
                            appViewModel.loadSettings(this)
                            
                            // CRITICAL FIX #2: Check for pending items on app startup and process them
                            // NOTE: This is called AFTER loadSettings to ensure syncIngestor can be initialized
                            // This ensures RoomListScreen shows up-to-date data when app opens
                            // Use a small delay to ensure initialization is complete
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                appViewModel.checkAndProcessPendingItemsOnStartup(this)
                            }, 500)
                            
                            // Schedule database maintenance (daily at 2 AM)
                            net.vrkknn.andromuks.database.DatabaseMaintenanceWorker.schedule(this)
                            
                            // Schedule WebSocket health checks (every 15 minutes)
                            WebSocketHealthCheckWorker.schedule(this)
                            
                            // Schedule auto-restart worker (every 30 minutes)
                            AutoRestartWorker.schedule(this)
                            
                            // OPTIMIZATION #2: Optimized intent processing
                            val shortcutUserId = intent.getStringExtra(PersonsApi.EXTRA_USER_ID)
                            val roomId = intent.getStringExtra("room_id")
                            val directNavigation = intent.getBooleanExtra("direct_navigation", false)
                            val fromNotification = intent.getBooleanExtra("from_notification", false)
                            val matrixUri = intent.data
                            val notificationEventId = intent.getStringExtra("event_id")
                            
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onCreate - roomId: $roomId, directNavigation: $directNavigation, fromNotification: $fromNotification, matrixUri: $matrixUri")
                            
                            val extractedRoomId = if (directNavigation && roomId != null) {
                                // OPTIMIZATION #2: Fast path - room ID already extracted
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onCreate - OPTIMIZATION #2 - Using pre-extracted room ID: $roomId")
                                roomId
                            } else {
                                // Fallback to URI parsing for legacy intents
                                val uriRoomId = extractRoomIdFromMatrixUri(matrixUri)
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onCreate - Fallback URI parsing: $uriRoomId")
                                uriRoomId
                            }
                            
                            if (extractedRoomId != null) {
                                // CRITICAL FIX #2: Store room navigation and wait for WebSocket connection
                                // This ensures proper state (spacesLoaded, WebSocket connected) before navigating
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onCreate - Storing direct room navigation to: $extractedRoomId (will wait for WebSocket)")
                                // Extract notification timestamp if present
                                val notificationTimestamp = intent.getLongExtra("notification_timestamp", 0L).takeIf { it > 0 }
                                // Store for navigation once WebSocket is connected and spacesLoaded = true
                                appViewModel.setDirectRoomNavigation(
                                    roomId = extractedRoomId,
                                    notificationTimestamp = notificationTimestamp,
                                    targetEventId = notificationEventId
                                )
                                if (!shortcutUserId.isNullOrBlank()) {
                                    appViewModel.reportPersonShortcutUsed(shortcutUserId)
                                }
                                // NOTE: Don't call navigateToRoomWithCache() here - RoomListScreen will handle it
                                // after WebSocket connection and spacesLoaded = true
                            }
                            
                            // Register broadcast receiver for notification actions
                            registerNotificationBroadcastReceiver()
                            registerNotificationActionReceiver()

                            pendingShareIntent?.let { storedIntent ->
                                processShareIntent(storedIntent)
                                pendingShareIntent = null
                            }
                        }

                        // Re-attach to existing WebSocket connection if the service already has one
                        viewModel.attachToExistingWebSocketIfAvailable()

                        if (!viewModelVisibilitySynced) {
                            viewModelVisibilitySynced = true
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: ViewModel created after onResume - forcing visible state")
                                viewModel.onAppBecameVisible()
                            }
                        }
                    }
                )
            }
        }
    }
    
    private fun isShareIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false
        return (Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action)
    }

    private fun processShareIntent(intent: Intent) {
        if (!::appViewModel.isInitialized) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: ViewModel not ready, storing share intent for later processing")
            pendingShareIntent = intent
            return
        }
        val targetRoomId = intent.getStringExtra(PersonsApi.EXTRA_ROOM_ID)
        val targetUserId = intent.getStringExtra(PersonsApi.EXTRA_USER_ID)
        val shareItems = extractShareItems(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (shareItems.isEmpty() && sharedText.isNullOrBlank() && targetRoomId.isNullOrBlank()) {
            Log.w("Andromuks", "MainActivity: Share intent ignored - no media/text and no target room")
            return
        }

        appViewModel.setPendingShare(shareItems, sharedText, targetRoomId)
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "MainActivity: Share intent processed with ${shareItems.size} media items, targetRoom=$targetRoomId"
        )

        if (!targetRoomId.isNullOrBlank()) {
            if (!targetUserId.isNullOrBlank()) {
                appViewModel.reportPersonShortcutUsed(targetUserId)
            }
            appViewModel.setDirectRoomNavigation(targetRoomId)
            appViewModel.navigateToRoomWithCache(targetRoomId)
            appViewModel.markPendingShareNavigationHandled()
        }

        // Prevent re-processing of the same intent
        pendingShareIntent = null
        setIntent(Intent(this, javaClass).apply { action = Intent.ACTION_MAIN })
    }

    private fun extractShareItems(intent: Intent): List<SharedMediaItem> {
        val items = mutableListOf<SharedMediaItem>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                getStreamUri(intent)?.let { uri ->
                    val mimeType = intent.type ?: contentResolver.getType(uri)
                    grantUriPermissions(uri, intent)
                    items.add(SharedMediaItem(uri, mimeType))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val clipData = intent.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    for (index in 0 until clipData.itemCount) {
                        val clipItem = clipData.getItemAt(index)
                        val uri = clipItem.uri ?: continue
                        val mimeType =
                            clipData.description?.getMimeType(index)
                                ?: contentResolver.getType(uri)
                                ?: intent.type
                        grantUriPermissions(uri, intent)
                        items.add(SharedMediaItem(uri, mimeType))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uriList = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    uriList?.forEach { uri ->
                        val mimeType = contentResolver.getType(uri) ?: intent.type
                        grantUriPermissions(uri, intent)
                        items.add(SharedMediaItem(uri, mimeType))
                    }
                }
            }
        }
        return items
    }

    private fun grantUriPermissions(uri: Uri, intent: Intent) {
        try {
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w("Andromuks", "MainActivity: Unable to grant read permission for $uri", e)
        }
        if ((intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.w("Andromuks", "MainActivity: Unable to persist read permission for $uri", e)
            }
        }
    }

    private fun getStreamUri(intent: Intent): Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun registerNotificationBroadcastReceiver() {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Registering notification broadcast receiver")
        notificationBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Broadcast receiver got intent: ${intent?.action}")
                when (intent?.action) {
                    "net.vrkknn.andromuks.SEND_MESSAGE" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val messageText = intent.getStringExtra("message_text")
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: SEND_MESSAGE broadcast - roomId: $roomId, messageText: $messageText")
                        if (roomId != null && messageText != null) {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Received send message broadcast for room $roomId: $messageText")
                            appViewModel.sendMessageFromNotification(roomId, messageText) {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Broadcast send message completed")
                                // Update the notification with the sent message
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationWithReply(roomId, messageText)
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Updated notification with reply for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification with reply", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: SEND_MESSAGE broadcast missing data - roomId: $roomId, messageText: $messageText")
                        }
                    }
                    "net.vrkknn.andromuks.MARK_READ" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: MARK_READ broadcast - roomId: $roomId, eventId: $eventId")
                        if (roomId != null) {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Received mark read broadcast for room $roomId, event: $eventId")
                            appViewModel.markRoomAsReadFromNotification(roomId, eventId ?: "") {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Broadcast mark read completed")
                                // Update the notification to show it's been read
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationAsRead(roomId)
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Updated notification as read for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification as read", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: MARK_READ broadcast missing roomId")
                        }
                    }
                    else -> {
                        Log.w("Andromuks", "MainActivity: Unknown broadcast action: ${intent?.action}")
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("net.vrkknn.andromuks.SEND_MESSAGE")
            addAction("net.vrkknn.andromuks.MARK_READ")
        }
        registerReceiver(notificationBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Notification broadcast receiver registered successfully")
    }
    
    private fun registerNotificationActionReceiver() {
        notificationActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "net.vrkknn.andromuks.ACTION_REPLY" -> {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: ACTION_REPLY received in broadcast receiver")
                        
                        // DEDUPLICATION: Check if this is a duplicate from NotificationReplyReceiver
                        // The receiver forwards via ordered broadcast, which can be received multiple times
                        val fromReplyReceiver = intent.getBooleanExtra("from_reply_receiver", false)
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: ACTION_REPLY from_reply_receiver: $fromReplyReceiver")
                        
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        val replyText = getReplyText(intent)
                        
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Reply data extracted - roomId: $roomId, eventId: $eventId, replyText: '$replyText'")
                        
                        if (roomId != null && replyText != null) {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Calling appViewModel.sendMessageFromNotification for room: $roomId")
                            // Deduplication is handled in sendMessageFromNotification
                            appViewModel.sendMessageFromNotification(roomId, replyText) {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Reply message sent successfully")
                                // Update the notification with the sent message
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationWithReply(roomId, replyText)
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Updated notification with reply for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification with reply", e)
                                }
                            }
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: sendMessageFromNotification call completed")
                        } else {
                            Log.w("Andromuks", "MainActivity: Missing required data - roomId: $roomId, replyText: $replyText")
                        }
                    }
                    "net.vrkknn.andromuks.ACTION_MARK_READ" -> {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: ACTION_MARK_READ received in broadcast receiver")
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Mark read data extracted - roomId: $roomId, eventId: '$eventId'")
                        
                        if (roomId != null && eventId != null && eventId.isNotEmpty()) {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Calling appViewModel.markRoomAsReadFromNotification for room: $roomId, event: $eventId")
                            appViewModel.markRoomAsReadFromNotification(roomId, eventId) {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Mark read completed successfully")
                                // Update the notification to show it's been read
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationAsRead(roomId)
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Updated notification as read for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification as read", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: Missing required data for mark read - roomId: $roomId, eventId: '$eventId'")
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("net.vrkknn.andromuks.ACTION_REPLY")
            addAction("net.vrkknn.andromuks.ACTION_MARK_READ")
        }
        registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
    
    private fun getReplyText(intent: Intent): String? {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: getReplyText called")
        val remoteInputResults = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: RemoteInput results: $remoteInputResults")
        
        val replyText = remoteInputResults
            ?.getCharSequence("key_reply_text")
            ?.toString()
            
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Extracted reply text: '$replyText'")
        return replyText
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (isShareIntent(intent)) {
            if (::appViewModel.isInitialized) {
                processShareIntent(intent)
            } else {
                pendingShareIntent = intent
            }
            return
        }
        
        // OPTIMIZATION #2: Optimized intent processing for onNewIntent
        val shortcutUserId = intent.getStringExtra(PersonsApi.EXTRA_USER_ID)
        val roomId = intent.getStringExtra("room_id")
        val directNavigation = intent.getBooleanExtra("direct_navigation", false)
        val fromNotification = intent.getBooleanExtra("from_notification", false)
        val matrixUri = intent.data
        val notificationEventId = intent.getStringExtra("event_id")
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onNewIntent - roomId: $roomId, directNavigation: $directNavigation, fromNotification: $fromNotification, matrixUri: $matrixUri")
        
        val extractedRoomId = if (directNavigation && roomId != null) {
            // OPTIMIZATION #2: Fast path - room ID already extracted
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onNewIntent - OPTIMIZATION #2 - Using pre-extracted room ID: $roomId")
            roomId
        } else {
            // Fallback to URI parsing for legacy intents
            val uriRoomId = extractRoomIdFromMatrixUri(matrixUri)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onNewIntent - Fallback URI parsing: $uriRoomId")
            uriRoomId
        }
        
        extractedRoomId?.let { roomId ->
            if (::appViewModel.isInitialized) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onNewIntent - Direct navigation to room: $roomId")
                // OPTIMIZATION #1: Direct navigation instead of pending state
                val notificationTimestamp = intent.getLongExtra("notification_timestamp", 0L).takeIf { it > 0 }
                appViewModel.setDirectRoomNavigation(
                    roomId = roomId,
                    notificationTimestamp = notificationTimestamp,
                    targetEventId = notificationEventId
                )
                if (!shortcutUserId.isNullOrBlank()) {
                    appViewModel.reportPersonShortcutUsed(shortcutUserId)
                }
                // Navigate with notification timestamp if available
                if (notificationTimestamp != null) {
                    appViewModel.navigateToRoomWithCache(roomId, notificationTimestamp)
                }
                // Force navigation if app is already running
                if (appViewModel.spacesLoaded) {
                    // App is already initialized, trigger navigation directly
                    // This will be handled by the UI layer
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onDestroy called")
        try {
            if (::notificationBroadcastReceiver.isInitialized) {
                unregisterReceiver(notificationBroadcastReceiver)
            }
            if (::notificationActionReceiver.isInitialized) {
                unregisterReceiver(notificationActionReceiver)
            }
        } catch (e: Exception) {
            Log.w("Andromuks", "MainActivity: Error unregistering broadcast receivers", e)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onStop called")
    }
    
    override fun onPause() {
        super.onPause()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onPause called")
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameInvisible()
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onResume called")
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameVisible()
        }
        
        // Broadcast that app is now in foreground so screens can refresh
        val foregroundRefreshIntent = Intent("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        sendBroadcast(foregroundRefreshIntent)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Sent FOREGROUND_REFRESH broadcast")
    }
    
    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: onStart called")
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
    }
    
    /**
     * Extract room ID or alias from matrix: URI
     * Examples:
     * - matrix:roomid/!bDYZaOWoWwefjYdoRz:aguiarvieira.pt?via=aguiarvieira.pt
     * - matrix:r/#test9test:aguiarvieira.pt
     */
    private fun extractRoomIdFromMatrixUri(uri: android.net.Uri?): String? {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: extractRoomIdFromMatrixUri called with uri: $uri")
        if (uri == null) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: URI is null")
            return null
        }

        val uriString = uri.toString()

        if (uriString.startsWith("matrix:u/", ignoreCase = true)) {
            val encodedUser = uriString.substringAfter("matrix:u/", missingDelimiterValue = "")
                .substringBefore("?")
            val decodedUser = runCatching {
                URLDecoder.decode(encodedUser, Charsets.UTF_8.name())
            }.getOrDefault(encodedUser)
            val userId = if (decodedUser.startsWith("@")) decodedUser else "@$decodedUser"

            if (::appViewModel.isInitialized) {
                val roomId = appViewModel.getDirectRoomIdForUser(userId)
                if (roomId != null) {
                    if (BuildConfig.DEBUG) Log.d(
                        "Andromuks",
                        "MainActivity: Resolved matrix:u URI for $userId to direct room $roomId"
                    )
                    return roomId
                }
            } else {
                Log.w(
                    "Andromuks",
                    "MainActivity: ViewModel not initialised yet, cannot resolve matrix:u URI"
                )
            }

            Log.w(
                "Andromuks",
                "MainActivity: No direct room found for matrix:u URI ($userId)"
            )
            return null
        }

        // Use the extractRoomLink utility function for all other matrix URIs
        val roomLink = net.vrkknn.andromuks.utils.extractRoomLink(uriString)
        if (roomLink != null) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Extracted room link: ${roomLink.roomIdOrAlias}")
            return roomLink.roomIdOrAlias
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "MainActivity: Could not extract room link from URI")
        return null
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier,
    onViewModelCreated: (AppViewModel) -> Unit = {}
) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    
    // Notify the parent about the ViewModel creation
    onViewModelCreated(appViewModel)

    LaunchedEffect(appViewModel.pendingShareNavigationRequested) {
        if (appViewModel.pendingShareNavigationRequested) {
            navController.navigate("simple_room_list") {
                launchSingleTop = true
            }
            appViewModel.markPendingShareNavigationHandled()
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = "auth_check",
        modifier = modifier
    ) {
        composable("login") { LoginScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("auth_check") { AuthCheckScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("permissions") {
            PermissionsScreen(
                onPermissionsGranted = {
                    // Navigate to auth_check after permissions are granted
                    // This will trigger WebSocket connection
                    navController.navigate("auth_check") {
                        popUpTo("permissions") { inclusive = true }
                    }
                },
                modifier = modifier
            )
        }
        composable(
            route = "room_list",
            exitTransition = {
                if (targetState.destination.route == "room_timeline/{roomId}") {
                    slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            },
            popEnterTransition = {
                if (initialState.destination.route == "room_timeline/{roomId}") {
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            }
        ) { RoomListScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("simple_room_list") {
            SimplerRoomListScreen(
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable(
            route = "room_timeline/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            enterTransition = {
                if (initialState.destination.route == "room_list") {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            },
            popExitTransition = {
                if (targetState.destination.route == "room_list") {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            }
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            val roomName = appViewModel.getRoomById(roomId)?.name ?: ""
            RoomTimelineScreen(
                roomId = roomId,
                roomName = roomName,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable(
            route = "thread_viewer/{roomId}/{threadRootId}",
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("threadRootId") { type = NavType.StringType }
            ),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            }
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            val threadRootId = backStackEntry.arguments?.getString("threadRootId") ?: ""
            ThreadViewerScreen(
                roomId = roomId,
                threadRootEventId = threadRootId,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable(
            route = "invite_detail/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            InviteDetailScreen(
                roomId = roomId,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable("reconnection_log") {
            ReconnectionLogScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable("unprocessed_events") {
            UnprocessedEventsScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable("room_disk_stats") {
            RoomDiskStatsScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "cached_profiles/{cacheType}",
            arguments = listOf(navArgument("cacheType") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val cacheType = backStackEntry.arguments?.getString("cacheType") ?: "memory"
            CachedProfilesScreen(
                cacheType = cacheType,
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "cached_media/{cacheType}",
            arguments = listOf(navArgument("cacheType") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val cacheType = backStackEntry.arguments?.getString("cacheType") ?: "memory"
            CachedMediaScreen(
                cacheType = cacheType,
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "room_info/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            enterTransition = {
                fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            }
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            net.vrkknn.andromuks.utils.RoomInfoScreen(
                roomId = roomId,
                navController = navController,
                appViewModel = appViewModel,
                modifier = modifier
            )
        }
        composable(
            route = "room_db_explorer/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            net.vrkknn.andromuks.utils.RoomDBExplorerScreen(
                roomId = roomId,
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "room_timeline_viewer/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            net.vrkknn.andromuks.utils.RoomTimelineViewerScreen(
                roomId = roomId,
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "user_info/{userId}",
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType }
            ),
            enterTransition = {
                fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        transformOrigin = TransformOrigin.Center
                    )
            }
        ) { backStackEntry: NavBackStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            // Extract roomId from savedStateHandle (set during navigation)
            val roomId = backStackEntry.savedStateHandle.get<String>("roomId")?.takeIf { it.isNotBlank() }
                ?: backStackEntry.savedStateHandle.get<String>("user_info_roomId")?.takeIf { it.isNotBlank() }
            net.vrkknn.andromuks.utils.UserInfoScreen(
                userId = userId,
                navController = navController,
                appViewModel = appViewModel,
                roomId = roomId,
                modifier = modifier
            )
        }
    }
}