# Location Sharing (MSC3488)

Location sharing is implemented per [MSC3488](https://github.com/matrix-org/matrix-spec-proposals/blob/matthew/location/proposals/3488-location.md).

---

## Sending a location

### Wire format

Locations are sent as `m.room.message` events with `msgtype: m.location`, plus MSC3488 extension fields in the `extra` map of the `send_message` command:

```json
{
  "command": "send_message",
  "data": {
    "room_id": "!room:server",
    "base_content": {
      "msgtype": "m.location",
      "body": "Optional caption",
      "geo_uri": "geo:41.151,-8.609"
    },
    "extra": {
      "org.matrix.msc3488.asset": { "type": "m.pin" },
      "org.matrix.msc3488.location": {
        "uri": "geo:41.151,-8.609",
        "description": "Optional caption"
      }
    },
    "text": "",
    "mentions": { "user_ids": [], "room": false },
    "url_previews": []
  }
}
```

`body` and `org.matrix.msc3488.location.description` are set to the user-supplied caption, or left as `"Location"` / `""` when no caption is given.

### Code path

`MessageSendCoordinator.sendLocationMessage()` → `AppViewModel.sendLocationMessage()` → called from each screen's `LocationPickerOverlay` `onSendLocation` callback.

---

## UI — Location picker overlay

The picker is a two-phase full-screen overlay (`LocationPickerOverlay` in `LocationPickerScreen.kt`). It is rendered as an in-screen `Box` overlay rather than a separate nav destination, so the room timeline stays composed (and the WebSocket room subscription remains active) while the picker is open.

### Phase 1 — PICKING

- **Interactive Google Map** (`maps-compose`) with a fixed centre-pin that tracks the camera position. The user pan/zooms to position the pin.
- **Address / POI search** using `android.location.Geocoder` (no API key, uses Play Services). Search is debounced 400 ms. Results are shown both as a dropdown list and as `Marker` composables on the map. The camera animates to fit all result markers (`LatLngBounds`).
- **Location bias**: the Geocoder bounds-biased overload (`getFromLocationName(query, n, south, west, north, east)`) is called first with a ±1.5° box around the current map centre. Falls back to unbiased global search only if no local results are found.
- **POI tapping**: `map.setOnPoiClickListener` (set via `MapEffect` — `onPoiClick` was removed from `GoogleMap` composable params in maps-compose 4+) moves the camera to the tapped POI and populates the search field with the POI name.
- **GPS button**: jumps camera to the device's current GPS fix via `FusedLocationProviderClient`. Fires automatically on first open if `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` is granted.

### Phase 2 — PREVIEW

- Shows a **Google Maps Static API** thumbnail (loaded by Coil, cached to disk) of the chosen location.
- Displays the search query / POI name (or raw coordinates as fallback).
- **Caption text field** — typed value becomes both `body` and `org.matrix.msc3488.location.description` in the sent event.
- Send button in the top-right (or keyboard IME `Send` action) calls `onSendLocation`.

---

## UI — Rendering received locations

`m.location` events (detected by `msgType == "m.location"` in `RoomMessageContent`) are rendered by `RoomLocationMessageContent` in `TimelineEventItem.kt`, which wraps `LocationMessageContent` (`utils/LocationMessageContent.kt`) inside the standard `MessageBubbleWithMenu`.

`LocationMessageContent`:
- Loads a **Google Maps Static API** thumbnail via Coil (`buildStaticMapUrl(lat, lon, apiKey)`).
- Displays the `body` (caption) and coordinates below the thumbnail.
- Tapping opens `geo:` URI → any installed maps app; falls back to `maps.google.com`.
- Receives `contentColor` from the bubble palette (`bubbleColors.content`) so text always contrasts with the bubble background regardless of sender or theme.

---

## GCP setup required

| API | Used for |
|-----|----------|
| Maps SDK for Android | Interactive map in the picker |
| Maps Static API | Thumbnails in the picker preview and in the timeline |
| *(Geocoding API not needed)* | Search uses `android.location.Geocoder` via Play Services |

The Maps API key is stored in `app/src/main/res/values/strings.xml` as `google_maps_api_key` and referenced in `AndroidManifest.xml` via `com.google.android.geo.API_KEY`. The key must have an **Android app restriction** with the app's package name and signing certificate SHA-1 registered.

---

## Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Both are requested at runtime (via `rememberLauncherForActivityResult`) when the user taps the GPS button. The map and search work without location permission; only GPS jump and the `isMyLocationEnabled` blue-dot layer need it.

---

## Screens that support sending locations

| Screen | Overlay state var | Thread support |
|--------|------------------|----------------|
| `RoomTimelineScreen` | `showLocationPickerOverlay` | No (room-level only) |
| `BubbleTimelineScreen` | `showLocationPickerOverlay` | No |
| `ThreadViewerScreen` | `showLocationPickerOverlay` | Yes — passes `threadRootEventId` to `sendLocationMessage` |
