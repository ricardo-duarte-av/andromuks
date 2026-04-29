# AuthCheck & Login — Startup Navigation Gate

This document covers the full startup flow: `LoginScreen` (first-run credential acquisition) and `AuthCheckScreen` (every-launch navigation gate).

---

## LoginScreen (`LoginScreen.kt`)

Shown when `AuthCheckScreen` finds no stored credentials. The user enters a homeserver URL, username, and password.

**Login flow:**

1. User submits the form → `performHttpLogin()` (`utils/NetworkUtils.kt`) fires an OkHttp `POST` to `<homeserver_url>/auth/login` (built by `buildAuthHttpUrl`).
2. On success the server returns `{ "token": "<gomuks session token>" }`.
3. Both values are persisted to `AndromuksAppPrefs` SharedPreferences:
   - `gomuks_auth_token` — the session cookie used for all subsequent WebSocket and media requests.
   - `homeserver_url` — the base URL used to construct WebSocket and media endpoints.
4. `navController.navigate("auth_check")` — control hands off to `AuthCheckScreen` to complete startup.

On failure (network error, bad credentials, unexpected JSON) `onFailure` is called; no credentials are saved and the user can retry.

---

## AuthCheckScreen (`AuthCheck.kt`)

The first Compose screen shown on every launch. It validates credentials, connects the WebSocket, and navigates to the correct destination once startup is complete. It is the sole orchestrator of the startup navigation flow.

### Responsibilities

- Read credentials (`gomuks_auth_token`, `homeserver_url`) from `AndromuksAppPrefs`; redirect to `LoginScreen` if absent.
- Check notification permission (Android 13+); redirect to `permissions` screen if missing.
- Display a startup loading screen with progress messages and an animated avatar-morph overlay.
- Register the **navigation callback** with `AppViewModel` that fires when startup is complete.
- Initiate or attach to the WebSocket connection (cold start vs. warm start).
- Navigate to the correct destination: `room_list`, `room_timeline/<id>`, `chat_bubble/<id>`, `simple_room_list`, or `user_info/<id>`.
- Guard against duplicate navigation with `navigationHandled` (composable-local `mutableStateOf`).
- Fall back to `room_list` after a 10-second timeout if the WebSocket never completes startup.

---

### Key LaunchedEffects

| Effect key | Purpose |
|---|---|
| `Unit` | Main startup coroutine: checks credentials, registers navigation callback, connects WebSocket. Runs once per composition. |
| `appViewModel.spacesLoaded, appViewModel.isStartupComplete, hasCredentials` | Early-exit path: if `spacesLoaded=true` and either offline or `isStartupComplete=true`, navigate from cache without waiting further. Skipped when network is up but WebSocket not yet fully started. |
| `hasCredentials` | 10-second timeout fallback: if `navigationHandled` is still `false` after 10 s, forces navigation to `room_list` regardless of WebSocket state. |

---

### Navigation Callback (`setNavigationCallback`)

Registered in `LaunchedEffect(Unit)` **before** `initializeWebSocketConnection()` is called, so it is always available when `AppViewModel.checkStartupComplete()` fires. The callback resolves the destination in priority order:

1. Pending share requiring room picker → `simple_room_list`
2. Direct room (from notification / FCM) → `room_timeline/<id>` (back-stack pops `auth_check`)
3. Pending bubble → `chat_bubble/<id>`
4. Pending room from shortcut → `room_list` (RoomListScreen auto-navigates on arrival)
5. Pending user info (matrix:u/ URI) → `user_info/<id>`
6. Default → `navigateToRoomListIfNeeded("default flow")`

---

### `navigateToRoomListIfNeeded(reason)`

Local helper that handles the nuances of navigating to `room_list`:

- If already on `room_list`, only clears `isLoading`.
- If `reason` is `"websocket already connected"` or `"default flow"`, force-navigates even if the current back-stack has `room_timeline/` or `chat_bubble/` (the user opened the app normally, not via a deep link).
- Otherwise skips navigation if already past `auth_check` (prevents double navigation).

---

### Startup Paths

**Cold start (WebSocket not connected, primary instance)**
`LaunchedEffect(Unit)` → `appViewModel.initializeWebSocketConnection(url, token)` → WebSocket connects → server sends `run_id`, then batched `sync_complete` messages, then `init_complete` → `AppViewModel.onInitComplete()` processes queued messages → `AppViewModel.checkStartupComplete()` passes all conditions → navigation callback fires → destination reached.

**Warm start (WebSocket already connected)**
`LaunchedEffect(Unit)` → `appViewModel.attachToExistingWebSocketIfAvailable()` → drain sentinel processed → navigation callback fires (or `navigateToRoomListIfNeeded` called inline) → destination reached.

**Deep-link / notification fast path**
WebSocket already connected **and** `getDirectRoomNavigation() != null` → skips the startup checklist UI entirely (`skipStartupScreenUi = true`, blank surface shown) → navigates directly to `room_timeline/<id>` via the navigation callback.

