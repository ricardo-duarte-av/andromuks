# Media Loading — Timeline Images & Videos

Covers how inline media (images, videos, stickers, audio, files) is loaded in `MediaContent` inside `MediaFunctions.kt`, and where `IntelligentMediaCache` does and does not apply.

---

## Two-cache split

| Path | Cache |
|---|---|
| Timeline media (inline images/videos in messages) | **Coil only** — memory + disk cache |
| Avatars (room list, chat headers, timeline, room info) | **Coil only** — general memory + disk cache, keyed by the immutable `"${mxc}@${size}"`. See [Avatar loading](#avatar-loading) below. |
| Android Launcher shortcuts / conversation icons | `IntelligentMediaCache` via `ConversationsApi` |
| Notification avatars (FCM, offline) | `IntelligentMediaCache` via `EnhancedNotificationDisplay` / `NotificationImageWorker` |

`IntelligentMediaCache` is **not** in the `MediaContent` code path. Coil's built-in disk and memory caches are sufficient for timeline media, and adding a second layer causes the spurious HTTP request described below.

---

## `MediaContent` composable — key invariants

### Stable `ImageRequest` keys

`imageUrl` is computed once per MXC URL change:

```kotlin
val imageUrl = remember(displayMxcUrl, displayIsEncrypted) {
    MediaUtils.mxcToThumbnailUrl(…, registerMapping = false) ?: …
}
```

`ImageRequest` is keyed only on values that change the actual request:

```kotlin
val imageRequest = remember(imageUrl, bypassCoilCache, authToken) { … }
```

**Never include volatile state (scroll position, scroll speed, visibility flags) in these `remember` keys.** Doing so causes Coil to cancel and restart every in-flight network request on each state change. Coil's own memory cache already serves already-loaded items instantly with no gating needed on the Compose side.

### One HTTP request per image

`imageUrl` is built synchronously inside `remember{}`. Coil receives it on the first composition frame and starts loading. If the item is already in Coil's disk or memory cache, Coil serves it without a network round-trip.

The previous design used a `LaunchedEffect` to check `IntelligentMediaCache` asynchronously. This caused a race: Coil started an HTTP request on frame 1, then the `LaunchedEffect` completed and changed `imageUrl` to a file path, triggering a second Coil request (and cancelling the first). That extra round-trip is now eliminated.

### `bypassCoilCache` retry

On `onError`, `bypassCoilCache` is set to `true`. This changes the `remember` key for `ImageRequest`, rebuilding it with `CachePolicy.DISABLED` for both memory and disk — forcing a fresh backend fetch. The flag resets when `displayMxcUrl` changes (i.e. a different image is shown in the same slot).

### URL mapping registration

`CoilUrlMapper.registerMapping` (needed for the cache gallery reverse-lookup) is a side effect and must not run inside `remember{}`. It is registered via `LaunchedEffect(imageUrl, displayMxcUrl)`, which fires once per URL pair change, not on every recomposition.

### Video thumbnail BlurHash

The outer `videoBlurHashPainter` (decoded once for the pre-reveal placeholder) is reused as `thumbnailBlurHashPainter` for the thumbnail's placeholder/error painter. There is intentionally only one `LaunchedEffect` BlurHash decode per video message regardless of whether a thumbnail URL is present.

---

## `/_gomuks/media/*` authentication

The backend accepts two authentication methods for media endpoints (in addition to the `Cookie: gomuks_auth=<session_token>` used by the Coil interceptor):

| Method | Form |
|---|---|
| Authorization header | `Authorization: Image <token>` |
| Query parameter | `?image_auth=<token>` |

`<token>` is the `image_auth_token` value delivered by the WebSocket on every connect and refreshed periodically. It is a short-lived JWT (`{"username":…,"expiry":…,"image_only":true}`) distinct from the session cookie.

**Current Android implementation** uses `Cookie: gomuks_auth=<session_token>` (the long-lived session cookie from `/_gomuks/auth`), which the backend also accepts and which the web frontend uses for all media requests. The `image_auth_token` JWT is persisted to SharedPreferences only for FCMService / NotificationImageWorker notification image downloads, which use their own OkHttp clients.

---

## `IntelligentMediaCache` — remaining scope

The file is still the authoritative disk store for **avatar images** (used by the notification system). Key properties:

- Keyed by `mxc://server/mediaId` (the raw MXC URL, no query params).
- `getCacheFile()` returns a `File` path without creating parent directories. Only `downloadAndCache()` calls `mkdirs()` — right before first write — so read and delete paths do not pay the filesystem cost.
- All public mutating methods hold `cacheMutex`. `getCachedFile()` also holds the lock for the in-memory index lookup and disk fallback check. This is intentional for avatar loads (which are fewer and from background coroutines), but would be a bottleneck for concurrent timeline loads — which is why timeline media no longer uses this class.

See [docs/NOTIFICATIONS.md](NOTIFICATIONS.md) for the full notification avatar load order. `IntelligentMediaCache` is populated by `ConversationsApi` (shortcut icons) and `PersonsApi` (contacts sync) — **not** by `AvatarImage.kt`, which uses Coil exclusively for UI avatar display.

---

## Avatar loading

`AvatarImage.kt` renders every UI avatar (room list, chat headers, timeline senders, room info) through Coil's **general** memory + disk cache. There is no separate avatar cache.

- **URL:** `AvatarUtils.getAvatarUrl` returns the http(s) MXC URL (`mxcToHttpUrl`), with a single query param `?thumbnail=avatar`. It deliberately carries **no `&size=`** (the backend ignores it) and **no `&fallback=color:letter`** (see Fallback below for why). The request sets an explicit `memoryCacheKey`/`diskCacheKey` of `"${mxc}@${targetPixelSize}"` — MXC content is immutable, so this is stable and bypasses Coil's `FileKeyer` (which would otherwise do a `File.lastModified()` disk read on Main during composition). The pixel size lives only in the cache key (so different render sizes keep separate bitmaps), not in the URL — every render size fetches the same `?thumbnail=avatar` URL.
- **Display:** hardware bitmaps (no `allowHardware(false)` on the display path) → textures live in GPU memory; the circle is a render-time `.clip(CircleShape)` (convex outline clip, no offscreen layer).
- **Size:** `targetPixelSize` is display-driven, min 64px; `useCircleCache = true` caps it at 256px (list/timeline). `useCircleCache` no longer selects any cache — it only gates that cap (full-size avatars pass `false` and stay uncapped).
- **Fallback:** null `mxcUrl` or a load failure renders a native Compose `Text` initial over a solid-fill `Box` (color from `getUserColor`, letter from `getFallbackCharacter`). No SVG: `SvgDecoder` was removed, as avatar fallbacks were its only consumer. This local `Text` path is now the **only** fallback — see below.

  **Why no server-drawn `&fallback=`:** the URL used to append `&fallback=color:letter`, which made the backend return an HTTP **200** colored-initial image when the underlying media was unavailable (transient 404 / federation hiccup). Coil disk-caches 2xx responses (but not 4xx), so that placeholder got written under the real media's cache key `"${mxc}@${px}"` and was served **forever** — the avatar stayed stuck on initials even after the media came back, surviving app restarts (Coil's disk cache persists; re-fetching `m.room.member` only refreshes the profile string, never Coil's bitmap). It also presented device-specifically (only the device whose slot loaded during the blip was poisoned) and looked identical to a normal missing-avatar placeholder, so it was easy to misread as a profile-data bug. Dropping `&fallback=` means a genuinely-unavailable avatar now returns a **404 → Coil `onError` → local `Text` fallback**; the 404 is not disk-cached, so the slot self-heals the moment the media returns. Devices already holding a poisoned 200 entry need a one-time **Clear cache** to evict it.
- **Preload:** `RoomListScreen` warms the first ~100 rooms' avatars after `initialSyncComplete`, using the same http URL + `"${mxc}@${px}"` key so the warmed slot is the one each row later hits.

### Why `CircleAvatarCache` was removed

A former `CircleAvatarCache` stored a redundant 128px square WebP per avatar (the name was misleading — it wasn't circular; the circle was always a render-time clip) and required a synchronous-miss → IO-resolve → recompose `file://` flip on cold start. Its only RAM rationale was decoding at 128px vs the display size — but its generation pass issued a **second** `imageLoader.execute()` per avatar (software `ARGB_8888`, no explicit cache key) that Coil cached under its default key: a second strong-referenced bitmap per avatar in a memory cache budgeted at 35% of heap. Removing it (and pointing avatars at the general cache) measured **−17 MB graphics / −158 MB total PSS** after a full room-list scroll on a ~620-room account, plus eliminated the second disk copy and the CPU thumbnail-generation. The `useCircleCache` parameter is retained only for the size cap described above.
