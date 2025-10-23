# Custom User-Agent Implementation

## Overview

Configured Coil image loader to use a custom User-Agent that identifies Andromuks in server logs and analytics.

## Implementation

### Custom User-Agent String

**User-Agent:** `Andromuks/1.0-alpha (Android; Coil)`

This identifies:
- **Andromuks** - Our app name
- **1.0-alpha** - Version (following semver)
- **Android** - Platform
- **Coil** - Image loading library (for transparency)

### Technical Implementation

**File:** `app/src/main/java/net/vrkknn/andromuks/utils/ImageLoaderSingleton.kt`

```kotlin
private fun createImageLoader(context: Context): ImageLoader {
    // Create custom OkHttpClient with Andromuks User-Agent
    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val requestWithUserAgent = originalRequest.newBuilder()
                .header("User-Agent", "Andromuks/1.0-alpha (Android; Coil)")
                .build()
            chain.proceed(requestWithUserAgent)
        }
        .build()
    
    return ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        // ... rest of configuration
        .build()
}
```

### Architecture

**OkHttp Interceptor:**
- Intercepts every HTTP request made by Coil
- Adds custom `User-Agent` header
- Replaces default "okhttp/5.1.0" with "Andromuks/1.0-alpha (Android; Coil)"

**ImageLoader Singleton:**
- Single global ImageLoader instance
- All image loading in the app uses this singleton
- Consistent User-Agent across all requests

### Files Modified

All files now use `ImageLoaderSingleton.get(context)` for consistent User-Agent:

1. ✅ **ImageLoaderSingleton.kt**
   - Added OkHttpClient with User-Agent interceptor
   - Core configuration

2. ✅ **MediaFunctions.kt** (2 places)
   - Media messages (images/videos)
   - Image viewer dialog

3. ✅ **AvatarImage.kt**
   - Avatar images (already using singleton)

4. ✅ **html.kt**
   - Inline images in HTML messages

5. ✅ **StickerFunctions.kt** (2 places)
   - Sticker messages
   - Sticker viewer dialog

6. ✅ **EmojiSelection.kt**
   - Custom emoji images

7. ✅ **ReactionFunctions.kt**
   - Custom reaction images

8. ✅ **ConversationsApi.kt** (3 places)
   - Shortcut icons
   - Notification avatars
   - Conversation icons

### Proxy Log Verification

**Before:**
```
[21:59:28] GET /_gomuks/media/.../file123 "okhttp/5.1.0"
```

**After:**
```
[22:10:15] GET /_gomuks/media/.../file123 "Andromuks/1.0-alpha (Android; Coil)"
```

## Benefits

### ✅ Better Server Analytics

- Identify Andromuks traffic separately from other clients
- Track adoption and usage patterns
- Debug issues specific to Andromuks

### ✅ Server-Side Optimization

- Server can apply Andromuks-specific optimizations
- Custom caching strategies for mobile clients
- Better rate limiting and resource allocation

### ✅ Debugging and Support

- Easier to identify issues in server logs
- Filter logs by client type
- Track client versions in production

### ✅ Professional Identity

- App has a clear identity in logs
- Shows version information
- Transparent about underlying technology (Coil)

## Version Management

### Current Version
- **1.0-alpha** - Alpha testing phase

### Future Versions

Update User-Agent when releasing new versions:

```kotlin
// Example for beta
.header("User-Agent", "Andromuks/1.0-beta (Android; Coil)")

// Example for stable release
.header("User-Agent", "Andromuks/1.0 (Android; Coil)")

// Example for updates
.header("User-Agent", "Andromuks/1.1 (Android; Coil)")
```

**Where to update:**
- `ImageLoaderSingleton.kt` line 35
- Single source of truth for entire app

### Dynamic Version (Optional)

Could make it dynamic from BuildConfig:

```kotlin
// In build.gradle.kts
android {
    defaultConfig {
        versionName = "1.0-alpha"
    }
}

// In ImageLoaderSingleton.kt
import net.vrkknn.andromuks.BuildConfig

.header("User-Agent", "Andromuks/${BuildConfig.VERSION_NAME} (Android; Coil)")
```

