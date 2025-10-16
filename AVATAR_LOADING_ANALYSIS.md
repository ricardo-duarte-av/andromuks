# Avatar Loading Analysis - Answering Your Questions

## Question 1: What is the cache key?

**Answer:** SHA-256 hash of the MXC URL

From `MediaCache.kt` lines 96-100:
```kotlin
fun getCacheKey(mxcUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(mxcUrl.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}
```

**Example:**
- MXC URL: `mxc://aguiarvieira.pt/dd96c417e21774d60e4ccbbf7bce925d0389fee61910715818316922880`
- Cache key: `0cf773cbe9cfba7a120285a6825a818970c2c3f58541ef4973215d35114090cf` (SHA-256 hash)
- Cache file: `/data/user/0/pt.aguiarvieira.andromuks/cache/media_cache/0cf773cbe9cfba7a120285a6825a818970c2c3f58541ef4973215d35114090cf`

## Question 2: Can cached MXC URLs be re-requested unnecessarily?

**Answer:** NO - The code checks cache first

The avatar loading flow in `AvatarUtils.getAvatarUrl()`:

```kotlin
fun getAvatarUrl(context: Context, mxcUrl: String?, homeserverUrl: String): String? {
    // ✅ STEP 1: Check if we have a cached file from MediaCache
    val cachedFile = if (mxcUrl != null) {
        MediaCache.getCachedFile(context, mxcUrl)
    } else null
    
    // ✅ STEP 2: If cached, return file path immediately (no conversion)
    if (cachedFile != null) {
        return cachedFile.absolutePath  // e.g., "/data/user/0/.../0cf773cb..."
    }
    
    // ✅ STEP 3: Only convert MXC → HTTP if NOT cached
    return mxcToHttpUrl(mxcUrl, homeserverUrl)
}
```

**Flow:**
1. `mxc://server/mediaId` → Check MediaCache
2. If found → Return file path (e.g., `/data/user/0/...`)
3. If NOT found → Convert to `https://backend/_gomuks/media/server/mediaId?thumbnail=avatar`
4. AsyncImage loads from file path or HTTP URL
5. Coil has its own memory/disk cache on top of MediaCache

**Caching Layers:**
1. **MediaCache** - Manual file-based cache (checked first)
2. **Coil Memory Cache** - In-memory bitmap cache (fastest)
3. **Coil Disk Cache** - Coil's own disk cache
4. **Network** - Download from server (slowest)

## Question 3: Are we wasting cycles rendering fallback?

**Answer:** YES - We were rendering colored background even when showing real avatars!

### The Issue (Before Final Fix)

From `AvatarImage.kt` line 86:
```kotlin
Box(
    modifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(backgroundColor ?: MaterialTheme.colorScheme.primaryContainer),  // ⚠️ ALWAYS rendered
    contentAlignment = Alignment.Center
) {
    if (avatarUrl == null || imageLoadFailed) {
        Text(fallbackLetter)  // Show text fallback
    } else {
        AsyncImage(...)  // Show real avatar
    }
}
```

**Problem:** The colored background is **always present**, even when showing the real avatar. This causes:
- Avatars with transparency show the colored circle through them
- Wasted rendering of background that's never needed
- Visual artifact where fallback "bleeds through" transparent avatars

### The Flow in RoomListScreen.kt

**Room Avatar (line 549-557):**
```kotlin
AvatarImage(
    mxcUrl = room.avatarUrl,           // ✅ MXC URL from room data
    homeserverUrl = homeserverUrl,     
    authToken = authToken,
    fallbackText = room.name,          // Used for letter extraction
    size = 48.dp,
    userId = room.id,                  // Used for color generation
    displayName = room.name            // Used for letter extraction
)
```

**Sender Avatar in Message Preview (line 652-660):**
```kotlin
AvatarImage(
    mxcUrl = senderAvatarUrl,          // ✅ From getUserProfile()
    homeserverUrl = homeserverUrl,
    authToken = authToken,
    fallbackText = senderDisplayName,
    size = 20.dp,                      // Smaller size for inline preview
    userId = room.messageSender,       // Sender's Matrix ID
    displayName = senderDisplayName
)
```

