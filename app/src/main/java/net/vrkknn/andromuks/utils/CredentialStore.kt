package net.vrkknn.andromuks.utils

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Encrypts sensitive auth material at rest using AES-256-GCM keys held in the Android Keystore.
 *
 * Two independent keys are used:
 *  - **Key A** ([TOKEN_KEY_ALIAS]) — wraps the gomuks session token.
 *  - **Key B** ([CRED_KEY_ALIAS]) — wraps the username + password for silent re-auth on token expiry.
 *
 * Both keys are non-auth-bound so they can be decrypted by background workers (FCM wake-ups,
 * WorkManager) where no user is present, and so silent re-auth works without UI. The "require
 * biometric" feature is enforced as a separate UI gate (a [androidx.biometric.BiometricPrompt] that
 * must pass before re-auth proceeds), not by binding these keys — see [ReauthCoordinator] and the
 * app-lock gate. This keeps re-auth and the settings toggle simple while still requiring a present,
 * authenticated user before re-authentication when the user opts in.
 *
 * The keys being non-exportable in the Android Keystore is the at-rest protection; the realistic
 * trust boundary is full-disk encryption plus the app sandbox.
 *
 * `homeserver_url` is intentionally left as plaintext in SharedPreferences: it is read in many
 * non-sensitive places and is not a secret.
 *
 * Ciphertext is stored as `Base64(iv):Base64(ciphertext)` in the same `AndromuksAppPrefs` store.
 * The GCM tag (128 bits) is appended to the ciphertext by the JCE provider.
 */
object CredentialStore {
    private const val TAG = "CredentialStore"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TOKEN_KEY_ALIAS = "andromuks_token_key_v1"
    private const val CRED_KEY_ALIAS = "andromuks_cred_key_v1"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val KEY_SIZE = 256

    // Ciphertext pref keys.
    private const val P_ENC_TOKEN = "enc_token"
    private const val P_ENC_CREDS = "enc_credentials"

    // Legacy plaintext key (pre-encryption builds wrote the token here).
    private const val LEGACY_TOKEN = "gomuks_auth_token"

    data class StoredCredentials(val username: String, val password: String)

    // In-memory cache of the decrypted token. A Keystore decrypt costs ~20 ms of disk I/O + crypto,
    // and getAuthToken() is called on hot, main-thread paths (sync processing, composition, image
    // loading), so without this every call would block the main thread and trip StrictMode. The
    // cache is the single source after first read; every write path below keeps it in sync, and
    // CredentialStore is the only writer of the token, so it can never go stale.
    @Volatile private var cachedToken: String? = null

    // ── Session token (Key A, never auth-bound) ──────────────────────────────

    /**
     * Returns the gomuks session token, decrypting the at-rest blob on first use and caching it.
     * Falls back to the legacy plaintext key so an in-flight migration (or a failed decrypt) never
     * strands callers that run in the background. Returns "" when no token is stored.
     */
    fun getAuthToken(prefs: SharedPreferences): String {
        cachedToken?.let { return it }
        synchronized(this) {
            cachedToken?.let { return it }
            val blob = prefs.getString(P_ENC_TOKEN, null)
            val token = if (!blob.isNullOrBlank()) {
                decrypt(getOrCreateKey(TOKEN_KEY_ALIAS), blob) ?: run {
                    Log.w(TAG, "Token decrypt failed; falling back to legacy plaintext if present")
                    prefs.getString(LEGACY_TOKEN, "") ?: ""
                }
            } else {
                prefs.getString(LEGACY_TOKEN, "") ?: ""
            }
            cachedToken = token
            return token
        }
    }

    /**
     * Whether a session token exists, **without** decrypting it (no Keystore access). Use this for
     * presence checks on hot paths instead of `getAuthToken(prefs).isNotEmpty()`.
     */
    fun hasAuthToken(prefs: SharedPreferences): Boolean {
        cachedToken?.let { return it.isNotEmpty() }
        return !prefs.getString(P_ENC_TOKEN, null).isNullOrBlank() ||
            !prefs.getString(LEGACY_TOKEN, null).isNullOrBlank()
    }

