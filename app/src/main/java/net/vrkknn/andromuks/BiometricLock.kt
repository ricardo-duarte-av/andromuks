package net.vrkknn.andromuks

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Authenticators accepted by the app-lock / re-auth prompt. On API 30+ a strong biometric OR the
 * device credential (PIN/pattern/password) is allowed in a single prompt. On API < 30 that
 * combination is unsupported, so we allow strong biometric only and supply a negative button.
 */
private fun allowedAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

/** True when the device can satisfy [allowedAuthenticators] (enrolled biometric or device credential). */
fun canAuthenticate(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(allowedAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val TAG = "BiometricLock"

/**
 * Runs a [BiometricPrompt]. [onSuccess] fires on authentication; [onFail] fires on a terminal error
 * or user cancellation. Any exception from [BiometricPrompt.authenticate] (e.g. an unsupported
 * authenticator combination) is caught and routed to [onFail] so callers never wedge.
 */
fun runBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFail: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            Log.w(TAG, "Biometric error $errorCode: $errString")
            onFail()
        }
        // onAuthenticationFailed (a single non-matching attempt) is intentionally ignored — the
        // prompt stays up and the user can retry.
    })
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(allowedAuthenticators())
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // Required when only BIOMETRIC_* authenticators are allowed.
        builder.setNegativeButtonText("Cancel")
    }
    try {
        prompt.authenticate(builder.build())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch biometric prompt", e)
        onFail()
    }
}

/**
 * Wraps app content with two behaviours, both active only when [AppViewModel.requireBiometricUnlock]
 * is on and the device can authenticate:
 *
 *  1. **App lock** — a full-screen overlay shown on cold start, whenever the app returns to the
 *     foreground (process `ON_STOP` → `ON_START`), and immediately when the user enables the
 *     setting. The biometric/device-credential prompt is launched only while the activity is in the
 *     foreground (`ON_RESUME`), never from the background.
 *  2. **Re-auth gate** — registers [AppViewModel.setBiometricReauthCallback] so that when the session
 *     token expires, the user authenticates (with a subtitle making clear it is for *re-authentication*)
 *     before [ReauthCoordinator] re-logs in with the stored credentials.
 *
 * Excluded from chat-bubble windows by simply not wrapping [BubbleTimelineScreen].
 */
@Composable
fun BiometricLockGate(
    appViewModel: AppViewModel,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val prefs = remember(context) {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }

    // Read the setting synchronously from prefs rather than waiting on the async ViewModel load —
    // otherwise a cold start composes this gate before requireBiometricUnlock is loaded (default
    // false) and never locks. appViewModel.requireBiometricUnlock is observed only as a recomposition
    // signal for runtime toggles; the persisted value (updated in-memory immediately by apply()) is
    // the source of truth.
    val requireSignal = appViewModel.requireBiometricUnlock
    val require = remember(requireSignal) { prefs.getBoolean("require_biometric_unlock", false) }
    val deviceCanAuth = remember(context, require) { canAuthenticate(context) }
    val gateActive = require && deviceCanAuth && activity != null

    // Locked until the user authenticates this foreground session. Survives rotation.
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var promptInFlight by remember { mutableStateOf(false) }

    val gateActiveState = rememberUpdatedState(gateActive)
    val unlockedState = rememberUpdatedState(unlocked)

    fun launchUnlockPrompt() {
        if (activity == null || promptInFlight || !gateActiveState.value || unlockedState.value) return
        promptInFlight = true
        runBiometricPrompt(
            activity,
            title = "Unlock Andromuks",
            subtitle = "Authenticate to access your messages",
            onSuccess = { promptInFlight = false; unlocked = true },
            onFail = { promptInFlight = false }
        )
    }

    // Drive lock/unlock off the activity's own lifecycle (immediate, unlike the debounced
    // ProcessLifecycleOwner). ON_STOP fires the moment the activity is no longer visible, so a quick
    // background→foreground still re-locks. The promptInFlight guard ignores the ON_STOP that a
    // full-screen device-credential (PIN) prompt causes, so authenticating doesn't re-lock us.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!promptInFlight) unlocked = false
                Lifecycle.Event.ON_START -> launchUnlockPrompt()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Cover the cases the ON_START observer can miss: the initial foreground that already happened
    // before this effect registered (cold start) and a mid-session enable. If we are visible and
    // locked, prompt now.
    LaunchedEffect(gateActive, unlocked) {
        if (gateActive && !unlocked &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            launchUnlockPrompt()
        }
    }

    // Re-authentication handshake: prompt before ReauthCoordinator re-logs in on token expiry.
    DisposableEffect(activity, gateActive) {
        if (activity != null && gateActive) {
            appViewModel.setBiometricReauthCallback {
                runBiometricPrompt(
                    activity,
                    title = "Re-authenticate",
                    subtitle = "Your session expired. Authenticate to reconnect your account.",
                    onSuccess = {
                        ReauthCoordinator.completeBiometricReauth(appViewModel)
                        appViewModel.clearPendingBiometricReauth()
                    },
                    onFail = {
                        ReauthCoordinator.cancelPendingReauth()
                        appViewModel.clearPendingBiometricReauth()
                    }
                )
            }
        }
        onDispose { appViewModel.setBiometricReauthCallback(null) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (gateActive && !unlocked) {
            BiometricLockOverlay(onAuthenticate = { launchUnlockPrompt() })
        }
    }
}

@Composable
private fun BiometricLockOverlay(onAuthenticate: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Andromuks is locked",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAuthenticate) {
                Text("Unlock")
            }
        }
    }
}