**Offline / cached fast path**
`LaunchedEffect(spacesLoaded, isStartupComplete, hasCredentials)` fires when `spacesLoaded=true` and network is `NONE` → navigates to `room_list` from cached data without waiting for WebSocket.

**10-second timeout**
`LaunchedEffect(hasCredentials)` waits 10 s; if `!navigationHandled`, forces `room_list` regardless of WebSocket state.

---

### `checkStartupComplete()` — the gate

`AuthCheckScreen` navigation is ultimately gated on `AppViewModel.checkStartupComplete()` returning `true`. All of the following must be satisfied simultaneously:

| Condition | Set by |
|---|---|
| `initializationComplete` | `onInitComplete()` finally block |
| `initialSyncComplete` | Early-unblock in message processing loop, or finally block |
| `initialSyncProcessingComplete` | `onInitComplete()` finally block after all queued messages are processed |
| `spacesLoaded` | Same as `initialSyncComplete` |
| `roomMap.isNotEmpty() \|\| initialSyncProcessingComplete` | Rooms parsed from sync_complete, or processing complete (zero-room account) |
| `currentUserProfile != null \|\| currentUserId.isBlank()` | `ensureCurrentUserProfileLoaded()` from cache or `get_profile` response |
| `allRoomStatesLoaded` | `loadAllRoomStatesAfterInitComplete()` when all uncached rooms' `get_room_state` responses arrive (or all rooms were already in `BridgeInfoCache`) |

---

### `navigationHandled` Guard

A composable-local `var navigationHandled by remember { mutableStateOf(false) }`. Set to `true` only when `navController.navigate("room_list")` is **actually called** (not merely attempted). This ensures:

- The `LaunchedEffect(spacesLoaded)` early path and the 10-second timeout do not race each other.
- If `currentRoute == null` (NavController back-stack not yet ready), `navigate()` is skipped and `navigationHandled` stays `false` so the timeout can retry.

---

### Interaction with AppViewModel Navigation Flags

| Flag | Set by | Cleared by |
|---|---|---|
| `navigationCallbackTriggered` | `checkStartupComplete()`, `attachToExistingWebSocketIfAvailable()`, `setNavigationCallback()` when `pendingNavigation=true` | `setNavigationCallback()` on every registration (reset so activity recreation re-arms it), `performQuickRefresh()`, `performFullRefresh()`, `clearOnUnauthorized()` |
| `pendingNavigation` | `checkStartupComplete()` when callback is null; `attachToExistingWebSocketIfAvailable()` when callback is null and `spacesLoaded=true` | `setNavigationCallback()` when it fires the pending callback |
| `onNavigateToRoomList` | `setNavigationCallback()` | Replaced on each call |

**Critical invariant:** `setNavigationCallback()` resets `navigationCallbackTriggered = false` on every call. This allows the ViewModel (retained across activity recreation) to fire the navigation callback again for the new Activity instance. Without this reset, a retained ViewModel with `navigationCallbackTriggered=true` would silently drop all future navigation attempts.

---

## Known Bugs (Fixed)

### 1. Silent navigation loss in `attachToExistingWebSocketIfAvailable`
**Symptom:** App stuck on startup after "Bridge info loaded for all rooms (from cache)".

**Root cause:** When `spacesLoaded=true` and `onNavigateToRoomList==null`, the old code set `navigationCallbackTriggered=true` but invoked nothing (`?.invoke()` on null). `pendingNavigation` was not set as a fallback, so the subsequent `setNavigationCallback()` call had no way to know navigation was needed.

**Fix:** Set `pendingNavigation=true` when the callback is null, so `setNavigationCallback()` fires it immediately when registered.

### 2. `navigationCallbackTriggered` not reset on activity recreation
**Symptom:** Same intermittent stuck startup; more likely after rotation or app-from-background.

**Root cause:** ViewModel is retained across activity recreation with `navigationCallbackTriggered=true`. New `AuthCheckScreen` calls `setNavigationCallback()`, but the guard blocked all paths.

**Fix:** `setNavigationCallback()` unconditionally resets `navigationCallbackTriggered=false` before checking `pendingNavigation`.

### 3. `navigationHandled=true` set even when `navController.navigate()` was skipped
**Symptom:** 10-second timeout fallback had no effect (stuck forever if NavController back-stack was not yet ready).

**Root cause:** In `LaunchedEffect(spacesLoaded)`, `navigationHandled=true` was set unconditionally after the `if (currentRoute != null && ...)` guard, so a `null` route blocked navigation but still armed the guard.

**Fix:** Move `navigationHandled=true` inside the `if` block so it is only set when `navigate()` was actually called.

### 4. `checkStartupComplete()` never re-called after `get_profile` response
**Symptom:** App stuck on AuthCheck showing "account data processed" as the last progress message. Resolves only after the 10-second timeout.

