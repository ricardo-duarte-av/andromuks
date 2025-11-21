package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption utility for handling push notification payloads
 * Based on the implementation from your other app
 */
object Encryption {
    
    private const val TAG = "Encryption"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_IV_SIZE = 12 // 96 bits
    private const val GCM_TAG_SIZE = 16 // 128 bits
    
    /**
     * Create an Encryption instance from a plain key
     */
    fun fromPlainKey(key: ByteArray): EncryptionInstance {
        return EncryptionInstance(key)
    }
    
    /**
     * Generate a plain key for encryption
     */
    fun generatePlainKey(): ByteArray {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256) // 256-bit key
        val secretKey = keyGenerator.generateKey()
        return secretKey.encoded
    }
    
    /**
     * Encryption instance that can encrypt/decrypt data
     */
    class EncryptionInstance(private val key: SecretKey) {
        
        constructor(keyBytes: ByteArray) : this(SecretKeySpec(keyBytes, "AES"))
        
        /**
         * Encrypt input as ByteArray
         */
        fun encrypt(input: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(input)
            return cipher.iv + encrypted
        }
        
        /**
         * Encrypt input as String
         */
        fun encrypt(input: String): String {
            return Base64.encodeToString(encrypt(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        
        /**
         * Decrypt encrypted ByteArray
         */
        fun decrypt(encrypted: ByteArray): ByteArray {
            if (BuildConfig.DEBUG) Log.d(TAG, "Decrypting ByteArray of size: ${encrypted.size}")
            
            if (encrypted.size < GCM_IV_SIZE) {
                Log.e(TAG, "Encrypted data too short: ${encrypted.size} < $GCM_IV_SIZE")
                throw IllegalArgumentException("Encrypted data too short")
            }
            
            val iv = encrypted.sliceArray(0 until GCM_IV_SIZE)
            val actualEncrypted = encrypted.sliceArray(GCM_IV_SIZE until encrypted.size)
            
            if (BuildConfig.DEBUG) Log.d(TAG, "IV size: ${iv.size}, Encrypted data size: ${actualEncrypted.size}")
            if (BuildConfig.DEBUG) Log.d(TAG, "IV (first 4 bytes): ${iv.take(4).joinToString { "%02x".format(it) }}")
            
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE * 8, iv))  // Tag size in bits
            return cipher.doFinal(actualEncrypted)
        }
        
        /**
         * Decrypt encrypted String (base64-encoded)
         */
        fun decrypt(encrypted: String): String {
            if (BuildConfig.DEBUG) Log.d(TAG, "Decrypting String of length: ${encrypted.length}")
            if (BuildConfig.DEBUG) Log.d(TAG, "String first 50 chars: ${encrypted.take(50)}")
            
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            if (BuildConfig.DEBUG) Log.d(TAG, "Base64 decoded to ${decodedBytes.size} bytes")
            
            val decryptedBytes = decrypt(decodedBytes)
            val result = decryptedBytes.toString(Charsets.UTF_8)
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Decrypted result length: ${result.length}")
            if (BuildConfig.DEBUG) Log.d(TAG, "Decrypted result first 100 chars: ${result.take(100)}")
            
            return result
        }
    }
}