    /** Encrypts and stores the token under Key A, removing any legacy plaintext copy. */
    fun persistAuthToken(prefs: SharedPreferences, token: String) {
        val key = getOrCreateKey(TOKEN_KEY_ALIAS)
        val blob = encrypt(key, token)
        if (blob == null) {
            // Keystore unavailable — degrade to legacy plaintext rather than losing the session.
            Log.w(TAG, "Token encryption failed; storing legacy plaintext as fallback")
            prefs.edit().putString(LEGACY_TOKEN, token).remove(P_ENC_TOKEN).apply()
            cachedToken = token
            return
        }
        prefs.edit().putString(P_ENC_TOKEN, blob).remove(LEGACY_TOKEN).apply()
        cachedToken = token
    }

    /** Removes both the encrypted token and any legacy plaintext copy. */
    fun clearAuthToken(editor: SharedPreferences.Editor) {
        editor.remove(P_ENC_TOKEN)
        editor.remove(LEGACY_TOKEN)
        cachedToken = ""
    }

    /**
     * Warms [cachedToken] off the main thread so the first real read (often during composition or
     * sync processing on the main thread) is a cache hit. Safe to call from a background thread at
     * app startup.
     */
    fun warmTokenCache(prefs: SharedPreferences) {
        if (cachedToken == null) getAuthToken(prefs)
    }

    /**
     * One-shot migration: if a legacy plaintext token exists and no encrypted blob does, re-encrypt
     * it and drop the plaintext. Safe to call on every app start.
     */
    fun migratePlaintextTokenIfNeeded(prefs: SharedPreferences) {
        val legacy = prefs.getString(LEGACY_TOKEN, null)
        if (legacy.isNullOrBlank()) return
        if (!prefs.getString(P_ENC_TOKEN, null).isNullOrBlank()) {
            // Encrypted copy already present; just drop the stale plaintext.
            prefs.edit().remove(LEGACY_TOKEN).apply()
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Migrating legacy plaintext token to encrypted storage")
        persistAuthToken(prefs, legacy)
    }

    // ── Credentials (Key B) ──────────────────────────────────────────────────

    fun hasCredentials(prefs: SharedPreferences): Boolean =
        !prefs.getString(P_ENC_CREDS, null).isNullOrBlank()

    /** Encrypts and stores the login credentials for silent re-auth. Returns false on failure. */
    fun persistCredentials(
        prefs: SharedPreferences,
        username: String,
        password: String
    ): Boolean {
        val key = getOrCreateKey(CRED_KEY_ALIAS) ?: return false
        val json = JSONObject().apply {
            put("u", username)
            put("p", password)
        }.toString()
        val blob = encrypt(key, json) ?: return false
        prefs.edit().putString(P_ENC_CREDS, blob).apply()
        return true
    }

    /** Decrypts and returns the stored credentials, or null when absent / on decrypt failure. */
    fun loadCredentials(prefs: SharedPreferences): StoredCredentials? {
        val blob = prefs.getString(P_ENC_CREDS, null) ?: return null
        val key = getKey(CRED_KEY_ALIAS) ?: return null
        val json = decrypt(key, blob) ?: return null
        return parseCredentials(json)
    }

    fun clearCredentials(prefs: SharedPreferences) {
        prefs.edit().remove(P_ENC_CREDS).apply()
        deleteKey(CRED_KEY_ALIAS)
    }

    private fun parseCredentials(json: String): StoredCredentials? = try {
        val o = JSONObject(json)
        StoredCredentials(o.optString("u", ""), o.optString("p", ""))
    } catch (e: Exception) {
        Log.e(TAG, "Malformed credential JSON", e)
        null
    }

    // ── Keystore + crypto primitives ─────────────────────────────────────────

    /** Encrypts [plaintext] with a fresh random IV. Returns `Base64(iv):Base64(ct)` or null. */
    private fun encrypt(key: SecretKey?, plaintext: String): String? {
        if (key == null) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    /** Decrypts a `Base64(iv):Base64(ct)` blob with a non-auth-bound key. Returns null on failure. */
    private fun decrypt(key: SecretKey?, blob: String): String? {
        if (key == null) return null
        return try {
            val iv = Base64.decode(blob.substringBefore(':'), Base64.NO_WRAP)
            val ct = Base64.decode(blob.substringAfter(':'), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    private fun getKey(alias: String): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(alias, null) as? SecretKey
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load key $alias", e)
        null
    }

    private fun getOrCreateKey(alias: String): SecretKey? {
        getKey(alias)?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create key $alias", e)
            null
        }
    }

    private fun deleteKey(alias: String) {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete key $alias", e)
        }
    }
}
