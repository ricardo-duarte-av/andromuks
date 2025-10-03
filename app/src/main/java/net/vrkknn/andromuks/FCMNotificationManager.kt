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

class FCMNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FCMNotificationManager"
        private const val PREFS_NAME = "fcm_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_BACKEND_REGISTERED = "backend_registered"
        private const val KEY_HOMESERVER_URL = "homeserver_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
    }
    
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get FCM token
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "FCM Token: $fcmToken")
                
                // Store credentials
                storeCredentials(homeserverUrl, userId, accessToken)
                
                // Register with backend
                val success = registerWithBackend(fcmToken, homeserverUrl, userId, accessToken)
                
                if (success) {
                    prefs.edit().putString(KEY_FCM_TOKEN, fcmToken).apply()
                    prefs.edit().putBoolean(KEY_BACKEND_REGISTERED, true).apply()
                    Log.d(TAG, "Successfully registered FCM token with backend")
                } else {
                    Log.e(TAG, "Failed to register FCM token with backend")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing FCM", e)
            }
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
