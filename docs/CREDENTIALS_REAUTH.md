# Secure Credentials, Silent Re-auth & Biometric Lock

This document covers how Andromuks stores auth material at rest, silently recovers from an expired
session token, and optionally gates the app behind biometric authentication.

## Problem

The gomuks session token (`gomuks_auth_token`) returned by `/auth/login` can expire. Previously the
token lived as **plaintext** in `AndromuksAppPrefs` SharedPreferences, and on a 401 the app wiped
credentials and dropped the user back to `LoginScreen` — re-entering the homeserver URL, username,
and password every time the token lapsed.

The goals:

1. Encrypt the token (and now the login credentials) at rest.
2. On token expiry, silently re-log in with stored credentials instead of forcing a manual login.
3. Optionally require biometric/device-credential authentication to open the app and before re-auth.

---

## `CredentialStore` (`utils/CredentialStore.kt`)

Encrypts sensitive auth material with **AES-256-GCM** keys held in the **Android Keystore** (keys are
non-exportable; the realistic trust boundary is full-disk encryption + the app sandbox).

| Key | Alias | Wraps | Auth-bound? |
|---|---|---|---|
| Key A | `andromuks_token_key_v1` | gomuks session token | No |
| Key B | `andromuks_cred_key_v1` | username + password (JSON blob) | No |

Both keys are **non-auth-bound** so background workers (FCM wake-ups, WorkManager) can decrypt where
no user is present, and so silent re-auth works without UI. `homeserver_url` is intentionally left
plaintext — it is read in many non-sensitive places and is not a secret.

Ciphertext is stored as `Base64(iv):Base64(ciphertext)` in `AndromuksAppPrefs` under `enc_token` and
`enc_credentials`. The 128-bit GCM tag is appended to the ciphertext by the JCE provider.

### In-memory token cache (StrictMode / performance)

A Keystore decrypt costs ~20 ms of disk I/O + crypto, and `getAuthToken()` is called on hot,
main-thread paths (sync processing, Compose composition, the Coil image interceptor). To avoid
blocking the main thread (and a StrictMode `DiskReadViolation` flood), the decrypted token is held in
a `@Volatile` in-memory cache:

- `getAuthToken(prefs)` decrypts once, then serves from cache. `CredentialStore` is the **only**
  writer of the token, so the cache can never go stale.
- `hasAuthToken(prefs)` is a **presence check with no decrypt** (just checks the blob exists) — use it
  for existence tests on hot paths instead of `getAuthToken(...).isNotEmpty()`. `SyncRepository.hasCredentials`
  uses this.
- `warmTokenCache(prefs)` decrypts off the main thread so the first real read is a cache hit.
  Called from `AndromuksApplication.onCreate` on `applicationScope` (`Dispatchers.Default`), right
  after `migratePlaintextTokenIfNeeded`.

### Migration

`migratePlaintextTokenIfNeeded(prefs)` re-encrypts any legacy plaintext `gomuks_auth_token` and drops
the plaintext. It runs **off the main thread** in `AndromuksApplication.onCreate`. `getAuthToken`
also falls back to the legacy plaintext key, so an in-flight migration (or a failed decrypt) never
strands callers.

### Token read sites

Every read of `gomuks_auth_token` across the app (services, WorkManager workers, screens,
`ImageLoaderSingleton`, `ExecApi`, …) routes through `CredentialStore.getAuthToken(prefs)`. The login
write path (`performHttpLogin`) and the 401-clear path (`AppViewModel.clearCredentialsAndNavigateToLogin`)
go through `persistAuthToken` / `clearAuthToken`. There are no remaining raw `getString("gomuks_auth_token")`
reads outside `CredentialStore`.

---

## Silent re-auth (`ReauthCoordinator.kt`)

On HTTP 401 (`NetworkUtils` WebSocket `onFailure`), `AppViewModel.handleUnauthorizedError()` now
delegates to `ReauthCoordinator.attempt(this)` **before** clearing the session:

1. **No stored credentials** → returns false → `clearCredentialsAndNavigateToLogin()` (legacy behaviour).
2. **Biometric required** (the "Require biometric" setting is on) → `appViewModel.requestBiometricReauth()`
   asks the UI to authenticate first; on success the UI calls `completeBiometricReauth()`.
