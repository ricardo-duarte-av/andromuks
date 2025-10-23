# Shortcut Icon Fix - MediaCache Requirement

## Problem

After removing the manual MediaCache download from `AvatarImage.kt`, shortcuts lost their icons because the notification system specifically relies on `MediaCache` to load avatars.

## Root Cause

### Two Separate Caching Systems:

**1. Coil Cache (for UI)**
- Memory cache (fastest, in-memory bitmaps)
- Disk cache (fast, managed by Coil)
- Used by `AsyncImage` components in the UI
- Not accessible to notification system

**2. MediaCache (for Notifications)**
- File-based cache in app's cache directory
- SHA-256 hash filenames
- Accessible to notification system (background process)
- Used by `EnhancedNotificationDisplay.loadAvatarBitmap()`

### The Notification Flow:

```kotlin
// EnhancedNotificationDisplay.kt line 883-934
private suspend fun loadAvatarBitmap(avatarUrl: String): Bitmap? {
    // ⚠️ Checks MediaCache FIRST
    val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
    
    if (cachedFile != null) {
        return BitmapFactory.decodeFile(cachedFile.absolutePath)
    }
    
    // Not in MediaCache - download again
    val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
    // ...
}
```

**What Happened:**
1. User opens app → Avatars load via `AvatarImage` → Cached by Coil only
2. Notification arrives → Calls `loadAvatarBitmap()`
3. Checks `MediaCache.getCachedFile()` → Returns null (avatar only in Coil cache)
4. Tries to download → Either succeeds (slow) or fails → **No icon in shortcut**

## Solution

Restore MediaCache download but **only if not already cached**:

```kotlin
onSuccess = {
    imageLoadFailed = false
    // Cache to MediaCache for notification system access
    // Only if it's an HTTP URL and not already in MediaCache
    if (mxcUrl != null && avatarUrl.startsWith("http")) {
        coroutineScope.launch {
            // ✅ Check if already cached to avoid redundant downloads
            if (MediaCache.getCachedFile(context, mxcUrl) == null) {
                MediaCache.downloadAndCache(context, mxcUrl, avatarUrl, authToken)
                MediaCache.cleanupCache(context)
            }
        }
    }
}
```

## Why This Works

### First Time Avatar Loads:
```
1. getAvatarUrl() returns HTTP URL
2. AsyncImage loads from network
3. Coil caches to memory + disk
4. onSuccess fires
5. Check MediaCache → null
6. Download to MediaCache (might hit Coil's HTTP cache)
7. Future UI loads: Fast from Coil
8. Future notifications: Fast from MediaCache
```

### Subsequent Avatar Loads (Cached):
```
1. getAvatarUrl() checks MediaCache → Found!
2. Returns file path
3. AsyncImage loads from file (instant)
4. onSuccess fires
5. Check MediaCache → Already exists
6. Skip download (no redundant request)
```

### Why We Need Both Caches:

| Cache | Purpose | Accessible By | Format |
|-------|---------|---------------|--------|
| **Coil** | UI performance | UI thread, AsyncImage | Bitmap pool, disk cache |
| **MediaCache** | Notification/background | Any context, file system | Raw files with SHA-256 names |

**Key Insight:** The notification system runs in a **background context** where it can't access Coil's caches. It needs plain files it can load with `BitmapFactory.decodeFile()`.

## Performance Impact

### Worst Case (First Load):
- Coil downloads avatar (1 network request)
- MediaCache downloads avatar (might be from HTTP cache, not full network)
- **Still only 1 actual network request** if OkHttp is shared

### Best Case (Cached):
- MediaCache check hits → Return file path
- AsyncImage loads from file instantly
- No MediaCache download triggered (already exists)
- **0 network requests**

## Why Not Use Coil Cache Directly?

Coil's disk cache is:
1. Internal API (not public)
2. Uses proprietary format (not simple files)
3. Not accessible from background processes
4. Has complex lifecycle management

MediaCache provides:
1. Simple file-based storage
2. Direct file path access
3. Works from any context
4. Easy to query and clean up

## Files Changed

- `app/src/main/java/net/vrkknn/andromuks/ui/components/AvatarImage.kt`
  - Restored MediaCache download in `onSuccess`
  - Added check to prevent redundant downloads
  - Only downloads if not already in MediaCache

## Testing

After this fix:
- ✅ Shortcuts should show avatar icons correctly
- ✅ Notifications should show Person icons with avatars
- ✅ No duplicate network requests for cached avatars
- ✅ Avatars still load fast from Coil in UI
- ✅ Background processes can access avatars via MediaCache

