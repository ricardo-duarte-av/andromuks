# AuthCheck.kt — Startup Screen & Navigation Gate

`AuthCheckScreen` is the first Compose screen shown after launch. It validates credentials, connects the WebSocket, and navigates to the correct destination once startup is complete. It is the sole orchestrator of the startup navigation flow.

## Responsibilities

- Read credentials (`gomuks_auth_token`, `homeserver_url`) from `AndromuksAppPrefs` and redirect to `LoginScreen` if absent.
- Check notification permission (required); redirect to `permissions` screen if missing.
- Display a startup loading screen with progress messages and an animated avatar morph overlay.
- Register the **navigation callback** with `AppViewModel` that fires when `init_complete` is received.
- Initiate or attach to the WebSocket connection (cold start vs. warm start).
- Navigate to the correct destination: `room_list`, `room_timeline/<id>`, `chat_bubble/<id>`, `simple_room_list`, or `user_info/<id>`.
- Guard against duplicate navigation with `navigationHandled` (composable-local `mutableStateOf`).
- Fall back to `room_list` after a 10-second timeout if the WebSocket never completes startup.

## Key LaunchedEffects

| Effect key | Purpose |
|---|---|
| `Unit` | Main startup coroutine: checks credentials, registers navigation callback, connects WebSocket. Runs once per composition. |
| `appViewModel.spacesLoaded, hasCredentials` | Early-exit path: if rooms are already cached (`spacesLoaded=true`) and the network is down (offline mode), navigate to `room_list` without waiting for WebSocket. Skipped when network is up but WebSocket not yet connected. |
| `hasCredentials` | 10-second timeout fallback: if `navigationHandled` is still false after 10 s, forces navigation to `room_list`. |

## Navigation Callback (`setNavigationCallback`)

Registered in `LaunchedEffect(Unit)` **before** `initializeWebSocketConnection()` is called, so it is always available when `onInitComplete()` fires. The callback resolves the destination in priority order:

1. Pending share requiring room picker → `simple_room_list`
2. Direct room (from notification / FCM) → `room_timeline/<id>`  (back-stack pops `auth_check`)
3. Pending bubble → `chat_bubble/<id>`
4. Pending room (from shortcut) → `room_list` (RoomListScreen auto-navigates)
5. Pending user info → `user_info/<id>`
6. Default → `navigateToRoomListIfNeeded("default flow")`

## `navigateToRoomListIfNeeded(reason)`

Local helper that handles the nuances of navigating to `room_list`:

- If already on `room_list`, only clears `isLoading`.
- If `reason` is `"websocket already connected"` or `"default flow"`, force-navigates even if the current back-stack has `room_timeline/` or `chat_bubble/` (the user opened the app normally, not via a deep link).
- Otherwise skips navigation if already past `auth_check` (prevents double navigation).

## Startup Paths

### Cold start (WebSocket not connected, primary instance)
`LaunchedEffect(Unit)` → `appViewModel.initializeWebSocketConnection(url, token)` → WebSocket connects → server sends `init_complete` → `AppViewModel.onInitComplete()` → navigation callback fires → `navigateToRoomListIfNeeded("default flow")`.

### Warm start (WebSocket already connected)
`LaunchedEffect(Unit)` → `appViewModel.attachToExistingWebSocketIfAvailable()` → navigation callback fires (or `navigateToRoomListIfNeeded` called inline) → destination reached.

### Deep-link / notification fast path
WebSocket already connected **and** `getDirectRoomNavigation() != null` → skips the startup checklist UI entirely (`skipStartupScreenUi = true`) → navigates directly to `room_timeline/<id>`.

### Offline / cached fast path
`LaunchedEffect(spacesLoaded, hasCredentials)` fires when `spacesLoaded=true` and network is `NONE` → navigates to `room_list` from cache data without waiting for WebSocket.

### 10-second timeout
`LaunchedEffect(hasCredentials)` waits 10 s; if `!navigationHandled`, forces `room_list` regardless of WebSocket state.

## `navigationHandled` Guard

A composable-local `var navigationHandled by remember { mutableStateOf(false) }`. Set to `true` only when `navController.navigate("room_list")` is **actually called** (not merely attempted). This ensures:

- The `LaunchedEffect(spacesLoaded)` early path and the 10-second timeout do not fight each other.
- If `currentRoute == null` (NavController backstack not ready), navigate is skipped and `navigationHandled` stays `false` so the timeout can retry.

## Interaction with AppViewModel Navigation Flags

`AuthCheckScreen` interacts with three flags on `AppViewModel`:

| Flag | Set by | Cleared by |
|---|---|---|
| `navigationCallbackTriggered` | `onInitComplete()`, `attachToExistingWebSocketIfAvailable()`, `setNavigationCallback()` when `pendingNavigation=true` | `setNavigationCallback()` on every registration (reset so activity recreation works), `performQuickRefresh()`, `performFullRefresh()`, `clearOnUnauthorized()` |
| `pendingNavigation` | `onInitComplete()` when callback is null; `attachToExistingWebSocketIfAvailable()` when callback is null and `spacesLoaded=true` | `setNavigationCallback()` when it fires the callback |
| `onNavigateToRoomList` | `setNavigationCallback()` | `setNavigationCallback()` replaces it on each call |

**Critical invariant:** `setNavigationCallback()` resets `navigationCallbackTriggered = false` on every call. This allows the ViewModel (retained across activity recreation) to fire the navigation callback again in the new Activity instance. Without this reset, a retained ViewModel with `navigationCallbackTriggered=true` would silently drop all future navigation attempts.

## Known Past Bugs (Fixed)

### 1. Silent navigation loss in `attachToExistingWebSocketIfAvailable`
**Symptom:** App stuck on startup after "Bridge info loaded for all rooms (from cache)".

**Root cause:** When `spacesLoaded=true` and `onNavigateToRoomList==null`, the old code set `navigationCallbackTriggered=true` but invoked nothing (`?.invoke()` on null). `pendingNavigation` was not set as a fallback, so the subsequent `setNavigationCallback()` call had no way to know navigation was needed.

**Fix:** Set `pendingNavigation=true` when the callback is null, so `setNavigationCallback()` fires it immediately when registered.

### 2. `navigationCallbackTriggered` not reset on activity recreation
**Symptom:** Same intermittent stuck startup; more likely after rotation or app-from-background.

**Root cause:** ViewModel is retained across activity recreation with `navigationCallbackTriggered=true`. New `AuthCheckScreen` calls `setNavigationCallback()`, but the guard blocked all paths.

**Fix:** `setNavigationCallback()` unconditionally resets `navigationCallbackTriggered=false` before checking `pendingNavigation`.

### 3. `navigationHandled=true` set even when `navController.navigate()` was skipped
**Symptom:** 10-second timeout fallback had no effect (stuck forever if NavController backstack was not yet ready).

**Root cause:** In `LaunchedEffect(spacesLoaded)`, `navigationHandled=true` was set unconditionally after the `if (currentRoute != null && ...)` guard, so a `null` route blocked navigation but still armed the guard.

**Fix:** Move `navigationHandled=true` inside the `if` block so it is only set when `navigate()` was actually called.