3. **Otherwise (silent)** → decrypt credentials → `performHttpLogin` → on success swap the new token
   and `reconnectAfterReauth(url, token)` (drops the stale `ws_run_id` for a clean cold-start sync);
   on failure → `clearCredentialsAndNavigateToLogin()` (genuinely changed/revoked password).

A global `inProgress` guard dedupes the per-ViewModel fan-out of `handleUnauthorizedError`, and a 5 s
post-failure cooldown prevents tight 401 → re-auth → 401 loops.

---

## Biometric lock + re-auth gate (`BiometricLock.kt`)

`BiometricLockGate` wraps `MainActivity`'s nav graph (and the call overlays, so the lock covers them).
It is **not** wrapped around `BubbleTimelineScreen` / `ChatBubbleActivity`, so chat bubbles are never
locked. `MainActivity` is a `FragmentActivity` (required by `androidx.biometric.BiometricPrompt`).

Two behaviours, both active only when **`requireBiometricUnlock`** is on **and** the device can
authenticate (`canAuthenticate()` → strong biometric or device credential):

1. **App lock** — a full-screen overlay shown on cold start, on every foreground return, and
   immediately when the user enables the setting. Dismissed by a `BiometricPrompt`.
2. **Re-auth gate** — registers `AppViewModel.setBiometricReauthCallback`, so a token expiry prompts
   *"Your session expired. Authenticate to reconnect your account."* before `ReauthCoordinator`
   re-logs in. This is what enforces "authenticate **before** we re-authenticate".

### Design decision: UI gate, not key binding

The biometric requirement is enforced as a **UI gate**, not by binding Key B to user authentication.
Auth-bound keys would have been cryptographically stronger but break two things: background silent
re-auth (no user present during an FCM wake-up) and toggling the setting off (can't re-wrap without a
prompt). Keeping the keys non-auth-bound keeps re-auth and the toggle simple; biometric strength comes
from the gate. Tradeoff: credentials at rest are protected by the non-exportable Keystore key + app
sandbox + FDE, rather than a per-use biometric crypto binding.

### Robustness notes (learned the hard way)

- **Read the setting synchronously from prefs**, not the async-loaded `AppViewModel` field — otherwise
  a cold start composes the gate before `requireBiometricUnlock` loads (default false) and never locks.
  `appViewModel.requireBiometricUnlock` is observed only as a recomposition signal for runtime toggles.
- **Drive lock/unlock off the activity's own lifecycle**, not `ProcessLifecycleOwner` — the latter is
  debounced, so a quick background→foreground would skip the prompt. `ON_STOP` re-locks immediately;
  `ON_START` launches the prompt. A `promptInFlight` guard ignores the `ON_STOP` a full-screen PIN
  prompt causes (so authenticating doesn't bounce the lock back on).
- **Never launch the prompt from the background** — only on `ON_START`/while `STARTED`. Launching while
  stopped wedges `BiometricPrompt`.
- `runBiometricPrompt` wraps `authenticate()` in try/catch (routes to `onFail`) and adds a negative
  button on API < 30 (where `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is unsupported).

---

## Settings

`SettingsScreen` has a **Security** section with a "Require biometric unlock" switch, disabled (with
an explanation) when no biometric/lock is enrolled. The setting persists to `require_biometric_unlock`
and is exposed via `AppViewModel.setRequireBiometricUnlock` / `SettingsCoordinator`.

---

## Files

| File | Role |
|---|---|
| `utils/CredentialStore.kt` | Keystore AES-GCM encrypt/decrypt, token cache, migration |
| `ReauthCoordinator.kt` | Silent / biometric-gated re-login on 401 |
| `BiometricLock.kt` | `BiometricLockGate`, `runBiometricPrompt`, `canAuthenticate` |
| `AppViewModel.kt` | `handleUnauthorizedError`, `clearCredentialsAndNavigateToLogin`, `reconnectAfterReauth`, biometric callback + `requireBiometricUnlock` |
| `AndromuksApplication.kt` | Off-main migration + token cache warm |
| `utils/NetworkUtils.kt` | `performHttpLogin` persists encrypted token + credentials |
| `SettingsScreen.kt` / `SettingsCoordinator.kt` | Security section + setting persistence |
