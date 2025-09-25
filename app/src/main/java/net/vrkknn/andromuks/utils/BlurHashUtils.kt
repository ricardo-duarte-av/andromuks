package net.vrkknn.andromuks.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import net.vrkknn.andromuks.utils.BlurHashDecoder

object BlurHashUtils {
    /**
     * Decode a BlurHash string into a Bitmap
     */
    fun decodeBlurHash(blurHash: String, width: Int = 32, height: Int = 32): Bitmap? {
        return try {
            BlurHashDecoder.decode(blurHash, width, height)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Convert BlurHash to Compose ImageBitmap
     */
    fun blurHashToImageBitmap(blurHash: String, width: Int = 32, height: Int = 32): ImageBitmap? {
        return decodeBlurHash(blurHash, width, height)?.asImageBitmap()
    }
    
    /**
     * Create a placeholder bitmap with a solid color
     */
    fun createPlaceholderBitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = color.toArgb()
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap.asImageBitmap()
    }
}
