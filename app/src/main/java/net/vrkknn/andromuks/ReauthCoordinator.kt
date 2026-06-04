package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.CredentialStore
import net.vrkknn.andromuks.utils.performHttpLogin
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recovers from an expired gomuks session token (HTTP 401) by silently re-logging in with the
 * credentials persisted by [CredentialStore], instead of dropping the user back to [LoginScreen].
 *
 * Two modes, chosen by the "Require biometric" setting:
 *  - **Silent** (setting off): re-login happens with no user interaction.
 *  - **Biometric** (setting on): re-login is deferred — the UI layer must run a
 *    [androidx.biometric.BiometricPrompt] and, on success, call [completeBiometricReauth], which
 *    decrypts the (non-auth-bound) credentials and re-logs in. See [AppViewModel.pendingBiometricReauth].
 *
 * A single global guard ([inProgress]) dedupes the per-ViewModel fan-out of `handleUnauthorizedError`
 * so only one re-login runs at a time, and a cooldown prevents tight 401 → re-auth → 401 loops.
 */
object ReauthCoordinator {
    private const val TAG = "ReauthCoordinator"
    private const val PREFS = "AndromuksAppPrefs"
    private const val COOLDOWN_MS = 5_000L

    private val inProgress = AtomicBoolean(false)
    @Volatile private var lastFailureMs = 0L

    private val client by lazy { OkHttpClient.Builder().build() }

    /**
     * Entry point from [AppViewModel.handleUnauthorizedError]. Returns true when a re-auth attempt
     * was started or is pending user authentication — in that case the caller must NOT clear the
     * session. Returns false when no recovery is possible (no stored credentials, or still in the
     * post-failure cooldown), signalling the caller to fall back to clear-and-login.
     */
    fun attempt(appViewModel: AppViewModel): Boolean {
        val context = appViewModel.appContext ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!CredentialStore.hasCredentials(prefs)) {
            Log.i(TAG, "No stored credentials; cannot silently re-auth")
            return false
        }

        // A recent failure means the password is likely genuinely invalid (changed/revoked).
        // Bail so the caller falls through to the login screen instead of looping.
        if (System.currentTimeMillis() - lastFailureMs < COOLDOWN_MS) {
            Log.w(TAG, "Within post-failure cooldown; not retrying re-auth")
            return false
        }

        if (!inProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Re-auth already in progress; suppressing duplicate 401")
            return true
        }

        if (prefs.getBoolean("require_biometric_unlock", false)) {
            // The user opted to authenticate before re-auth. Hand off to the UI to run a
            // BiometricPrompt; on success it calls completeBiometricReauth().
            Log.i(TAG, "Biometric required; requesting authentication from UI before re-auth")
            appViewModel.requestBiometricReauth()
            return true
        }

        startReauth(appViewModel, prefs)
        return true
    }

    /**
     * Completes a biometric-gated re-auth once the UI's BiometricPrompt has authenticated the user.
     * The credentials are decrypted here (Key B is not auth-bound), then re-login proceeds.
     */
    fun completeBiometricReauth(appViewModel: AppViewModel) {
        val context = appViewModel.appContext ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // attempt() already set inProgress for the biometric path.
        startReauth(appViewModel, prefs)
    }

    /** Called by the UI if the biometric prompt is cancelled/failed, releasing the guard. */
    fun cancelPendingReauth() {
        inProgress.set(false)
    }

    private fun startReauth(
        appViewModel: AppViewModel,
        prefs: android.content.SharedPreferences
    ) {
        val creds = CredentialStore.loadCredentials(prefs)
        if (creds == null) {
            Log.e(TAG, "Stored credentials present but decrypt failed; falling back to login")
            inProgress.set(false)
            appViewModel.clearCredentialsAndNavigateToLogin()
            return
        }
        val username = creds.username
        val password = creds.password
        val url = prefs.getString("homeserver_url", "") ?: ""
        if (url.isBlank()) {
            Log.e(TAG, "No homeserver_url for re-auth")
            inProgress.set(false)
            appViewModel.clearCredentialsAndNavigateToLogin()
            return
        }
        Log.i(TAG, "Starting silent re-auth for $username @ $url")
        appViewModel.logActivity("401 Unauthorized - Attempting silent re-auth", null)

        // performHttpLogin re-persists the new token (encrypted) and the credentials on success.
        performHttpLogin(
            url = url,
            username = username,
            password = password,
            client = client,
            scope = appViewModel.viewModelScope,
            sharedPreferences = prefs,
            onSuccess = {
                inProgress.set(false)
                lastFailureMs = 0L
                Log.i(TAG, "Silent re-auth succeeded; reconnecting WebSocket")
                appViewModel.logActivity("Silent re-auth succeeded", null)
                val newToken = CredentialStore.getAuthToken(prefs)
                appViewModel.reconnectAfterReauth(url, newToken)
            },
            onFailure = {
                inProgress.set(false)
                lastFailureMs = System.currentTimeMillis()
                Log.w(TAG, "Silent re-auth failed; clearing credentials and navigating to login")
                appViewModel.logActivity("Silent re-auth failed - clearing credentials", null)
                appViewModel.clearCredentialsAndNavigateToLogin()
            }
        )
    }
}