**Current User Avatar (line 215-223):**
```kotlin
AvatarImage(
    mxcUrl = me?.avatarUrl,            // ✅ From currentUserProfile
    homeserverUrl = appViewModel.homeserverUrl,
    authToken = authToken,
    fallbackText = me?.displayName ?: appViewModel.currentUserId,
    size = 40.dp,
    userId = appViewModel.currentUserId,
    displayName = me?.displayName
)
```

### What Happens in Each Case

**Case 1: Avatar is cached in MediaCache**
```
AvatarImage → getAvatarUrl() → getCachedFile() returns file path
           → avatarUrl = "/data/user/0/.../hash"
           → ❌ Background was rendered (FIXED NOW)
           → AsyncImage loads from file path instantly
           → ✅ Avatar shows WITHOUT fallback background
```

**Case 2: Avatar not in MediaCache, but in Coil cache**
```
AvatarImage → getAvatarUrl() → getCachedFile() returns null
           → mxcToHttpUrl() returns HTTP URL
           → avatarUrl = "https://backend/_gomuks/media/..."
           → ❌ Background was rendered (FIXED NOW)
           → AsyncImage loads from Coil cache instantly
           → ✅ Avatar shows WITHOUT fallback background
```

**Case 3: Avatar not cached anywhere**
```
AvatarImage → getAvatarUrl() → returns HTTP URL
           → ❌ Background was rendered (FIXED NOW)
           → AsyncImage starts downloading
           → ✅ No background during loading
           → onSuccess: Avatar appears cleanly
```

**Case 4: Avatar doesn't exist or fails to load**
```
AvatarImage → getAvatarUrl() → returns null or HTTP URL
           → imageLoadFailed = false initially
           → ✅ No background yet
           → AsyncImage fails
           → onError: imageLoadFailed = true
           → ✅ Background appears WITH fallback text
```

## Summary of Fixes

### Fix 1: Removed Preemptive Loading
- **Before:** `getAvatarUrlWithFallback()` tested image loading before returning URL
- **After:** `getAvatarUrl()` just checks cache and converts MXC → HTTP
- **Result:** No duplicate ImageLoader instances, no premature fallback generation

### Fix 2: Conditional Background Rendering
- **Before:** Background always rendered, even when showing real avatar
- **After:** Background only rendered when showing fallback (no URL or load failed)
- **Result:** Transparent avatars no longer show colored circle beneath them

### Fix 3: Fixed Media Caching Bug
- **Before:** `onSuccess` tried to cache file paths as HTTP URLs
- **After:** Only caches when `avatarUrl.startsWith("http")`
- **Result:** No more `MalformedURLException` when trying to download already-cached files

## Performance Implications

**Before fixes:**
1. Create new ImageLoader instance for each avatar
2. Execute synchronous image request to test loading
3. Generate SVG fallback if not in MediaCache
4. Render colored background always
5. AsyncImage loads real avatar on top
6. Transparent avatars show colored circle through them

**After fixes:**
1. Check MediaCache only (fast file lookup)
2. Convert MXC → HTTP if needed (string operation)
3. Render Box WITHOUT background
4. AsyncImage loads (uses Coil's caching)
5. Real avatar appears cleanly
6. Background only added if load fails

**Estimated savings per avatar:**
- Eliminated: 1 ImageLoader creation + 1 synchronous network request
- Eliminated: SVG generation for already-cached avatars
- Eliminated: Background rendering for successful loads
- Result: **Faster rendering, less flickering, correct transparency**

## Testing Checklist

- [ ] Cached avatars appear instantly without flash
- [ ] Transparent avatars don't show colored circle beneath them
- [ ] Failed/missing avatars show colored circle with letter
- [ ] No MalformedURLException errors in logs
- [ ] New avatars download and cache correctly
- [ ] Room avatars work in RoomListScreen
- [ ] User avatars work in message senders
- [ ] Current user avatar works in header

