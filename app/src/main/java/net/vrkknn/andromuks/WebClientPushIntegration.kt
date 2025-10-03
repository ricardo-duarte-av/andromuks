package net.vrkknn.andromuks

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore

/**
 * Handles push token registration with web client
 * This matches the pattern from the reference implementation
 */
class WebClientPushIntegration(private val context: Context) {
    
    companion object {
        private const val TAG = "WebClientPushIntegration"
        private const val PREFS_NAME = "web_client_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUSH_ENCRYPTION_KEY = "push_encryption_key"
        private const val KEY_LAST_PUSH_REG = "last_push_reg"
        private const val PUSH_REG_INTERVAL_HOURS = 12L
        
        // Encryption constants
        private const val KEY_ALIAS = "push_encryption_key"
        private const val KEY_SIZE = 256
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Store device ID from Gomuks Backend client_state
     */
    fun storeDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        Log.d(TAG, "Stored device ID: $deviceId")
    }
    
    /**
     * Get device ID from Gomuks Backend
     */
    fun getDeviceID(): String? {
        return prefs.getString(KEY_DEVICE_ID, null)
    }
    
    /**
     * Get push encryption key using Android Keystore (matches your reference implementation)
     */
    fun getPushEncryptionKey(): String {
        return try {
            val keyBytes = getOrCreatePushEncryptionKey()
            Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting push encryption key", e)
            // Fallback to simple key generation
            val fallbackKey = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_PUSH_ENCRYPTION_KEY, fallbackKey).apply()
            fallbackKey
        }
    }
    
    /**
     * Get or create push encryption key using Android Keystore
     * This matches your reference implementation pattern
     */
    private fun getOrCreatePushEncryptionKey(): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            if (keyStore.containsAlias(KEY_ALIAS)) {
                // Key exists, retrieve it
                val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
                key.encoded
            } else {
                // Generate new key
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE)
                    .build()
                
                keyGenerator.init(keyGenParameterSpec)
                val secretKey = keyGenerator.generateKey()
                secretKey.encoded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with Android Keystore, falling back to simple key generation", e)
            // Fallback to simple key generation
            val fallbackKey = UUID.randomUUID().toString().replace("-", "").toByteArray()
            prefs.edit().putString(KEY_PUSH_ENCRYPTION_KEY, Base64.encodeToString(fallbackKey, Base64.NO_WRAP)).apply()
            fallbackKey
        }
    }
    
    /**
     * Check if push registration is needed (based on time interval)
     */
    fun shouldRegisterPush(): Boolean {
        val lastReg = prefs.getLong(KEY_LAST_PUSH_REG, 0L)
        val now = System.currentTimeMillis()
        val intervalMs = PUSH_REG_INTERVAL_HOURS * 60 * 60 * 1000
        val timeSinceLastReg = now - lastReg
        
        Log.d(TAG, "shouldRegisterPush: lastReg=$lastReg, now=$now, timeSinceLastReg=$timeSinceLastReg ms, intervalMs=$intervalMs ms")
        
        val shouldRegister = timeSinceLastReg > intervalMs
        Log.d(TAG, "shouldRegisterPush: returning $shouldRegister")
        
        return shouldRegister
    }
    
    /**
     * Mark push registration as completed
     */
    fun markPushRegistrationCompleted() {
        val timestamp = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_PUSH_REG, timestamp).apply()
        Log.d(TAG, "Marked push registration as completed at timestamp: $timestamp")
    }
    
    /**
     * Create push registration message for web client
     * This matches the format from the reference implementation
     */
    fun createPushRegistrationMessage(token: String): JSONObject {
        return JSONObject(
            mapOf(
                "type" to "register_push",
                "device_id" to getDeviceID(),
                "token" to token,
                "encryption" to mapOf(
                    "key" to getPushEncryptionKey(),
                ),
            )
        )
    }
    
    /**
     * Create auth credentials message for web client
     * This matches the format from the reference implementation
     */
    fun createAuthCredentialsMessage(username: String, password: String): JSONObject {
        val basicAuth = Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        
        return JSONObject(
            mapOf(
                "type" to "auth",
                "authorization" to "Basic $basicAuth",
            )
        )
    }
    
    /**
     * Get stored credentials (if any)
     */
    fun getCredentials(): Triple<String, String, String>? {
        val homeserver = prefs.getString("homeserver_url", null)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        
        return if (homeserver != null && username != null && password != null) {
            Triple(homeserver, username, password)
        } else {
            null
        }
    }
    
    /**
     * Store credentials
     */
    fun storeCredentials(homeserver: String, username: String, password: String) {
        prefs.edit()
            .putString("homeserver_url", homeserver)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }
    
    /**
     * Clear stored credentials
     */
    fun clearCredentials() {
        prefs.edit()
            .remove("homeserver_url")
            .remove("username")
            .remove("password")
            .apply()
    }
    
    /**
     * Check if we have valid credentials
     */
    fun hasCredentials(): Boolean {
        return getCredentials() != null
    }
}