**Root cause:** Race between `loadAllRoomStatesAfterInitComplete()` and the `get_profile` network request. When all rooms are already cached (`BridgeInfoCache` hit for every room), `loadAllRoomStatesAfterInitComplete()` exits immediately and calls `checkStartupComplete()`. If `currentUserProfile` is still `null` at that moment (the `get_profile` response hasn't arrived yet), startup is blocked on the profile condition. When the response finally arrives, `handleProfileResponse` sets `currentUserProfile` but **did not call `checkStartupComplete()`**, so the startup gate was never re-evaluated.

**Fix:** `MemberProfilesCoordinator.handleProfileResponse` calls `checkStartupComplete()` after setting `currentUserProfile` for `currentUserId`.

### 5. No timeout protecting `allRoomStatesLoaded`
**Symptom:** App permanently stuck on AuthCheck if any `get_room_state` backend response is dropped (deleted room, transient network error, backend throttling).

**Root cause:** `loadAllRoomStatesAfterInitComplete()` waits indefinitely for every `pendingRoomStateResponses` entry to be removed. A single missing response keeps `allRoomStatesLoaded=false` forever, permanently blocking `checkStartupComplete()`.

**Fix:** After all `get_room_state` requests are dispatched, a 15-second polling loop waits for `pendingRoomStateResponses` to drain. If it still has entries at the deadline, `allRoomStatesLoaded` is force-set to `true` and `checkStartupComplete()` is called — the same path any successful response would have taken.

### 7. `navigateToRoomListIfNeeded` force-navigates away from room opened via notification tap

**Symptom:** Tapping an FCM notification correctly opens the room timeline, but a moment later (once the WebSocket finishes startup) the app redirects back to `RoomListScreen`.

**Root cause:** The notification tap flow stores the target room in `directRoomNavigation`, navigates to `room_timeline/<id>`, and then clears `directRoomNavigation`. When `checkStartupComplete()` later fires the navigation callback, it finds `directRoomNavigation == null` and falls through to `navigateToRoomListIfNeeded("default flow")`. That path has `shouldForceNavigation = true`, so it pops the back-stack back to `room_list` even though the user is already on the correct timeline.

**Fix:** Added `appViewModel.openedViaDirectNotification: Boolean` flag. All call sites that navigate to `room_timeline` as a result of a notification or shortcut tap (in `RoomListScreen` LaunchedEffects and in `RoomTimelineScreen`'s `LaunchedEffect(navTrigger)` handler) set this flag to `true` immediately before calling `navigateToRoomTimelineForExternalEntry`. `navigateToRoomListIfNeeded` checks the flag and bails out early (clearing `isLoading` but skipping the `popBackStack`) when it is `true`.

**Critical invariant:** Every call to `navController.navigateToRoomTimelineForExternalEntry(roomId)` that is triggered by a notification or shortcut **must** be preceded by `appViewModel.openedViaDirectNotification = true`. Missing even one site re-introduces the redirect-back-to-room_list race.

---

### 6. `currentUserProfile` startup gate has no timeout and no SharedPreferences cache
**Symptom:** App stuck on AuthCheck indefinitely on cold start. Logs show "BLOCKED - missing: profile" even after all sync messages are processed. Opening the user's own profile screen resolves it in that session but the bug recurs on next restart.

**Root cause:** Two compounding issues:
1. `ensureCurrentUserProfileLoaded()` only checked the in-process `ProfileCache` singleton (populated by a previous VM instance in the same process) and then fell through to a `get_profile` network request. Only the avatar MXC URL was persisted to SharedPreferences — the display name was never saved — so `ProfileCache` was always empty on a fresh process start and a network request was always required.
2. The `_events` SharedFlow (capacity 256, `DROP_OLDEST`) could drop the `get_profile` response when flooded by burst traffic (e.g. on first login with many `get_room_state` responses). With no timeout on the `profile` condition in `checkStartupComplete()`, a dropped response caused a permanent hang.

**Fix (AppViewModel / MemberProfilesCoordinator):**
- `persistCurrentUserDisplayNameIfChanged()` added alongside the existing `persistCurrentUserAvatarMxcIfChanged()`. Both are called from every `currentUserProfile` write path (`handleProfileResponse`, `m.room.member` state event, `ProfileCache` fast path).
- Key semantics: `""` = key present, field is genuinely blank; `null` (key absent) = never fetched, must request. Both persist functions now always call `putString` (never `remove`) so the key's presence is the fetch-complete signal.
- `ensureCurrentUserProfileLoaded()` checks SharedPreferences first (fast path 1) — if **both** `current_user_display_name` and `current_user_avatar_mxc` keys are non-null, `currentUserProfile` is populated immediately and `checkStartupComplete()` is called without waiting for the network. A background `requestUserProfile` still fires to refresh the data. If either key is absent the network path is taken as before.
- `SyncRepository._events` buffer raised from 256 to 1024 and overflow policy changed from `DROP_OLDEST` to `DROP_LATEST` so burst responses never evict already-queued earlier responses.
