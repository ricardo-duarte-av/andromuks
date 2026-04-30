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
Entry: "Client Preferences" button at the top of `SettingsScreen`.

Shows two `GomuksPreferenceCard`s — Global (all devices) and Global (this device).

### RoomPreferencesScreen

Route: `room_preferences/{roomId}` (roomId URL-encoded)  
Entry: "Room Preferences" button in `RoomInfo.kt`.

Shows two `GomuksPreferenceCard`s — Room (all devices) and Room (this device).

Room account state is loaded via `remember(roomId, roomPrefsVersion)` — the version key ensures the `remember` block re-runs and re-reads the cache whenever a room-level pref is saved.

### GomuksPreferenceCard

Reusable composable in `SettingsScreen.kt`. Renders a `Card` with a title, description, and a `SingleChoiceSegmentedButtonRow` with three buttons: **On** (`true`) / **Default** (`null`) / **Off** (`false`).

## Effect on media rendering

`MediaFunctions.kt` calls `appViewModel?.resolveShowMediaPreviews(event?.roomId) ?: true` to decide whether to load an image/video preview or stop at the BlurHash placeholder. The resolved value is recomputed on every recomposition, so changing a preference takes effect immediately without restarting the app.
