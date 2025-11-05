# Media and Profile Caching System

## Overview

This document explains the caching systems used for media files (images, videos, etc.) and user profiles in Andromuks. The app uses multiple caching layers to optimize performance and reduce network usage.

---

## Profile Caching

### Architecture

Profile caching uses a **two-tier system**:

1. **Memory Cache** - Fast, in-memory storage for active profiles
2. **Disk Cache** - Persistent SQLite database for profile persistence

### Memory Cache

#### Components

The memory cache consists of three collections:

- **`flattenedMemberCache`** (`ConcurrentHashMap<String, MemberProfile>`)
  - Key format: `"roomId:userId"` (e.g., `"!abc123:matrix.org:@alice:example.com"`)
  - Stores room-specific profiles (different display names/avatars per room)
  - Only stores entries when profile differs from global profile (optimization)

- **`globalProfileCache`** (`ConcurrentHashMap<String, WeakReference<MemberProfile>>`)
  - Key: `userId` (e.g., `"@alice:example.com"`)
  - Stores global profiles (canonical profile from explicit profile requests)
  - Uses weak references to allow garbage collection

- **`roomMemberCache`** (`Map<String, Map<String, MemberProfile>>`)
  - Legacy cache structure (deprecated but maintained for compatibility)
  - Nested map: `roomId -> userId -> profile`

#### Profile Lookup Strategy

When requesting a profile via `getMemberProfile(roomId, userId)`:

1. **Check room-specific cache first** (`flattenedMemberCache["roomId:userId"]`)
   - If found, return it (this profile differs from global for this room)
2. **Check global cache** (`globalProfileCache[userId]`)
   - If found, return it (this is the canonical profile)
3. **Fallback to legacy cache** (`roomMemberCache[roomId][userId]`)

#### Memory Optimization

**Important:** To avoid `$number_of_rooms * $number_of_users` redundancy, room-specific profiles are only cached when they **differ** from the global profile.

**Storage Logic** (`storeMemberProfile`):

