package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
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
        private const val KEY_DEVICE_ID = "device_id" // Backend's device_id (for reference, not used for FCM)
        private const val KEY_LOCAL_DEVICE_ID = "local_device_id" // Unique per-device identifier for FCM
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
     * NOTE: This is stored for reference only. FCM registration uses getLocalDeviceID() instead.
     */
    fun storeDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        if (BuildConfig.DEBUG) Log.d(TAG, "Stored backend device ID: $deviceId (for reference only, FCM uses local device ID)")
    }
    
    /**
     * Get device ID from Gomuks Backend (for reference only)
     * NOTE: For FCM registration, use getLocalDeviceID() instead to ensure each device has a unique identifier.
     */
    fun getDeviceID(): String? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (BuildConfig.DEBUG) Log.d(TAG, "getDeviceID: returning backend device ID: $deviceId")
        return deviceId
    }
    
    /**
     * Get or generate a unique device identifier for FCM registration.
     * This ensures each Android device has a unique identifier that persists across app restarts.
     * CRITICAL FIX: Use this instead of backend's device_id to prevent one device from overwriting another's FCM registration.
     * 
     * The identifier is generated once per device installation and stored permanently.
     * It uses the same device identifier as the User-Agent (manufacturer + model) combined with a random component.
     * 
     * @return A unique device identifier (alphanumeric, uppercase, max 20 characters)
     */
    fun getLocalDeviceID(): String {
        // Check if we already have a local device ID
        val existingId = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
        if (existingId != null && existingId.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "getLocalDeviceID: Reusing existing local device ID: $existingId")
            return existingId
        }
        
        // Generate a new unique device identifier
        // Use the same device identifier as User-Agent (manufacturer + model)
        // This matches the format used in getUserAgent(): "Andromuks/1.0 - OnePlus GM1901"
        val deviceIdentifier = net.vrkknn.andromuks.utils.getDeviceIdentifier() // e.g., "OnePlus GM1901"
        
        // Format: replace spaces with underscores, remove special characters, uppercase
        // Example: "OnePlus GM1901" -> "ONEPLUS_GM1901"
        val baseId = deviceIdentifier
            .replace(" ", "_")
            .replace("/", "_")
            .replace("-", "_")
            .replace(Regex("[^A-Za-z0-9_]"), "")
            .uppercase()
            .take(15) // Limit base part to 15 chars
        
        // Add a random component to ensure uniqueness even for same-model devices
        val randomComponent = UUID.randomUUID().toString().replace("-", "").take(5).uppercase()
        
        // Combine: base identifier + random component (max 20 chars total)
        val uniqueId = "${baseId}_${randomComponent}".take(20)
        
        // Store it permanently (never changes for this device installation)
        prefs.edit().putString(KEY_LOCAL_DEVICE_ID, uniqueId).apply()
        if (BuildConfig.DEBUG) Log.d(TAG, "getLocalDeviceID: Generated new local device ID: $uniqueId (based on device: $deviceIdentifier, will persist for this device)")
        
        return uniqueId
    }
    
    /**
     * Get push encryption key using Android Keystore (matches your reference implementation)
     */
    fun getPushEncryptionKey(): String {
        return try {
            // First, check if we already have a key stored
            val existingKey = prefs.getString(KEY_PUSH_ENCRYPTION_KEY, null)
            if (existingKey != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Reusing existing key from SharedPreferences")
                if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Existing key (first 20 chars): ${existingKey.take(20)}...")
                return existingKey
            }
            
            // No existing key, generate a new one
            if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: No existing key found, generating new one")
            val keyBytes = getOrCreatePushEncryptionKey()
            val base64Key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Generated new key of size ${keyBytes.size} bytes")
            if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Key (first 8 bytes): ${keyBytes.take(8).joinToString { "%02x".format(it) }}")
            if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Base64 key: $base64Key")
            
            // Store the key in SharedPreferences for FCMService to use
            prefs.edit().putString(KEY_PUSH_ENCRYPTION_KEY, base64Key).apply()
            if (BuildConfig.DEBUG) Log.d(TAG, "getPushEncryptionKey: Stored new key in SharedPreferences: $base64Key")
            
            base64Key
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
            // Check if Android Keystore is available
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Android Keystore not available on this API level, using fallback")
                return generateFallbackKey()
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Attempting to use Android Keystore for encryption key")
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            if (keyStore.containsAlias(KEY_ALIAS)) {
                // Key exists, retrieve it
                if (BuildConfig.DEBUG) Log.d(TAG, "Retrieving existing key from AndroidKeyStore")
                val key = keyStore.getKey(KEY_ALIAS, null)
                if (key is SecretKey) {
                    val keyBytes = key.encoded ?: generateFallbackKey()
                    if (BuildConfig.DEBUG) Log.d(TAG, "Retrieved AndroidKeyStore key of size: ${keyBytes.size} bytes")
                    if (BuildConfig.DEBUG) Log.d(TAG, "AndroidKeyStore key (first 8 bytes): ${keyBytes.take(8).joinToString { "%02x".format(it) }}")
                    keyBytes
                } else {
                    Log.w(TAG, "Retrieved key is not a SecretKey, using fallback")
                    generateFallbackKey()
                }
            } else {
                // Generate new key
                if (BuildConfig.DEBUG) Log.d(TAG, "Generating new key in AndroidKeyStore")
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
                val keyBytes = secretKey.encoded ?: generateFallbackKey()
                if (BuildConfig.DEBUG) Log.d(TAG, "Generated new AndroidKeyStore key of size: ${keyBytes.size} bytes")
                if (BuildConfig.DEBUG) Log.d(TAG, "New AndroidKeyStore key (first 8 bytes): ${keyBytes.take(8).joinToString { "%02x".format(it) }}")
                keyBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with Android Keystore, falling back to simple key generation", e)
            generateFallbackKey()
        }
    }
    
    /**
     * Generate a fallback encryption key
     */
    private fun generateFallbackKey(): ByteArray {
        val fallbackKey = UUID.randomUUID().toString().replace("-", "").toByteArray()
        val base64Key = Base64.encodeToString(fallbackKey, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PUSH_ENCRYPTION_KEY, base64Key).apply()
        if (BuildConfig.DEBUG) Log.d(TAG, "Generated fallback encryption key of size ${fallbackKey.size} bytes")
        if (BuildConfig.DEBUG) Log.d(TAG, "Fallback key (first 8 bytes): ${fallbackKey.take(8).joinToString { "%02x".format(it) }}")
        if (BuildConfig.DEBUG) Log.d(TAG, "Fallback key stored as: $base64Key")
        return fallbackKey
    }
    
    /**
     * Check if push registration is needed (based on time interval)
     */
    fun shouldRegisterPush(): Boolean {
        val lastReg = prefs.getLong(KEY_LAST_PUSH_REG, 0L)
        val now = System.currentTimeMillis()
        val intervalMs = PUSH_REG_INTERVAL_HOURS * 60 * 60 * 1000
        val timeSinceLastReg = now - lastReg
        
        if (BuildConfig.DEBUG) Log.d(TAG, "shouldRegisterPush: lastReg=$lastReg, now=$now, timeSinceLastReg=$timeSinceLastReg ms, intervalMs=$intervalMs ms")
        
        val shouldRegister = timeSinceLastReg > intervalMs
        if (BuildConfig.DEBUG) Log.d(TAG, "shouldRegisterPush: returning $shouldRegister")
        
        return shouldRegister
    }
    
    /**
     * Mark push registration as completed
     */
    fun markPushRegistrationCompleted() {
        val timestamp = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_PUSH_REG, timestamp).apply()
        if (BuildConfig.DEBUG) Log.d(TAG, "Marked push registration as completed at timestamp: $timestamp")
    }
    
    /**
     * Create push registration message for web client
     * This matches the format from the reference implementation
     * CRITICAL FIX: Uses local device ID instead of backend device_id to ensure each device has unique registration
     */
    fun createPushRegistrationMessage(token: String): JSONObject {
        return JSONObject(
            mapOf(
                "type" to "register_push",
                "device_id" to getLocalDeviceID(), // Use local device ID, not backend's device_id
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
