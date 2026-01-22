package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.MainActivity
import net.vrkknn.andromuks.BuildConfig


data class PersonTarget(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val roomId: String,
    val roomDisplayName: String,
    val lastActiveTimestamp: Long
)

/**
 * PersonsApi manages Person objects for Android's People API
 * 
 * IMPORTANT: Person shortcuts are NOT published as dynamic shortcuts.
 * They are only used in notifications via Person objects (NotificationCompat.MessagingStyle).
 * Dynamic shortcuts are reserved for conversation/room shortcuts only.
 * 
 * This ensures that person shortcuts don't interfere with the 4 conversation shortcuts
 * shown in the app icon long-press menu.
 */
class PersonsApi(
    private val context: Context,
    private val homeserverUrl: String,
    private val authToken: String,
    private val realMatrixHomeserverUrl: String = ""
) {

    companion object {
        private const val TAG = "PersonsApi"
        private const val MAX_PEOPLE = 8
        private const val SHORTCUT_PREFIX = "person_"
        private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"
        private const val DEBOUNCE_MS = 500L // Debounce rapid updates to prevent cancellation errors
        const val EXTRA_ROOM_ID = "net.vrkknn.andromuks.extra.PERSON_ROOM_ID"
        const val EXTRA_USER_ID = "net.vrkknn.andromuks.extra.PERSON_USER_ID"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingJob: Job? = null
    private var lastUpdateTime = 0L

    private var lastPublished = emptyMap<String, PersonTarget>()
    private var lastShortcutIds = emptySet<String>()

    fun updatePersons(targets: List<PersonTarget>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping Persons update - API level too low")
            return
        }

        val trimmed = trimTargets(targets)
        if (!needsUpdate(trimmed)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: No changes detected, skipping update")
            return
        }

        // COLD START FIX: Debounce rapid updates to prevent JobCancellationException
        // When multiple updatePersons() calls happen in quick succession (e.g., on startup),
        // we debounce them to prevent cancelling jobs mid-execution
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastUpdateTime
        
        pendingJob?.cancel()
        pendingJob = scope.launch {
            // Wait if update came too soon after previous one
            if (timeSinceLastUpdate < DEBOUNCE_MS) {
                delay(DEBOUNCE_MS - timeSinceLastUpdate)
            }
            try {
                publishPeople(trimmed)
                lastUpdateTime = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled - this is expected if a newer update arrived
                if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Update job cancelled (newer update arrived)")
                throw e // Re-throw to properly handle cancellation
            } catch (e: Exception) {
                Log.e(TAG, "PersonsApi: Error publishing people", e)
            }
        }
    }

    /**
     * Remove all person shortcuts from dynamic shortcuts
     * Person shortcuts should only be used in notifications, not as app icon shortcuts
     */
    fun cleanupPersonShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        
        scope.launch {
            try {
                val allShortcuts = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                val personShortcuts = allShortcuts.filter { shortcut ->
                    shortcut.id.startsWith(SHORTCUT_PREFIX)
                }
                
                if (personShortcuts.isNotEmpty()) {
                    val personShortcutIds = personShortcuts.map { it.id }
                    ShortcutManagerCompat.removeDynamicShortcuts(context, personShortcutIds)
                    if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Cleaned up ${personShortcutIds.size} person shortcuts from dynamic shortcuts")
                }
                
                lastShortcutIds = emptySet()
            } catch (e: Exception) {
                Log.e(TAG, "PersonsApi: Failed to cleanup person shortcuts", e)
            }
        }
    }

    fun reportShortcutUsed(userId: String) {
        // Person shortcuts are no longer published as dynamic shortcuts
        // This method is kept for backward compatibility but does nothing
        if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: reportShortcutUsed called for $userId (person shortcuts are not dynamic shortcuts)")
    }

    fun clear() {
        // Person shortcuts are no longer published as dynamic shortcuts
        // Just clear internal state
        lastShortcutIds = emptySet()
        lastPublished = emptyMap()
        if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Cleared internal person state")
    }

    private fun trimTargets(targets: List<PersonTarget>): List<PersonTarget> {
        return targets
            .distinctBy { it.userId }
            .sortedByDescending { it.lastActiveTimestamp }
            .take(MAX_PEOPLE)
    }

    private fun needsUpdate(targets: List<PersonTarget>): Boolean {
        if (targets.size != lastPublished.size) {
            return true
        }

        return targets.any { target ->
            val prev = lastPublished[target.userId]
            prev == null ||
                prev.displayName != target.displayName ||
                prev.avatarUrl != target.avatarUrl ||
                prev.roomId != target.roomId ||
                prev.lastActiveTimestamp != target.lastActiveTimestamp
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun publishPeople(targets: List<PersonTarget>) {
        withContext(Dispatchers.IO) {
            // CRITICAL: Person shortcuts should NOT be published as dynamic shortcuts
            // They should only be used in notifications via Person objects (NotificationCompat.MessagingStyle)
            // Dynamic shortcuts are reserved for conversation/room shortcuts only (shown in app icon long-press menu)
            // 
            // Android only shows 4 dynamic shortcuts total. If we publish person shortcuts as dynamic shortcuts,
            // they would push conversation shortcuts out of the top 4 slots.
            //
            // Instead, Person objects are attached to notifications (already implemented in EnhancedNotificationDisplay.kt)
            // and are used by Android's People API for conversation grouping and quick actions.
            
            // Remove any existing person shortcuts from dynamic shortcuts to free up slots
            // and prevent them from appearing in the app icon long-press menu
            val allShortcuts = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
            val existingPersonShortcuts = allShortcuts.filter { shortcut ->
                shortcut.id.startsWith(SHORTCUT_PREFIX)
            }
            
            if (existingPersonShortcuts.isNotEmpty()) {
                val personShortcutIds = existingPersonShortcuts.map { it.id }
                try {
                    ShortcutManagerCompat.removeDynamicShortcuts(context, personShortcutIds)
                    if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Removed ${personShortcutIds.size} person shortcuts from dynamic shortcuts (they should only be used in notifications)")
                } catch (e: Exception) {
                    Log.e(TAG, "PersonsApi: Failed to remove person shortcuts from dynamic shortcuts", e)
                }
            }
            
            // Clear our tracking since we're not publishing shortcuts anymore
            if (lastShortcutIds.isNotEmpty()) {
                lastShortcutIds = emptySet()
            }
            
            // Store person data for notification use (Person objects are used in notifications, not as shortcuts)
            lastPublished = targets.associateBy { it.userId }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "PersonsApi: Processed ${targets.size} person targets for notification use (not as dynamic shortcuts)")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun buildShortcut(target: PersonTarget): ShortcutInfoCompat {
        val displayName = target.displayName.ifBlank { target.userId }
        val shortcutIcon = loadShortcutIcon(target)

        val person = Person.Builder()
            .setKey(target.userId)
            .setName(displayName)
            .setUri(buildPersonUri(target.userId))
            .setIcon(shortcutIcon)
            .build()

        val viewIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(buildMatrixUri(target.roomId)),
            context,
            MainActivity::class.java
        ).apply {
            putExtra(EXTRA_ROOM_ID, target.roomId)
            putExtra(EXTRA_USER_ID, target.userId)
            putExtra("direct_navigation", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shareIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "*/*"
            putExtra(EXTRA_ROOM_ID, target.roomId)
            putExtra(EXTRA_USER_ID, target.userId)
            putExtra("direct_navigation", true)
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        val builder = ShortcutInfoCompat.Builder(context, shortcutId(target.userId))
            .setShortLabel(displayName.take(40))
            .setLongLabel(target.roomDisplayName.ifBlank { displayName }.take(80))
            .setPerson(person)
            .setLongLived(true)
            .setCategories(setOf(CATEGORY_CONVERSATION))
            .setIntents(arrayOf(viewIntent, shareIntent))
        builder.setIcon(shortcutIcon)

        return builder.build()
    }

    private fun shortcutId(userId: String): String {
        return "$SHORTCUT_PREFIX$userId"
    }

    private fun buildMatrixUri(roomId: String): String {
        return if (realMatrixHomeserverUrl.isNotEmpty()) {
            val host = try {
                android.net.Uri.parse(realMatrixHomeserverUrl).host
            } catch (e: Exception) {
                null
            }
            if (!host.isNullOrBlank()) {
                "matrix:roomid/${roomId.removePrefix("!")}?via=$host"
            } else {
                "matrix:roomid/${roomId.removePrefix("!")}"
            }
        } else {
            "matrix:roomid/${roomId.removePrefix("!")}"
        }
    }

    private fun buildPersonUri(userId: String): String {
        val sanitized = userId.removePrefix("@")
        return "matrix:u/$sanitized"
    }

    private suspend fun loadPersonIcon(target: PersonTarget): IconCompat? {
        return withContext(Dispatchers.IO) {
            val avatarUrl = target.avatarUrl
            if (avatarUrl.isNullOrBlank()) {
                return@withContext null
            }

            // Check if we have a cached version first
            var cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            // If not cached, download and cache it (similar to ConversationsApi)
            if (cachedFile == null || !cachedFile.exists()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Avatar for ${target.userId} not cached, downloading...")
                
                // Convert MXC URL to HTTP URL
                val httpUrl = when {
                    avatarUrl.startsWith("mxc://") -> {
                        AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                    }
                    avatarUrl.startsWith("_gomuks/") -> {
                        "$homeserverUrl/$avatarUrl"
                    }
                    else -> {
                        avatarUrl
                    }
                }
                
                if (httpUrl != null) {
                    // Download and cache using IntelligentMediaCache
                    cachedFile = IntelligentMediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
                    if (cachedFile != null) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: ✓ Successfully downloaded and cached avatar for ${target.userId} (${cachedFile.length()} bytes)")
                    } else {
                        Log.w(TAG, "PersonsApi: ✗ Failed to download avatar for ${target.userId} from: $httpUrl")
                    }
                } else {
                    Log.w(TAG, "PersonsApi: Failed to convert avatar URL to HTTP URL: $avatarUrl")
                }
            }

            if (cachedFile == null || !cachedFile.exists()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "PersonsApi: Avatar for ${target.userId} not available, using fallback")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "PersonsApi: Failed to decode avatar bitmap for ${target.userId}")
                return@withContext null
            }

            val circular = getCircularBitmap(bitmap)
            if (circular == null) {
                Log.w(TAG, "PersonsApi: Failed to create circular bitmap for ${target.userId}")
                return@withContext null
            }

            IconCompat.createWithAdaptiveBitmap(circular)
        }
    }

    private suspend fun loadShortcutIcon(target: PersonTarget): IconCompat {
        return loadPersonIcon(target) ?: createFallbackIcon(target)
    }

    private fun createFallbackIcon(target: PersonTarget): IconCompat {
        return try {
            val colorHex = AvatarUtils.getUserColor(target.userId)
            val letter = AvatarUtils.getFallbackCharacter(target.displayName, target.userId)
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val backgroundPaint = Paint().apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#$colorHex")
                isAntiAlias = true
            }

            canvas.drawRect(0f, 0f, 256f, 256f, backgroundPaint)

            val textPaint = Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = 144f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }

            val baseline = 256f / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(letter.ifBlank { "?" }, 128f, baseline, textPaint)

            IconCompat.createWithBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "PersonsApi: Failed to create fallback icon for ${target.userId}", e)
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
    }

    private fun getCircularBitmap(source: Bitmap): Bitmap? {
        val size = minOf(source.width, source.height)
        if (size <= 0) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
        }

        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, rect, rect, paint)

        return output
    }
}

