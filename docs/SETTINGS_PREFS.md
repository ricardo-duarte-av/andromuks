# Settings & Preferences Architecture

## Overview

Preferences are stored at four scopes. Resolution walks from highest to lowest priority; the first explicitly set (non-null) value wins:

```
Room (this device)       SharedPreferences  key: gomuks_room_show_media_previews_<roomId>
Room (all devices)       Matrix room account data  fi.mau.gomuks.preferences / content
Global (this device)     SharedPreferences  key: gomuks_device_show_media_previews
Global (all devices)     Matrix account data  fi.mau.gomuks.preferences / content
Legacy fallback          AppViewModel.renderThumbnailsAlways (device toggle, pre-gomuks era)
```

"Default" (null) at any scope means "inherit from the next level down."

## Current settings

| Key | Type | Description |
|---|---|---|
| `show_media_previews` | `Boolean?` | Render images and videos inline; false shows only a BlurHash placeholder until tapped |
| `show_membership_events` | `Boolean?` | Show joins, leaves and display-name/avatar changes. Moderation events (invite, ban, kick) are unaffected. **Unset default is per-room-type:** shown in DMs, hidden in group rooms (all other prefs default to a flat fallback) |

## Reactivity model

`AppViewModel` exposes three `mutableStateOf` fields that Compose tracks:

- `accountGlobalShowMediaPreviews: Boolean?` — populated from Matrix account data on sync
- `deviceGlobalShowMediaPreviews: Boolean?` — populated from SharedPreferences in `loadSettings`
- `gomuksRoomPrefsVersion: Int` — incremented whenever a room-level pref changes (SharedPrefs write or optimistic cache update)

`resolveShowMediaPreviews(roomId: String?): Boolean` reads all five levels in order and returns the resolved value. Because it reads the three `mutableStateOf` fields it establishes Compose snapshot dependencies — any `@Composable` that calls this function will automatically recompose when a pref changes.

Room-level values come from SharedPrefs (`SettingsCoordinator`) and `RoomAccountDataCache` — neither is a `mutableStateOf`, so `gomuksRoomPrefsVersion` is the reactivity trigger for those two levels.

## Data flow

### Reads (startup / sync)

```
Matrix sync  →  SyncRoomsCoordinator.processAccountData
    fi.mau.gomuks.preferences content  →  AppViewModel.accountGlobalShowMediaPreviews

Matrix sync  →  SpaceRoomParser
    room account_data fi.mau.gomuks.preferences  →  RoomAccountDataCache.setRoomAccountData

SharedPreferences (loadSettings)  →  AppViewModel.deviceGlobalShowMediaPreviews
```

### Writes

| Scope | Write path | Storage |
|---|---|---|
| Global (all devices) | `AppViewModel.setGomuksGlobalPrefs` → `AccountDataCoordinator.setGomuksGlobalPrefs` | WebSocket `set_account_data` (no `room_id`) + updates `accountGlobalShowMediaPreviews` |
| Global (this device) | `AppViewModel.setDeviceGlobalShowMediaPreviews` → `SettingsCoordinator.setDeviceGlobalShowMediaPreviews` | SharedPrefs `gomuks_device_show_media_previews` + updates `deviceGlobalShowMediaPreviews` |
| Room (all devices) | `AppViewModel.setGomuksRoomPrefs` → `AccountDataCoordinator.setGomuksRoomPrefs` | WebSocket `set_account_data` (with `room_id`) + optimistic `RoomAccountDataCache` update + increments `gomuksRoomPrefsVersion` |
| Room (this device) | `AppViewModel.setDeviceRoomShowMediaPreviews` → `SettingsCoordinator.setDeviceRoomShowMediaPreviews` | SharedPrefs `gomuks_room_show_media_previews_<roomId>` + increments `gomuksRoomPrefsVersion` |

### Key preservation

Both `setGomuksGlobalPrefs` and `setGomuksRoomPrefs` use a read-modify-write pattern: they read all existing keys from `AccountDataCache` / `RoomAccountDataCache` first and copy them into the new content map, then apply only the managed key. This ensures unrecognised keys in `fi.mau.gomuks.preferences` (set by other clients) are preserved.

Setting a value to `null` (Default) removes the key from the content map entirely rather than writing `null` or `false`.

## Caches

- `AccountDataCache` — keyed by account data type. `fi.mau.gomuks.preferences` is stored as `{"content": {...}}`.
- `RoomAccountDataCache` — `ConcurrentHashMap<roomId, ConcurrentHashMap<type, JSONObject>>`. Same `{"content": {...}}` wrapping. Populated from `account_data` blocks in sync room state.

The content is always accessed via `data.optJSONObject("content") ?: data` to tolerate both formats.

## UI

### ClientPreferencesScreen

Route: `client_preferences`  
Entry: "Open Client Preferences" button in the Client Preferences section of `SettingsScreen` (grouped with the other screen-launcher sections).

Shows two `GomuksPreferenceCard`s — Global (all devices) and Global (this device).

### RoomPreferencesScreen

Route: `room_preferences/{roomId}` (roomId URL-encoded)  
Entry: "Room Preferences" button in `RoomInfo.kt`.

Shows two `GomuksPreferenceCard`s — Room (all devices) and Room (this device).

