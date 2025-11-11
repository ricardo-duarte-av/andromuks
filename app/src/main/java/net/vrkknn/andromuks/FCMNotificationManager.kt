package net.vrkknn.andromuks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Data class to hold all FCM-related components after initialization
 */
data class FCMComponents(
    val fcmNotificationManager: FCMNotificationManager,
    val conversationsApi: ConversationsApi,
    val personsApi: PersonsApi,
    val webClientPushIntegration: WebClientPushIntegration
)

class FCMNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FCMNotificationManager"
        private const val PREFS_NAME = "fcm_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_BACKEND_REGISTERED = "backend_registered"
        private const val KEY_HOMESERVER_URL = "homeserver_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        
        /**
         * Initializes all FCM-related components including notification manager,
         * conversations API, and web client push integration.
         * 
         * This is the main entry point for setting up Firebase Cloud Messaging support
         * in the application. It creates and configures all necessary components for:
         * - Receiving push notifications
         * - Managing conversation shortcuts
         * - Handling web client push registration
         * 
         * @param context Application context
         * @param homeserverUrl The Gomuks backend URL (e.g., https://webmuks.aguiarvieira.pt)
         * @param authToken Authentication token for the backend
         * @param realMatrixHomeserverUrl The actual Matrix homeserver URL (e.g., https://matrix.org)
         * @return FCMComponents containing initialized instances of all FCM-related components
         */
        fun initializeComponents(
            context: Context,
            homeserverUrl: String = "",
            authToken: String = "",
            realMatrixHomeserverUrl: String = ""
        ): FCMComponents {
            android.util.Log.d(TAG, "Initializing FCM components")
            
            val fcmNotificationManager = FCMNotificationManager(context)
            val conversationsApi = ConversationsApi(context, homeserverUrl, authToken, realMatrixHomeserverUrl)
            val personsApi = PersonsApi(context, homeserverUrl, authToken, realMatrixHomeserverUrl)
            val webClientPushIntegration = WebClientPushIntegration(context)
            
            android.util.Log.d(TAG, "FCM components initialized successfully")
            
            return FCMComponents(
                fcmNotificationManager = fcmNotificationManager,
                conversationsApi = conversationsApi,
                personsApi = personsApi,
                webClientPushIntegration = webClientPushIntegration
            )
        }
        
        /**
         * Registers FCM notifications with the backend.
         * 
         * This function initiates the FCM token registration process with the Gomuks backend.
         * It sets up a callback to trigger when the FCM token is ready, which then initiates
         * the WebSocket-based registration with the backend.
         * 
         * Prerequisites:
         * - homeserverUrl must be set and non-blank
         * - authToken must be set and non-blank
         * - currentUserId must be set and non-blank
         * 
         * @param fcmNotificationManager The FCM notification manager instance
         * @param homeserverUrl The Gomuks backend URL
         * @param authToken Authentication token for the backend
         * @param currentUserId The current Matrix user ID
         * @param onTokenReady Callback to invoke when FCM token is ready for Gomuks backend registration
         */
        fun registerNotifications(
            fcmNotificationManager: FCMNotificationManager,
            homeserverUrl: String,
            authToken: String,
            currentUserId: String,
            onTokenReady: () -> Unit
        ) {
            if (homeserverUrl.isBlank() || authToken.isBlank() || currentUserId.isBlank()) {
                android.util.Log.w(TAG, "Cannot register FCM notifications - missing required parameters")
                android.util.Log.d(TAG, "homeserverUrl: ${if (homeserverUrl.isBlank()) "BLANK" else "OK"}")
                android.util.Log.d(TAG, "authToken: ${if (authToken.isBlank()) "BLANK" else "OK"}")
                android.util.Log.d(TAG, "currentUserId: ${if (currentUserId.isBlank()) "BLANK" else "OK"}")
                return
            }
            
            android.util.Log.d(TAG, "Registering FCM notifications for user: $currentUserId")
            
            // Set callback to register with Gomuks backend when FCM token is ready
            fcmNotificationManager.setOnTokenReadyCallback {
                android.util.Log.d(TAG, "FCM token ready, triggering Gomuks Backend registration callback")
                onTokenReady()
            }
            
            // Initialize and register with FCM
            fcmNotificationManager.initializeAndRegister(homeserverUrl, currentUserId, authToken)
        }
    }
    
    // Callback to notify when FCM token is ready for Gomuks backend registration
    private var onTokenReadyCallback: (() -> Unit)? = null
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient()
    
    /**
     * Initialize FCM and register with backend
     */
    fun initializeAndRegister(
        homeserverUrl: String,
        userId: String,
        accessToken: String
    ) {
        Log.d(TAG, "initializeAndRegister called with homeserverUrl=$homeserverUrl, userId=$userId, accessToken=${accessToken.take(20)}...")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Getting FCM token...")
                // Get FCM token
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "FCM Token received: ${fcmToken.take(50)}...")
                
                // Store credentials
                storeCredentials(homeserverUrl, userId, accessToken)
                Log.d(TAG, "Credentials stored")
                
                // Register with backend
                val success = registerWithBackend(fcmToken, homeserverUrl, userId, accessToken)
                Log.d(TAG, "Backend registration result: $success")
                
                if (success) {
                    prefs.edit().putString(KEY_FCM_TOKEN, fcmToken).apply()
                    prefs.edit().putBoolean(KEY_BACKEND_REGISTERED, true).apply()
                    Log.d(TAG, "Successfully registered FCM token with backend")
                    
                    // Notify that token is ready for Gomuks backend registration
                    Log.d(TAG, "Calling onTokenReadyCallback")
                    onTokenReadyCallback?.invoke()
                } else {
                    Log.e(TAG, "Failed to register FCM token with backend")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing FCM", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Set callback to be called when FCM token is ready for Gomuks backend registration
     */
    fun setOnTokenReadyCallback(callback: () -> Unit) {
        onTokenReadyCallback = callback
        
        // If token is already available, call the callback immediately
        if (getTokenForGomuksBackend() != null) {
            callback()
        }
    }
    
    /**
     * Register FCM token with Gomuks Backend
     * This stores the token for the AppViewModel to send via WebSocket
     */
    private suspend fun registerWithBackend(
        fcmToken: String,
        homeserverUrl: String,
        userId: String,
        accessToken: String
    ): Boolean {
        return try {
            // Store the FCM token for Gomuks Backend registration
            prefs.edit().putString("fcm_token_for_gomuks", fcmToken).apply()
            
            // Store other registration data
            prefs.edit()
                .putString("homeserver_url", homeserverUrl)
                .putString("user_id", userId)
                .putString("access_token", accessToken)
                .apply()
            
            Log.d(TAG, "Stored FCM token for Gomuks Backend registration: $fcmToken")
            
            // Return true as we've prepared the token for registration
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing FCM token for Gomuks Backend registration", e)
            false
        }
    }
    
    /**
     * Unregister FCM token from backend
     */
    fun unregisterFromBackend() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val homeserverUrl = prefs.getString(KEY_HOMESERVER_URL, null)
                val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
                
                if (homeserverUrl != null && accessToken != null) {
                    val pushGatewayUrl = "$homeserverUrl/_matrix/push/v1/register"
                    
                    val request = Request.Builder()
                        .url(pushGatewayUrl)
                        .delete()
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build()
                    
                    val response = httpClient.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        prefs.edit().putBoolean(KEY_BACKEND_REGISTERED, false).apply()
                        Log.d(TAG, "Successfully unregistered from backend")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering from backend", e)
            }
        }
    }
    
    /**
     * Get current FCM token
     */
    fun getCurrentToken(): String? {
        return prefs.getString(KEY_FCM_TOKEN, null)
    }
    
    /**
     * Get FCM token for Gomuks Backend registration
     */
    fun getTokenForGomuksBackend(): String? {
        return prefs.getString("fcm_token_for_gomuks", null)
    }
    
    /**
     * Check if registered with backend
     */
    fun isRegisteredWithBackend(): Boolean {
        return prefs.getBoolean(KEY_BACKEND_REGISTERED, false)
    }
    
    /**
     * Store user credentials for FCM registration
     */
    private fun storeCredentials(homeserverUrl: String, userId: String, accessToken: String) {
        prefs.edit()
            .putString(KEY_HOMESERVER_URL, homeserverUrl)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply()
    }
    
    /**
     * Clear stored credentials
     */
    fun clearCredentials() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Refresh FCM token and re-register with backend
     */
    fun refreshToken() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val newToken = FirebaseMessaging.getInstance().token.await()
                val homeserverUrl = prefs.getString(KEY_HOMESERVER_URL, null)
                val userId = prefs.getString(KEY_USER_ID, null)
                val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
                
                if (homeserverUrl != null && userId != null && accessToken != null) {
                    val success = registerWithBackend(newToken, homeserverUrl, userId, accessToken)
                    
                    if (success) {
                        prefs.edit().putString(KEY_FCM_TOKEN, newToken).apply()
                        Log.d(TAG, "Successfully refreshed FCM token")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing FCM token", e)
            }
        }
    }
}
