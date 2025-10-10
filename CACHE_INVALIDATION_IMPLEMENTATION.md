# Cache Invalidation Implementation

## Overview

Implemented smart cache invalidation for media content across the Andromuks app. The system intelligently determines when to invalidate cached content based on error types, only clearing cache when content is permanently invalid or corrupt.

## Implementation Details

### Core Utility: `CacheUtils.kt`

Created a new utility class with three main functions:

1. **`shouldInvalidateCache(throwable: Throwable): Boolean`**
   - Analyzes error types to determine if cache should be cleared
   - Returns `true` for permanent failures (404, 410, corruption)
   - Returns `false` for transient failures (network, auth, server errors)

2. **`invalidateImageCache(imageLoader: ImageLoader, imageUrl: String)`**
   - Removes content from both memory and disk cache
   - Logs cache invalidation for debugging

3. **`handleImageLoadError(imageUrl, throwable, imageLoader, context)`**
   - Convenient wrapper that logs errors and conditionally invalidates cache
   - Used consistently across all image loading points

### Cache Invalidation Rules

#### ✅ **Cache IS invalidated for:**
- **HTTP 404 (Not Found)** - Media deleted or invalid URL
- **HTTP 410 (Gone)** - Media permanently removed
- **Decode/Corruption errors** - Cache file is damaged
- **Malformed image data** - Invalid image format

#### ❌ **Cache is NOT invalidated for:**
- **Network errors** (timeout, connection failure, DNS resolution)
- **Authentication errors** (401, 403) - Token might be refreshing
- **Server errors** (500, 502, 503, 504) - Temporary server issues
- **Stream reset errors** - Usually transient network issues
- **Unknown errors** - Conservative approach to preserve valid cache

### Files Updated

Updated error handling in all media loading components:

1. **`MediaFunctions.kt`** (2 locations)
   - Media message images
   - Fullscreen image viewer

2. **`AvatarImage.kt`** (1 location)
   - User avatar loading
   - Preserves fallback generation logic

3. **`html.kt`** (1 location)
   - HTML embedded images

4. **`StickerFunctions.kt`** (2 locations)
   - Sticker display in messages
   - Fullscreen sticker viewer

5. **`ReactionFunctions.kt`** (1 location)
   - Custom image reactions

6. **`EmojiSelection.kt`** (1 location)
   - Custom emoji picker

## Benefits

### 1. **Improved Reliability**
- Corrupt cache entries are automatically cleared
- Deleted media is not repeatedly attempted from cache
- Proper handling of 404/410 responses

### 2. **Better Offline Experience**
- Network failures don't clear valid cache
- Users can still view cached content during connectivity issues
- Temporary server outages don't lose cached data

### 3. **Smart Error Handling**
- Conservative approach preserves valid cached content
- Extensive logging helps debug media loading issues
- Consistent error handling across all media types

### 4. **Performance**
- Reduces unnecessary re-downloads of valid cached content
- Clears cache only when necessary
- Uses Coil's efficient dual-cache system (memory + disk)

## Error Logging

All cache invalidation decisions are logged with context:

```kotlin
// When cache is invalidated
Log.w("Andromuks", "CacheUtils: Cache invalidated for URL: $imageUrl")

// When error is detected
Log.e("Andromuks", "❌ Media load failed: $imageUrl")
Log.e("Andromuks", "Error: ${throwable.message}", throwable)

// Decision rationale
Log.d("Andromuks", "CacheUtils: Should invalidate - 404 Not Found")
Log.d("Andromuks", "CacheUtils: Not invalidating - network error (transient)")
```

## Testing Recommendations

Test the following scenarios:

1. **404 Error** - Verify cache is cleared, subsequent loads don't use stale cache
2. **Network Timeout** - Verify cache is NOT cleared, image shows when network recovers
3. **Server 500 Error** - Verify cache is NOT cleared
4. **Corrupt Cache File** - Verify cache is cleared and re-downloaded
5. **Auth Token Expired** - Verify cache is NOT cleared, image loads after token refresh

## Future Enhancements

Potential improvements:

1. **Retry Logic** - Automatic retry with exponential backoff for transient errors
2. **Cache Metrics** - Track cache hit/miss rates and invalidation frequency
3. **User Feedback** - Show toast messages when media is unavailable (404/410)
4. **Cache Health Check** - Periodic validation of cached content
5. **Selective Invalidation** - Clear only specific cache entries, not all variants (thumbnail vs full)

## Technical Notes

- Uses Coil's built-in memory and disk cache systems
- Cache keys are based on image URLs
- No changes required to `ImageLoaderSingleton.kt` configuration
- Works with encrypted and unencrypted media
- Compatible with MXC URL conversion system
- Thread-safe cache operations

## No Breaking Changes

- All existing functionality preserved
- Error handlers now include cache invalidation logic
- Fallback mechanisms (avatars, placeholders) still work
- No API changes required

