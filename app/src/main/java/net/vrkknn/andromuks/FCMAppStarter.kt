package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FCM-triggered app starter
 * 
 * When FCM is received, this class ensures the app is running and WebSocket is connected.
 * This is critical for maintaining real-time communication even when the app is not actively running.
 */
object FCMAppStarter {
    private const val TAG = "FCMAppStarter"
    
    /**
     * Ensure the app is running and WebSocket is connected when FCM is received
     * This is called from FCMService when a push notification is received
     */
    fun ensureAppIsRunningAndConnected(context: Context) {
        try {
            Log.d(TAG, "FCM received - ensuring app is running and WebSocket is connected")
            
            // Always start the WebSocket service when FCM is received
            // This ensures the foreground service is running to maintain the app process
            Log.d(TAG, "Starting WebSocket service to maintain app process")
            startWebSocketService(context)
            
            // Check if WebSocket service is running and connected
            val isWebSocketConnected = WebSocketService.isConnected()
            Log.d(TAG, "WebSocket connection status: $isWebSocketConnected")
            
            if (!isWebSocketConnected) {
                Log.w(TAG, "FCM received but WebSocket disconnected - initializing app and connecting")
                
                // Initialize app components and connect WebSocket
                initializeAppAndConnect(context)
            } else {
                Log.d(TAG, "Both FCM and WebSocket are working correctly")
                // Mark network as healthy since we received FCM
                WebSocketService.markNetworkHealthy()
                
                // Also trigger a backend health check to ensure backend is responsive
                WebSocketService.triggerBackendHealthCheck()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring app is running", e)
        }
    }
    
    /**
     * Start the WebSocket service to maintain the app process
     */
    private fun startWebSocketService(context: Context) {
        try {
            Log.d(TAG, "Starting WebSocket service to maintain app process")
            
            val intent = Intent(context, WebSocketService::class.java)
            context.startForegroundService(intent)
            
            Log.d(TAG, "WebSocket service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocket service", e)
        }
    }
    
    /**
     * Initialize app components and establish WebSocket connection
     */
    private fun initializeAppAndConnect(context: Context) {
        try {
            Log.d(TAG, "Initializing app components and connecting WebSocket")
            
            // Get stored credentials from SharedPreferences
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
            val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
            
            if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                Log.e(TAG, "Missing credentials - cannot initialize app")
                return
            }
            
            Log.d(TAG, "Found credentials - homeserver: ${homeserverUrl.take(50)}..., token: ${authToken.take(20)}...")
            
            // Initialize FCM components
            initializeFCMComponents(context, homeserverUrl, authToken)
            
            // Trigger WebSocket reconnection via the service's restart mechanism
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Add a small delay to ensure service is fully started
                    delay(500)
                    
                    Log.d(TAG, "Triggering WebSocket reconnection from FCM via service")
                    
                    // When FCM is received, we want to ensure the WebSocket is connected
                    // Since we can't rely on AppViewModel being initialized, we need to check
                    // if the service has a valid reconnection callback set
                    val hasCallback = WebSocketService.getReconnectionCallback() != null
                    Log.d(TAG, "Service has reconnection callback: $hasCallback")
                    
                    if (hasCallback) {
                        // AppViewModel is initialized, use the callback
                        Log.d(TAG, "AppViewModel is initialized - using reconnection callback")
                        WebSocketService.restartWebSocket("FCM received - ensuring WebSocket connection")
                    } else {
                        // AppViewModel is not initialized yet - cannot connect WebSocket
                        // The service will stay "Connecting..." until the user opens the app
                        Log.w(TAG, "AppViewModel not initialized - cannot connect WebSocket from FCM")
                        Log.w(TAG, "Service will remain in 'Connecting...' state until app is opened manually")
                    }
                    
                    Log.d(TAG, "WebSocket reconnection triggered from FCM")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering WebSocket reconnection from FCM", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing app and connecting", e)
        }
    }
    
    /**
     * Initialize FCM components
     */
    private fun initializeFCMComponents(context: Context, homeserverUrl: String, authToken: String) {
        try {
            Log.d(TAG, "Initializing FCM components")
            
            // Initialize FCM notification manager
            FCMNotificationManager.initializeComponents(context, homeserverUrl, authToken)
            
            Log.d(TAG, "FCM components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FCM components", e)
        }
    }
}