- If no global profile exists → Set as global (no room-specific entry needed)
- If global exists and **matches** → Don't store room-specific entry (use global)
- If global exists and **differs** → Store room-specific entry (but don't update global)

**Global Profile Updates** (`updateGlobalProfile`):

- Called when receiving explicit profile requests (canonical profiles)
- Updates global cache
- Automatically cleans up room-specific entries that now match the new global profile

### Disk Cache

#### SQLite Database (`ProfileRepository`)

Profiles are persisted to disk using SQLite:

- **Database:** `andromuks_profiles.db`
- **Table:** `user_profiles`
  - `user_id` (PRIMARY KEY)
  - `display_name` (nullable)
  - `avatar_url` (nullable)

#### Disk Cache Methods

- `saveProfile(userId, profile)` - Save/update profile
- `loadProfile(userId)` - Load single profile
- `getAllProfiles()` - Get all profiles (sorted by display name)

#### Profile Loading Flow

1. On app startup, `loadCachedProfiles()` loads all profiles from disk
2. Profiles are added to memory caches for fast access
3. Missing profiles are requested opportunistically from the backend

---

## Media Caching

### Architecture

Media caching uses a **three-layer system**:

1. **Coil Memory Cache** - In-memory bitmap cache (fastest, but can't be enumerated)
2. **Coil Disk Cache** - Persistent disk cache (can be enumerated)
3. **IntelligentMediaCache** - Custom disk cache with MXC URL tracking

### Coil Memory Cache

#### Configuration

- **Location:** RAM (bitmap objects)
- **Size:** 25% of available heap memory (configured in `ImageLoaderSingleton`)
- **API Limitation:** Cannot enumerate/list cached items (no API support)

#### How It Works

- When `AsyncImage` loads an image, Coil automatically:
  1. Checks memory cache first (fastest)
  2. If miss, checks disk cache
  3. If miss, downloads from network
  4. Stores in both memory and disk cache

#### Cache Key

Coil uses the **HTTP URL** as the cache key (not MXC URL directly).

Example:
- MXC URL: `mxc://example.com/abc123`
- HTTP URL: `https://backend/_gomuks/media/example.com/abc123`
- Coil cache key: Hash of HTTP URL

### Coil Disk Cache

#### Configuration

- **Location:** `context.cacheDir/image_cache/`
- **Size:** 2GB maximum (2048 MB)
- **Format:** Coil's proprietary format (files are not directly readable)

#### How It Works

- Coil automatically manages disk cache:
  - LRU (Least Recently Used) eviction
  - Automatic cleanup when size limit reached
  - Persists across app restarts

#### Enumerating Disk Cache

The disk cache can be enumerated by scanning the `image_cache` directory:

```kotlin
val coilCacheDir = File(context.cacheDir, "image_cache")
val files = coilCacheDir.walkTopDown().filter { it.isFile }
```

**Note:** MXC URLs are not stored directly in Coil's cache. To match files to MXC URLs, we:
1. Query event database for MXC URLs
2. Convert MXC to HTTP URL
3. Match HTTP URL hash to file name

### IntelligentMediaCache

#### Purpose

Custom disk cache that tracks MXC URLs explicitly, providing:
- Direct MXC URL to file mapping
- Viewport-aware caching
- Priority-based eviction
- Access statistics

#### Configuration

- **Location:** `context.cacheDir/intelligent_media_cache/`
- **Size:** 2GB maximum
- **Cache Key:** SHA-256 hash of MXC URL

#### Cache Key Generation

```kotlin
fun getCacheKey(mxcUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(mxcUrl.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}
```

Example:
- MXC URL: `mxc://example.com/abc123`
- Cache Key: `a1b2c3d4e5f6...` (64 hex characters)

#### Features

- **MXC URL Tracking:** Maintains `ConcurrentHashMap<String, CacheEntry>` keyed by MXC URL
- **Visibility Tracking:** Prioritizes visible media (prevents eviction)
- **Access Statistics:** Tracks access count, last accessed time, priority
- **Smart Eviction:** Evicts low-priority, non-visible media first

#### Methods

- `getCachedFile(context, mxcUrl)` - Get cached file for MXC URL
- `cacheFile(context, mxcUrl, file, fileType)` - Cache a file
- `getMxcUrlForFile(fileName)` - Reverse lookup: file name → MXC URL
- `getAllCachedMxcUrls()` - Get all cached MXC URLs
- `getCacheStats()` - Get cache statistics

---

## Cache Statistics UI

### Settings Screen

The `SettingsScreen` displays cache statistics:

- **App RAM Usage:** Current and maximum heap memory
- **Room Timeline Cache:** Number of events and estimated memory
- **User Profiles (Memory):** Number of profiles and estimated memory
- **User Profiles (Disk):** SQLite database size
- **Media Cache (Memory):** Maximum configured size (actual usage not available)
- **Media Cache (Disk):** Coil disk cache size

### Cached Profiles Screen

Accessible from Settings → "User Profiles (Memory)" or "User Profiles (Disk)".

**Memory View:**
- Shows all room-specific profiles (with room IDs)
- Shows global profiles (room ID: null)
- Displays: Avatar, Display Name, User ID, Room ID (if room-specific)

**Disk View:**
- Shows all profiles from SQLite database
- Displays: Avatar, Display Name, User ID

### Cached Media Gallery

Accessible from Settings → "Media Cache (Memory)" or "Media Cache (Disk)".

**Memory View:**
- Shows Coil's disk cache (represents what could be in RAM)
- **Note:** Coil's RAM cache cannot be enumerated (bitmaps in memory)
- Displays thumbnails, MXC URLs, file sizes

**Disk View:**
- Shows IntelligentMediaCache entries (with MXC URLs)
- Shows Coil's disk cache entries (may not have MXC URLs)
- Displays thumbnails, MXC URLs (when available), file sizes

---

## Cache Flow Examples

### Profile Caching Flow

1. **User opens a room:**
   - `getMemberProfile(roomId, userId)` is called
   - Checks room-specific cache → global cache → legacy cache
   - If not found, `requestUserProfileAsync()` is called
   - Backend responds with profile
   - `storeMemberProfile()` is called:
     - If differs from global → stored in room-specific cache
     - If matches global → not stored (uses global)
     - Global cache is always updated

2. **Explicit profile request:**
   - `handleProfileResponse()` receives canonical profile
   - `updateGlobalProfile()` is called
   - Global cache updated
   - Room-specific entries that now match are cleaned up

3. **App startup:**
   - `loadCachedProfiles()` loads all profiles from SQLite
   - Profiles added to memory caches
   - UI can render immediately without network requests

### Media Caching Flow

1. **Image displayed in UI:**
   - `AsyncImage` loads image URL
   - Coil checks memory cache → disk cache → network
   - Image cached in both memory (bitmap) and disk (file)
   - If MXC URL is known, `IntelligentMediaCache.cacheFile()` may be called

2. **Media gallery access:**
   - "Memory" tab: Scans Coil's disk cache (`image_cache/`)
   - "Disk" tab: Scans IntelligentMediaCache + Coil's disk cache
   - MXC URLs are matched by:
     - IntelligentMediaCache: Direct lookup (has MXC URL → file mapping)
     - Coil cache: Query event database, convert MXC → HTTP, match URL hash

3. **Cache eviction:**
   - Coil: Automatic LRU eviction when size limit reached
   - IntelligentMediaCache: Priority-based eviction (lowest priority, non-visible first)

---

## Important Limitations

### Memory Cache Enumeration

**Coil's MemoryCache cannot be enumerated.** The API doesn't provide a way to list what bitmaps are currently in RAM. This is a limitation of Coil's design.

**Workaround:**
- Show Coil's disk cache as "Memory" (represents what could be in RAM)
- Disk cache entries get loaded into RAM when accessed via `AsyncImage`

### Profile Cache Optimization

**Room-specific profiles are only cached when they differ from global.** This prevents:
- Storing `$number_of_rooms * $number_of_users` redundant entries
- Memory bloat from duplicate profiles

**Example:**
- User `@alice:example.com` has same profile in 10 rooms
- Global cache: 1 entry
- Room-specific cache: 0 entries (all use global)
- If profile differs in Room A: 1 room-specific entry for Room A

### MXC URL Matching

**Coil's cache doesn't store MXC URLs directly.** To match files to MXC URLs:

1. Query event database for MXC URLs
2. Convert MXC → HTTP URL
3. Match HTTP URL hash to Coil's cache file name

This is a best-effort matching and may not find all MXC URLs for cached files.

---

## Performance Considerations

### Memory Cache Size

- **Profiles:** Estimated ~350 bytes per profile
- **Timeline Events:** Estimated ~1.5KB per event
- **Media (Coil):** 25% of heap (configurable)

### Disk Cache Size

- **Profiles:** SQLite database (typically small, < 10MB)
- **Media (Coil):** 2GB maximum
- **Media (IntelligentMediaCache):** 2GB maximum

### Cache Hit Rates

- **Profile Cache:** Very high (profiles are cached aggressively)
- **Media Cache:** High (Coil's automatic caching is efficient)
- **Memory Cache:** Highest (bitmaps in RAM, fastest access)

---

## Troubleshooting

### Empty Memory Cache (Media)

**Symptom:** "Memory Cached Media" shows 0 items

**Possible Causes:**
1. Coil hasn't cached anything to disk yet (no images loaded)
2. Cache directory doesn't exist (`image_cache/` not created)
3. Images loaded but cache policy disabled

**Solution:**
- Check logs for: `"Checking Coil disk cache at: ..."`
- Verify `context.cacheDir/image_cache/` exists
- Load some images in the app, then check again

### Missing MXC URLs

**Symptom:** Gallery shows "Unknown MXC URL" for many files

**Possible Causes:**
1. Files from Coil cache (doesn't store MXC URLs)
2. MXC URL not found in event database (event not loaded yet)
3. MXC → HTTP URL conversion failed

**Solution:**
- IntelligentMediaCache entries always have MXC URLs (direct mapping)
- Coil cache entries may not have MXC URLs (best-effort matching)

### Profile Cache Not Updating

**Symptom:** Profile changes not reflected in UI

**Possible Causes:**
1. Profile not requested from backend
2. Cache not updated after profile response
3. Room-specific cache not checked

**Solution:**
- Check `requestUserProfileAsync()` is being called
- Verify `handleProfileResponse()` updates caches
- Check `storeMemberProfile()` logic (room-specific vs global)

---

## Related Files

### Profile Caching
- `AppViewModel.kt` - Profile cache management
- `ProfileRepository.kt` - SQLite profile storage
- `CachedProfilesScreen.kt` - Profile gallery UI

### Media Caching
- `ImageLoaderSingleton.kt` - Coil configuration
- `IntelligentMediaCache.kt` - Custom media cache with MXC URL tracking
- `MediaCache.kt` - Legacy media cache (deprecated)
- `CachedMediaScreen.kt` - Media gallery UI

### Event Database
- `EventDao.kt` - Event queries for MXC URL matching
- `EventEntity.kt` - Event entity with raw JSON

---

## Future Improvements

1. **Coil Memory Cache Enumeration:** If Coil adds API support, enumerate actual RAM cache
2. **Better MXC URL Matching:** Improve matching algorithm for Coil cache files
3. **Cache Statistics:** Add hit/miss rates, eviction statistics
4. **Cache Warming:** Preload frequently accessed profiles/media
5. **Cache Compression:** Compress cached profiles/media to reduce disk usage

---

**Last Updated:** 2024  
**Related Documents:**
- `PERSISTENT_ROOM_EVENTS_STORAGE.md` - Room events persistence
- `AVATAR_LOADING_ANALYSIS.md` - Avatar loading flow