## Standardization

### Why Include "Coil"?

**Transparency:** Shows we're using Coil library
- Helps diagnose library-specific issues
- Credits open-source dependency
- Standard practice (e.g., "Chrome/120.0", "Firefox/121.0")

### User-Agent Format

Following standard conventions:
```
ApplicationName/Version (Platform; Details)
```

**Examples from other apps:**
- `Element/1.6.8 (Android; OkHttp/4.12.0)`
- `FluffyChat/1.16.0 (Android; Matrix SDK)`
- `Andromuks/1.0-alpha (Android; Coil)`

## Testing

### Verify User-Agent

**Server logs:**
```bash
# Check reverse proxy logs
tail -f /var/log/nginx/access.log | grep Andromuks

# Expected output
[...] "GET /_gomuks/media/..." "Andromuks/1.0-alpha (Android; Coil)"
```

**App behavior:**
- ✅ All images load correctly
- ✅ Avatars display properly
- ✅ Media messages work
- ✅ Stickers render
- ✅ Custom emoji show
- ✅ No functionality lost

### Testing Checklist

1. ✅ Open app and check room list (avatars load)
2. ✅ Open room with media (images load with new User-Agent)
3. ✅ Send sticker (loads with new User-Agent)
4. ✅ React with custom emoji (loads with new User-Agent)
5. ✅ Check server logs (see "Andromuks/1.0-alpha")
6. ✅ Check notifications (icons load)
7. ✅ Check shortcuts (icons load)

## Server-Side Usage

### Nginx Log Format

**Identify Andromuks requests:**
```nginx
log_format main '$remote_addr - $request - $http_user_agent';
```

**Filter logs:**
```bash
# All Andromuks requests
grep "Andromuks" /var/log/nginx/access.log

# Count Andromuks users
grep "Andromuks" /var/log/nginx/access.log | wc -l

# Check versions
grep -oP "Andromuks/\K[^ ]+" /var/log/nginx/access.log | sort | uniq -c
```

### Server-Side Optimization Examples

**Example 1: Custom caching for mobile**
```nginx
location /_gomuks/media/ {
    # Longer cache for Andromuks (better battery life)
    if ($http_user_agent ~* "Andromuks") {
        expires 7d;
    }
    proxy_pass http://backend;
}
```

**Example 2: Rate limiting by client**
```nginx
# More generous limits for Andromuks
limit_req_zone $http_user_agent zone=andromuks:10m rate=100r/s;

location /_gomuks/media/ {
    if ($http_user_agent ~* "Andromuks") {
        limit_req zone=andromuks burst=50;
    }
    proxy_pass http://backend;
}
```

**Example 3: Analytics**
```nginx
# Track client types
map $http_user_agent $client_type {
    ~*"Andromuks"  "andromuks";
    ~*"Element"    "element";
    default        "unknown";
}

log_format analytics '$client_type $request_time $body_bytes_sent';
```

## Maintenance

### Update Version

When releasing new version:

1. Update `ImageLoaderSingleton.kt`:
   ```kotlin
   .header("User-Agent", "Andromuks/1.1 (Android; Coil)")
   ```

2. Update this documentation

3. Announce to server admins (if applicable)

### Monitor

- Check server logs for User-Agent distribution
- Track version adoption
- Identify outdated clients

## Related Changes

- **DUPLICATE_MEDIA_DOWNLOAD_FIX.md** - Eliminated redundant downloads
- **UI_RENDERING_PERFORMANCE_OPTIMIZATION.md** - Performance improvements
- **ImageLoaderSingleton** - Centralized image loading configuration

## Status

**IMPLEMENTED and TESTED** ✅

---

**Last Updated:** October 16, 2025  
**Version:** 1.0-alpha  
**User-Agent:** `Andromuks/1.0-alpha (Android; Coil)`

