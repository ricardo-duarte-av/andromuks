# Avatar Fallback Loading Fix

## Problem

Avatar images were showing faux/fallback avatars first, then loading the real avatar on top. This created a flash/flicker effect where users would see the colored circle with initials briefly before the actual avatar appeared, even when the avatar was already cached.

## Root Cause

In `AvatarUtils.kt`, the `getAvatarUrlWithFallback()` function was **preemptively testing** if avatars could be loaded:

```kotlin
suspend fun getAvatarUrlWithFallback(...): String {
    // Check MediaCache
    val cachedFile = MediaCache.getCachedFile(context, mxcUrl)
    if (cachedFile != null) return cachedFile.absolutePath
    
    // Convert MXC to HTTP
    val httpUrl = mxcToHttpUrl(mxcUrl, homeserverUrl)
    
    if (httpUrl != null) {
        // ⚠️ PROBLEM: Creates NEW ImageLoader and synchronously executes request
        val imageLoader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(httpUrl)
            .addHeader("Cookie", "gomuks_auth=$authToken")
            .build()
        
        when (val result = imageLoader.execute(request)) {
            is SuccessResult -> return httpUrl
            is ErrorResult -> {
                // Falls through to generate fallback
            }
        }
    }
    
    // ⚠️ PROBLEM: Immediately returns SVG fallback if not in MediaCache
    return generateLocalFallbackAvatar(displayName, userId)
}
```

### The Flow (Before Fix)

1. `AvatarImage` calls `getAvatarUrlWithFallback()` in `LaunchedEffect`
2. If avatar not in `MediaCache` (even if in Coil's memory/disk cache):
   - Creates a new `ImageLoader` instance
   - Synchronously executes the image request
   - If slow or fails, immediately returns SVG fallback
3. `avatarUrl` state is set to the SVG fallback data URI
4. Component renders the faux avatar (colored circle with letter)
5. Then `AsyncImage` loads the real avatar from Coil's cache
6. Real avatar is drawn on top → **flash/flicker effect**

## Solution

### 1. Simplified `getAvatarUrl()` Function

Replaced `getAvatarUrlWithFallback()` with a simple `getAvatarUrl()` that:
- Checks `MediaCache` for cached files
- Converts MXC URLs to HTTP URLs
- **Does NOT preemptively test loading**
- Returns `null` if no URL available (instead of generating fallback)

```kotlin
fun getAvatarUrl(
    context: Context,
    mxcUrl: String?,
    homeserverUrl: String
): String? {
    // Check MediaCache
    val cachedFile = if (mxcUrl != null) {
        MediaCache.getCachedFile(context, mxcUrl)
    } else null
    
    if (cachedFile != null) return cachedFile.absolutePath
    
    // Convert MXC to HTTP - let AsyncImage handle loading
    return mxcToHttpUrl(mxcUrl, homeserverUrl)
}
```

### 2. Improved `AvatarImage` Component

- Removed `LaunchedEffect` with async avatar loading
- Uses `remember` to compute avatar URL synchronously (no suspend calls)
- Tracks `imageLoadFailed` state to show fallback only on actual error
- Shows fallback immediately only if `avatarUrl == null`
- Let's `AsyncImage` naturally load from Coil's cache without interference

```kotlin
@Composable
fun AvatarImage(...) {
    // Get avatar URL without preemptive loading
    val avatarUrl = remember(mxcUrl) {
        AvatarUtils.getAvatarUrl(context, mxcUrl, homeserverUrl)
    }
    
    // Track if image failed to load
    var imageLoadFailed by remember(avatarUrl) { mutableStateOf(false) }
    
    Box(...) {
        if (avatarUrl == null || imageLoadFailed) {
            // Show fallback text only if no URL or load failed
            Text(text = fallbackLetter, ...)
        } else {
            // Try to load with AsyncImage
            AsyncImage(
                model = ...,
                onSuccess = { imageLoadFailed = false },
                onError = { imageLoadFailed = true }  // Show fallback on error
            )
        }
    }
}
```

### The Flow (After Fix)

1. `AvatarImage` calls `getAvatarUrl()` synchronously in `remember`
2. If avatar URL exists (cached file or HTTP URL):
   - Shows `AsyncImage` immediately
   - AsyncImage loads from Coil's cache (fast, synchronous for memory cache)
   - Real avatar appears immediately → **no flash/flicker**
3. If avatar URL is `null`:
   - Shows fallback text immediately
4. If `AsyncImage` fails to load:
   - Sets `imageLoadFailed = true`
   - Component recomposes and shows fallback text

## Benefits

1. ✅ **No preemptive loading** - Let Coil handle caching naturally
2. ✅ **No flash/flicker** - Cached avatars load instantly without showing fallback first
3. ✅ **Proper error handling** - Fallback only shown when actually needed
4. ✅ **Better performance** - No duplicate ImageLoader instances
5. ✅ **Cleaner code** - Simpler, more idiomatic Compose

## Files Changed

- `app/src/main/java/net/vrkknn/andromuks/utils/AvatarUtils.kt`
  - Replaced `getAvatarUrlWithFallback()` with simpler `getAvatarUrl()`
  - Removed preemptive image loading logic
  
- `app/src/main/java/net/vrkknn/andromuks/ui/components/AvatarImage.kt`
  - Removed `LaunchedEffect` with async loading
  - Added `imageLoadFailed` state tracking
  - Show fallback only when URL is null or load fails

## Testing

After this fix:
- Cached avatars should appear immediately without flashing fallback
- New avatars should load smoothly without showing fallback first
- Failed/missing avatars should show fallback colored circle with letter
- No regression in error handling or cache behavior

