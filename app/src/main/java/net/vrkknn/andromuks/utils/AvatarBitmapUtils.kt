package net.vrkknn.andromuks.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Bitmap plumbing shared by every surface that has to hand Android a raw avatar bitmap rather than
 * let Coil do the work — notifications ([net.vrkknn.andromuks.EnhancedNotificationDisplay]) and the
 * home-screen room widget ([net.vrkknn.andromuks.widget.RoomWidgetRefresher]).
 *
 * The timeline and other Compose UI must **not** use these: Coil already handles decoding, caching
 * and circular cropping there. This exists for the `IconCompat` / `ImageProvider` boundaries, which
 * take a `Bitmap` and nothing else.
 */
object AvatarBitmapUtils {
    private const val TAG = "AvatarBitmapUtils"

    /**
     * Decode [file] scaled down so its longest edge is at most [maxPx].
     *
     * Both callers care about the ceiling for the same underlying reason — an oversized bitmap gets
     * rescaled by the framework at an inconvenient moment. Notifications pay for it on the main
     * thread at `build()` time (StrictMode logs "Downscaling oversized Icon Bitmap"); widgets pay
     * for it in the RemoteViews IPC transaction, which is hard-capped and kills the update outright
     * when exceeded.
     *
     * Returns null if the file is missing, empty or undecodable.
     */
    fun decodeScaledBitmap(file: File, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            null
        } else {
            var sample = 1
            while ((w / sample) > maxPx * 2 || (h / sample) > maxPx * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts)
            when {
                raw == null -> null

                raw.width > maxPx || raw.height > maxPx -> {
                    val scale = maxPx.toFloat() / maxOf(raw.width, raw.height)
                    val tw = (raw.width * scale).toInt().coerceAtLeast(1)
                    val th = (raw.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
                    if (scaled !== raw) raw.recycle()
                    scaled
                }

                else -> raw
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "decodeScaledBitmap failed: ${file.absolutePath}", e)
        null
    }

    /**
     * Crop [bitmap] to a centred circle on a transparent background.
     *
     * Hardware bitmaps are copied to software first: `Canvas.drawBitmap` cannot read them.
     * `Bitmap.Config.HARDWARE` only exists on API 26+, so the version check short-circuits before
     * the constant is referenced on 24/25 (where it would throw `NoSuchFieldError`) — and no bitmap
     * can be HARDWARE there anyway.
     */
    fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        val isHardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        val softwareBitmap = if (isHardwareBitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap

        val size = minOf(softwareBitmap.width, softwareBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, size, size)
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)

        if (softwareBitmap !== bitmap) {
            softwareBitmap.recycle()
        }

        return output
    }

    /**
     * Draw an initials avatar for a user with no avatar (or whose avatar failed to load), using the
     * same colour and character rules as [AvatarUtils] so it matches what the timeline draws.
     *
     * The result is a filled square; wrap it in [createCircularBitmap] if a circle is wanted.
     */
    fun createFallbackAvatarBitmap(displayName: String?, userId: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = AvatarUtils.getUserColor(userId)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val character = AvatarUtils.getFallbackCharacter(displayName, userId)
        if (character.isNotEmpty()) {
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = size * 0.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val textBounds = Rect()
            textPaint.getTextBounds(character, 0, character.length, textBounds)
            canvas.drawText(character, size / 2f, size / 2f + textBounds.height() / 2f, textPaint)
        }

        return bitmap
    }
}
