package net.vrkknn.andromuks.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.AvatarBitmapUtils
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import java.io.File
import java.io.FileOutputStream

/**
 * Prepares circular avatar PNGs on disk for the widget to load at paint time.
 *
 * Glance takes an `ImageProvider(Bitmap)`, and every bitmap in a widget update rides the
 * `RemoteViews` IPC transaction, which is hard-capped (~1–2 MB) and *kills the whole update* when
 * exceeded. So avatars are rendered small ([AVATAR_PX]) and circular here, once, then referenced
 * from the snapshot by file path — never inlined into it.
 *
 * Files live in a widget-owned directory rather than in `IntelligentMediaCache` itself: that cache
 * evicts on its own schedule, and a widget's avatar must stay readable for as long as its snapshot
 * does. The source download still goes through `IntelligentMediaCache` so we share its disk cache
 * and its auth handling.
 */
object RoomWidgetAvatars {
    private const val TAG = "RoomWidgetAvatars"

    /** Longest edge of a stored avatar. 96 px ≈ 36 KB as ARGB_8888 — see the IPC budget above. */
    private const val AVATAR_PX = 96

    private fun dir(context: Context): File = File(context.cacheDir, "room_widget_avatars").apply { mkdirs() }

    /**
     * Resolve [mxcUrl] to a circular PNG on disk and return its absolute path.
     *
     * Falls back to an initials lettermark (via [AvatarBitmapUtils.createFallbackAvatarBitmap])
     * when there is no avatar URL or the download fails, so the widget always has something to draw
     * and never renders a hole. Returns null only if even the fallback could not be written.
     *
     * The download recipe mirrors `EnhancedNotificationDisplay.loadAvatarBitmap`: the stable
     * `mxc://` URL is the cache key, while the request URL carries `?image_auth=` for
     * `/_gomuks/media/` endpoints.
     */
    suspend fun resolve(context: Context, mxcUrl: String?, fallbackName: String?, fallbackId: String, homeserverUrl: String, authToken: String): String? =
        withContext(Dispatchers.IO) {
            val cacheKey = cacheKeyFor(mxcUrl, fallbackId)
            val target = File(dir(context), "$cacheKey.png")
            if (target.exists() && target.length() > 0) return@withContext target.absolutePath

            val source = mxcUrl?.takeIf { it.isNotBlank() }?.let { url ->
                loadSourceBitmap(context, url, homeserverUrl, authToken)
            }
            val bitmap = source ?: AvatarBitmapUtils.createFallbackAvatarBitmap(fallbackName, fallbackId, AVATAR_PX)
            val circular = AvatarBitmapUtils.createCircularBitmap(bitmap)
            if (circular !== bitmap) bitmap.recycle()

            val written = write(target, circular)
            circular.recycle()
            if (written) target.absolutePath else null
        }

    /**
     * Cache key for an avatar file. Keyed on the mxc URL when there is one, so two users sharing an
     * avatar share a file; keyed on the user/room id for lettermarks, so each gets its own initial
     * and colour.
     */
    private fun cacheKeyFor(mxcUrl: String?, fallbackId: String): String {
        val raw = mxcUrl?.takeIf { it.isNotBlank() }?.let { "mxc_" + IntelligentMediaCache.getCacheKey(it) }
            ?: "fallback_$fallbackId"
        return raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)
    }

    private suspend fun loadSourceBitmap(context: Context, avatarUrl: String, homeserverUrl: String, authToken: String): Bitmap? = try {
        val cached = IntelligentMediaCache.getCachedFile(context, avatarUrl)
        val file = cached ?: downloadAvatar(context, avatarUrl, homeserverUrl, authToken)
        file?.let { AvatarBitmapUtils.decodeScaledBitmap(it, AVATAR_PX) }
    } catch (e: Exception) {
        Log.w(TAG, "Avatar load failed for $avatarUrl: ${e.message}")
        null
    }

    private suspend fun downloadAvatar(context: Context, avatarUrl: String, homeserverUrl: String, authToken: String): File? {
        if (homeserverUrl.isBlank()) return null
        val baseHttpUrl = when {
            avatarUrl.startsWith("mxc://") -> AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
            avatarUrl.startsWith("_gomuks/") -> "$homeserverUrl/$avatarUrl"
            else -> avatarUrl
        } ?: return null

        val httpUrl = if (authToken.isNotEmpty() && baseHttpUrl.contains("/_gomuks/media/")) {
            val sep = if (baseHttpUrl.contains("?")) "&" else "?"
            "$baseHttpUrl${sep}image_auth=$authToken"
        } else {
            baseHttpUrl
        }
        return IntelligentMediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
    }

    private fun write(target: File, bitmap: Bitmap): Boolean = try {
        FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not write widget avatar ${target.name}: ${e.message}")
        false
    }

    /**
     * Delete avatar files no longer referenced by any live snapshot. Called after a refresh, so the
     * directory tracks the set of currently displayed senders instead of growing without bound.
     */
    fun prune(context: Context, keepPaths: Set<String>) {
        try {
            dir(context).listFiles()?.forEach { file ->
                if (file.absolutePath !in keepPaths) file.delete()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Avatar prune skipped: ${e.message}")
        }
    }
}
