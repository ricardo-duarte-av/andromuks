# Media Loading — Timeline Images & Videos

Covers how inline media (images, videos, stickers, audio, files) is loaded in `MediaContent` inside `MediaFunctions.kt`, and where `IntelligentMediaCache` does and does not apply.

---

## Two-cache split

| Path | Cache |
|---|---|
| Timeline media (inline images/videos in messages) | **Coil only** — memory + disk cache |
| Avatars (room list, chat headers) | **Coil only** — `CircleAvatarCache` for pre-clipped 48dp PNGs, `AvatarUtils.resolvedUrlCache` for O(1) URL lookup |
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

## `IntelligentMediaCache` — remaining scope

The file is still the authoritative disk store for **avatar images** (used by the notification system). Key properties:

- Keyed by `mxc://server/mediaId` (the raw MXC URL, no query params).
- `getCacheFile()` returns a `File` path without creating parent directories. Only `downloadAndCache()` calls `mkdirs()` — right before first write — so read and delete paths do not pay the filesystem cost.
- All public mutating methods hold `cacheMutex`. `getCachedFile()` also holds the lock for the in-memory index lookup and disk fallback check. This is intentional for avatar loads (which are fewer and from background coroutines), but would be a bottleneck for concurrent timeline loads — which is why timeline media no longer uses this class.

See [docs/NOTIFICATIONS.md](NOTIFICATIONS.md) for the full notification avatar load order. `IntelligentMediaCache` is populated by `ConversationsApi` (shortcut icons) and `PersonsApi` (contacts sync) — **not** by `AvatarImage.kt`, which uses Coil exclusively for UI avatar display.