Room account state is loaded via `remember(roomId, roomPrefsVersion)` — the version key ensures the `remember` block re-runs and re-reads the cache whenever a room-level pref is saved.

### GomuksPreferenceCard

Reusable composable in `SettingsScreen.kt`. Renders a `Card` with a title, description, and a `SingleChoiceSegmentedButtonRow` with three buttons: **On** (`true`) / **Default** (`null`) / **Off** (`false`).

## Local UI preferences (AndromuksAppPrefs)

Separate from the 4-scope gomuks preferences above, a number of device-local UI toggles are stored in the `AndromuksAppPrefs` SharedPreferences file, surfaced as plain `mutableStateOf` fields on `AppViewModel`, written via `SettingsCoordinator`, and loaded in `SettingsCoordinator.loadSettings`. Examples: `trim_long_display_names`, `move_read_receipts_to_edge`, `show_all_room_list_tabs`.

### Crash reporting opt-in (`crash_reporting_enabled`)

| Key | Type | Default |
|---|---|---|
| `crash_reporting_enabled` | `Boolean` | `false` |

Controls Firebase Crashlytics collection. Automatic collection is **disabled in the manifest** (`firebase_crashlytics_collection_enabled = false`), so nothing is sent until the user opts in via the **Crash Reporting** section of `SettingsScreen`. That section also has a **Crash the app** test button (enabled only while reporting is on) that throws an uncaught `RuntimeException` to verify the pipeline — Crashlytics batches the report and uploads it on the next app launch, so reopen the app after it crashes.

- **State field:** `AppViewModel.crashReportingEnabled`.
- **Write:** `AppViewModel.setCrashReportingEnabled` → `SettingsCoordinator.setCrashReportingEnabled`, which persists the pref **and** calls `ErrorReportingCoordinator.setEnabled` (→ `FirebaseCrashlytics.setCrashlyticsCollectionEnabled`).
- **Startup:** `SettingsCoordinator.loadSettings` reloads the pref and calls `ErrorReportingCoordinator.applyPersistedState`, re-asserting it into Crashlytics on every launch so the SharedPref remains the single source of truth.
- **Reporting API:** `ErrorReportingCoordinator` has a companion `report(throwable, message?)` (logs an optional breadcrumb, then `recordException`), plus `log` (breadcrumbs) and `setKey` (custom keys). These are `companion object` functions so layers without an `AppViewModel` handle (`WebSocketService`, `SyncRepository`) can call them. All are no-ops while collection is disabled, so callers need no guard.
- **Instrumented call sites (non-fatal):** sync/connection failure points that already `Log.e` — `WebSocketService` (service-initiated reconnect, `sendCommand`, `connectWebSocket`), `SyncRepository` (`sync_complete` pipeline), and `AppViewModel` (incoming/ack message apply, the three `processInitialSyncComplete` crash paths, per-message `onInitComplete` crashes, `reconnectAfterReauth`). Uncaught crashes are captured automatically with no call site.
- **Deobfuscation:** R8 mapping-file upload is set explicitly (`mappingFileUploadEnabled = true` on `release`, `false` on `debug`) in `app/build.gradle.kts` so release stack traces are readable in the Crashlytics console.

### Display name color mode

| Key | Type | Values | Default |
|---|---|---|---|
| `displayname_color_mode` | `String` | `dynamic` / `fixed` / `theme` | `dynamic` |

Controls how sender display names are colored in the timeline (`RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, `EventContextScreen`) and reply previews.

- **State field:** `AppViewModel.displayNameColorMode: DisplayNameColorMode` (enum in `utils/UserColorUtils.kt`; `DisplayNameColorMode.fromPref` maps the stored string back, falling back to `DYNAMIC`).
- **Write:** `AppViewModel.setDisplayNameColorMode` → `SettingsCoordinator.setDisplayNameColorMode` (persists `mode.prefValue`).
- **UI:** "Display name colors" card in `SettingsScreen` → Room Timeline section, a `SingleChoiceSegmentedButtonRow` with **Dynamic / Fixed / Theme**.

The three modes are resolved by the `@Composable rememberUserColor(userID, appViewModel)` dispatcher in `utils/UserColorUtils.kt`, which reads the active `MaterialTheme.colorScheme` so the result reacts to dynamic color and light/dark:

- **Dynamic** (default) — per-user color via the HCT color space (`getUserColorHct`): the user ID hashes to a stable hue, chroma/tone are pinned to the scheme, then `harmonize`d toward `colorScheme.primary` (requires `com.materialkolor:material-kolor`).
- **Fixed** — per-user color from the fixed Catppuccin palette (`getUserColor`), matching the web app.
- **Theme** — no per-user color: `colorScheme.primary` for your own messages, `colorScheme.tertiary` for everyone else (decided by `appViewModel.currentUserId == userID`).

Avatar-fallback background colors (`AvatarUtils.getUserColor`) are a separate path and are **not** affected by this setting.

## Effect on media rendering

`MediaFunctions.kt` calls `appViewModel?.resolveShowMediaPreviews(event?.roomId) ?: true` to decide whether to load an image/video preview or stop at the BlurHash placeholder. The resolved value is recomputed on every recomposition, so changing a preference takes effect immediately without restarting the app.
