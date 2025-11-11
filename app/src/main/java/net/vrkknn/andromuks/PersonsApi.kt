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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.MainActivity

data class PersonTarget(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val roomId: String,
    val roomDisplayName: String,
    val lastActiveTimestamp: Long
)

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
        const val EXTRA_ROOM_ID = "net.vrkknn.andromuks.extra.PERSON_ROOM_ID"
        const val EXTRA_USER_ID = "net.vrkknn.andromuks.extra.PERSON_USER_ID"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingJob: Job? = null

    private var lastPublished = emptyMap<String, PersonTarget>()
    private var lastShortcutIds = emptySet<String>()

    fun updatePersons(targets: List<PersonTarget>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            Log.d(TAG, "Skipping Persons update - API level too low")
            return
        }

        val trimmed = trimTargets(targets)
        if (!needsUpdate(trimmed)) {
            Log.d(TAG, "PersonsApi: No changes detected, skipping update")
            return
        }

        pendingJob?.cancel()
        pendingJob = scope.launch {
            publishPeople(trimmed)
        }
    }

    fun reportShortcutUsed(userId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutId = shortcutId(userId)
        ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
        Log.d(TAG, "PersonsApi: Reported shortcut used for $userId ($shortcutId)")
    }

    fun clear() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        if (lastShortcutIds.isEmpty()) return

        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, lastShortcutIds.toList())
            Log.d(TAG, "PersonsApi: Cleared ${lastShortcutIds.size} people shortcuts")
        } catch (e: Exception) {
            Log.e(TAG, "PersonsApi: Failed to clear shortcuts", e)
        } finally {
            lastShortcutIds = emptySet()
            lastPublished = emptyMap()
        }
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
            val newShortcutIds = targets.map { shortcutId(it.userId) }.toSet()

            val toRemove = lastShortcutIds - newShortcutIds
            if (toRemove.isNotEmpty()) {
                try {
                    ShortcutManagerCompat.removeDynamicShortcuts(context, toRemove.toList())
                    Log.d(TAG, "PersonsApi: Removed ${toRemove.size} outdated people shortcuts")
                } catch (e: Exception) {
                    Log.e(TAG, "PersonsApi: Failed to remove outdated shortcuts", e)
                }
            }

            for (target in targets) {
                try {
                    val shortcut = buildShortcut(target)
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                    Log.d(TAG, "PersonsApi: Published person shortcut for ${target.userId}")
                } catch (e: Exception) {
                    Log.e(TAG, "PersonsApi: Failed to publish shortcut for ${target.userId}", e)
                }
            }

            lastShortcutIds = newShortcutIds
            lastPublished = targets.associateBy { it.userId }
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

            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            if (cachedFile == null || !cachedFile.exists()) {
                Log.d(TAG, "PersonsApi: Avatar for ${target.userId} not cached, using fallback")
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

