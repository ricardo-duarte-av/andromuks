package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONObject
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign
import net.vrkknn.andromuks.utils.getUserAgent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data class representing the result of a media upload
 */
data class MediaUploadResult(
    val mxcUrl: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val mimeType: String,
    val blurHash: String,
    val thumbnailUrl: String? = null,
    val thumbnailWidth: Int? = null,
    val thumbnailHeight: Int? = null,
    val thumbnailMimeType: String? = null,
    val thumbnailSize: Long? = null
)

/**
 * Request body that reports progress
 */
class ProgressRequestBody(
    private val contentType: okhttp3.MediaType?,
    private val contentLength: Long,
    private val source: okio.Source,
    private val onProgress: (Long) -> Unit
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength() = contentLength
    override fun writeTo(sink: BufferedSink) {
        val buffer = okio.Buffer()
        var totalWritten = 0L
        while (true) {
            val count = source.read(buffer, 8192)
            if (count == -1L) break
            sink.write(buffer, count)
            totalWritten += count
            onProgress(totalWritten)
        }
    }
}

/**
 * Data class representing the result of an audio upload
 */
data class AudioUploadResult(
    val mxcUrl: String,
    val duration: Int, // duration in milliseconds
    val size: Long,
    val mimeType: String,
    val filename: String
)

/**
 * Data class representing the result of a file upload
 */
data class FileUploadResult(
    val mxcUrl: String,
    val size: Long,
    val mimeType: String,
    val filename: String
)

/**
 * Utilities for uploading media files and calculating blurhash
 */
object MediaUploadUtils {

    /** Shared OkHttpClient for all uploads to reuse connections (faster than creating one per request). */
    internal val sharedUploadClient by lazy { 
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS) // Use unlimited for large uploads
            .readTimeout(0, TimeUnit.SECONDS)
            .build() 
    }

    /**
     * Get content length from URI without loading into memory. Returns -1 if unknown.
     */
    private fun getContentLength(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Create a high-quality thumbnail with subtle blur to reduce pixelation
     * Uses better scaling algorithm and applies a subtle Gaussian blur
     */
    internal fun createHighQualityThumbnail(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val scaledBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.isDither = true
        
        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        
        return scaledBitmap
    }
    
    
    private fun applySinglePassBoxBlur(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurredPixels = IntArray(width * height)
        
        val kernelSum = 9
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val px = (x + kx).coerceIn(0, width - 1)
                        val py = (y + ky).coerceIn(0, height - 1)
                        val pixel = pixels[py * width + px]
                        
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        a += (pixel shr 24) and 0xFF
                    }
                }
                
                r /= kernelSum
                g /= kernelSum
                b /= kernelSum
                a /= kernelSum
                
                blurredPixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        blurred.setPixels(blurredPixels, 0, width, 0, 0, width, height)
        return blurred
    }
    
    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false,
        compressOriginal: Boolean = false,
        onProgress: ((String, Float) -> Unit)? = null
    ): MediaUploadResult? = withContext(Dispatchers.IO) {
        onProgress?.invoke("thumbnail", 0.05f)
        onProgress?.invoke("original", 0.05f)
        
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Starting upload for URI: $uri")
            
            val mimeType = context.contentResolver.getType(uri) ?: getMimeTypeFromUri(uri.toString())
            val filename = getFileNameFromUri(context, uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val totalSize = getContentLength(context, uri)
            
            val (width, height, exifOrientation) = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                
                val exif = ExifInterface(pfd.fileDescriptor)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                
                val needsSwap = orientation == ExifInterface.ORIENTATION_ROTATE_90 || 
                                orientation == ExifInterface.ORIENTATION_ROTATE_270
                
                if (needsSwap) Triple(options.outHeight, options.outWidth, orientation)
                else Triple(options.outWidth, options.outHeight, orientation)
            } ?: Triple(0, 0, ExifInterface.ORIENTATION_NORMAL)

            val needsProcessing = compressOriginal || exifOrientation != ExifInterface.ORIENTATION_NORMAL
            
            val maxThumbDim = 800
            val inSampleSize = calculateInSampleSize(width, height, maxThumbDim, maxThumbDim)
            
            val baseBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })
            } ?: return@withContext null
            
            val orientedBase = if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
                val matrix = Matrix().apply { 
                    when (exifOrientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    }
                }
                Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.width, baseBitmap.height, matrix, true).also {
                    if (it != baseBitmap) baseBitmap.recycle()
                }
            } else baseBitmap

            val thumbWidth = if (orientedBase.width > orientedBase.height) maxThumbDim else (maxThumbDim * orientedBase.width / orientedBase.height)
            val thumbHeight = if (orientedBase.height > orientedBase.width) maxThumbDim else (maxThumbDim * orientedBase.height / orientedBase.width)
            
            val thumbnail = createHighQualityThumbnail(orientedBase, thumbWidth, thumbHeight)
            
            // OPTIMIZATION: Scale down thumbnail drastically for BlurHash calculation
            val blurHashInput = Bitmap.createScaledBitmap(thumbnail, 64, 64, true)
            val blurHash = encodeBlurHash(blurHashInput)
            if (blurHashInput != thumbnail) blurHashInput.recycle()
            
            val thumbBytes = ByteArrayOutputStream().use { 
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)
                it.toByteArray()
            }
            thumbnail.recycle()
            orientedBase.recycle()

            val thumbRequestBody = ProgressRequestBody(
                "image/jpeg".toMediaType(),
                thumbBytes.size.toLong(),
                java.io.ByteArrayInputStream(thumbBytes).source()
            ) { written ->
                onProgress?.invoke("thumbnail", written.toFloat() / thumbBytes.size)
            }

            val thumbRequest = Request.Builder()
                .url(buildUploadUrl(homeserverUrl, "thumb_$filename", isEncrypted))
                .post(thumbRequestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("User-Agent", getUserAgent())
                .build()

            val (mainRequestBody, finalWidth, finalHeight, finalSize) = if (needsProcessing) {
                val processedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }?.let { bitmap ->
                    val matrix = Matrix()
                    if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
                        when (exifOrientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        }
                    }
                    if (compressOriginal) {
                        val maxDim = if (height > width) 1080 else 1920
                        val scale = maxOf(bitmap.width, bitmap.height).let { if (it > maxDim) maxDim.toFloat() / it else 1.0f }
                        if (scale < 1.0f) matrix.postScale(scale, scale)
                    }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                        if (it != bitmap) bitmap.recycle()
                    }
                } ?: return@withContext null
                
                val bytes = ByteArrayOutputStream().use { 
                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                    it.toByteArray()
                }
                val w = processedBitmap.width
                val h = processedBitmap.height
                processedBitmap.recycle()
                
                val body = ProgressRequestBody(
                    "image/jpeg".toMediaType(),
                    bytes.size.toLong(),
                    java.io.ByteArrayInputStream(bytes).source()
                ) { written ->
                    onProgress?.invoke("original", written.toFloat() / bytes.size)
                }
                Quadruple(body, w, h, bytes.size.toLong())
            } else {
                val source = context.contentResolver.openInputStream(uri)!!.source()
                val body = ProgressRequestBody(mimeType.toMediaType(), totalSize, source) { written ->
                    onProgress?.invoke("original", written.toFloat() / totalSize)
                }
                Quadruple(body, width, height, totalSize)
            }

            val mainRequest = Request.Builder()
                .url(buildUploadUrl(homeserverUrl, filename, isEncrypted))
                .post(mainRequestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("User-Agent", getUserAgent())
                .build()

            coroutineScope {
                val thumbDeferred = async { executeAndParseProgress(thumbRequest) { _ -> /* Using outgoing progress */ } }
                val mainDeferred = async { executeAndParseProgress(mainRequest) { _ -> /* Using outgoing progress */ } }
                
                val thumbMxc = thumbDeferred.await()
                val mainMxc = mainDeferred.await() ?: return@coroutineScope null

                MediaUploadResult(
                    mxcUrl = mainMxc,
                    width = finalWidth,
                    height = finalHeight,
                    size = finalSize,
                    mimeType = if (needsProcessing) "image/jpeg" else mimeType,
                    blurHash = blurHash,
                    thumbnailUrl = thumbMxc,
                    thumbnailWidth = thumbWidth,
                    thumbnailHeight = thumbHeight,
                    thumbnailMimeType = "image/jpeg",
                    thumbnailSize = thumbBytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: Upload failed", e)
            null
        }
    }
    
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    internal suspend fun executeAndParseProgress(request: Request, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            val response = sharedUploadClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@withContext null))
            var line: String?
            var finalMxc: String? = null
            
            while (reader.readLine().also { line = it } != null) {
                try {
                    val json = JSONObject(line!!)
                    if (json.has("current") && json.has("total")) {
                        val current = json.optLong("current")
                        val total = json.optLong("total")
                        if (total > 0) onProgress(current.toFloat() / total)
                    } else if (json.has("url") || json.has("mxc")) {
                        finalMxc = json.optString("url").takeIf { it.isNotEmpty() } ?: json.optString("mxc")
                    }
                } catch (e: Exception) {
                }
            }
            finalMxc
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: Failed to parse progress stream", e)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    internal fun buildUploadUrl(homeserverUrl: String, filename: String, isEncrypted: Boolean): String {
        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
        return "$homeserverUrl/_gomuks/upload?encrypt=$isEncrypted&progress=true&filename=$encodedFilename&resize_percent=100"
    }
    
    private fun parseMxcUrlFromResponse(responseBody: String?): String? {
        if (responseBody == null) return null
        return try {
            val json = JSONObject(responseBody)
            val mxcUrl = json.optString("url").takeIf { it.isNotEmpty() }
            if (mxcUrl != null && mxcUrl.startsWith("mxc://")) mxcUrl
            else json.optString("mxc").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getMimeTypeFromUri(uriString: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uriString)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    }
    
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName ?: uri.lastPathSegment
    }
    
    fun encodeBlurHash(bitmap: Bitmap, componentsX: Int = 4, componentsY: Int = 3): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val factors = Array(componentsX * componentsY) { FloatArray(3) }
        for (y in 0 until componentsY) {
            for (x in 0 until componentsX) {
                factors[y * componentsX + x] = multiplyBasisFunction(pixels, x, y, width, height)
            }
        }
        val dc = factors[0]
        val ac = factors.sliceArray(1 until factors.size)
        var hash = ""
        val sizeFlag = (componentsX - 1) + (componentsY - 1) * 9
        hash += encode83(sizeFlag, 1)
        val maximumValue: Float
        if (ac.isNotEmpty()) {
            val actualMaximumValue = ac.maxOf { it.maxOf { v -> v.absoluteValue } }
            val quantisedMaximumValue = kotlin.math.max(0, (actualMaximumValue * 166 - 0.5).toInt()).coerceAtMost(82)
            maximumValue = (quantisedMaximumValue + 1) / 166f
            hash += encode83(quantisedMaximumValue, 1)
        } else {
            maximumValue = 1f
            hash += encode83(0, 1)
        }
        hash += encode83(encodeDC(dc), 4)
        for (factor in ac) {
            hash += encode83(encodeAC(factor, maximumValue), 2)
        }
        return hash
    }
    
    private val sRGBToLinearTable = FloatArray(256) { sRGBToLinear(it) }

    private fun multiplyBasisFunction(pixels: IntArray, xComponent: Int, yComponent: Int, width: Int, height: Int): FloatArray {
        var r = 0f
        var g = 0f
        var b = 0f
        val normalisation = if (xComponent == 0 && yComponent == 0) 1f else 2f
        
        // Pre-calculate basis functions for x and y components
        val basisX = FloatArray(width) { x -> cos(PI * xComponent * (x + 0.5) / width).toFloat() }
        val basisY = FloatArray(height) { y -> cos(PI * yComponent * (y + 0.5) / height).toFloat() }

        for (y in 0 until height) {
            val by = basisY[y]
            for (x in 0 until width) {
                val basis = normalisation * basisX[x] * by
                val pixel = pixels[y * width + x]
                
                r += basis * sRGBToLinearTable[(pixel shr 16) and 0xFF]
                g += basis * sRGBToLinearTable[(pixel shr 8) and 0xFF]
                b += basis * sRGBToLinearTable[pixel and 0xFF]
            }
        }
        val scale = 1f / (width * height)
        return floatArrayOf(r * scale, g * scale, b * scale)
    }
    
    private fun sRGBToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }
    
    private fun linearToSRGB(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) (v * 12.92f * 255f + 0.5f).toInt()
        else ((1.055f * v.pow(1f / 2.4f) - 0.055f) * 255f + 0.5f).toInt()
    }
    
    private fun encodeDC(value: FloatArray): Int = (linearToSRGB(value[0]) shl 16) + (linearToSRGB(value[1]) shl 8) + linearToSRGB(value[2])
    
    private fun encodeAC(value: FloatArray, maximumValue: Float): Int {
        val quantR = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[0] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        val quantG = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[1] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        val quantB = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[2] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        return quantR * 19 * 19 + quantG * 19 + quantB
    }
    
    private fun signPow(value: Float, exp: Float): Float = value.absoluteValue.pow(exp).withSign(value)
    
    private fun encode83(value: Int, length: Int): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
        var result = ""
        var v = value
        for (i in 1..length) {
            val digit = v % 83
            v /= 83
            result = chars[digit] + result
        }
        return result
    }
    
    suspend fun uploadAudio(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false,
        onProgress: ((String, Float) -> Unit)? = null
    ): AudioUploadResult? = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val filename = getFileNameFromUri(context, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
            val size = getContentLength(context, uri)
            val duration = getAudioDuration(context, uri)
            val requestBody = ProgressRequestBody(mimeType.toMediaType(), size, context.contentResolver.openInputStream(uri)!!.source()) { written ->
                onProgress?.invoke("original", written.toFloat() / size)
            }
            val request = Request.Builder().url(buildUploadUrl(homeserverUrl, filename, isEncrypted)).post(requestBody).addHeader("Cookie", "gomuks_auth=$authToken").addHeader("User-Agent", getUserAgent()).build()
            val mxcUrl = executeAndParseProgress(request) { _ -> } ?: return@withContext null
            AudioUploadResult(mxcUrl = mxcUrl, duration = duration, size = size, mimeType = mimeType, filename = filename)
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun uploadFile(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false,
        onProgress: ((String, Float) -> Unit)? = null
    ): FileUploadResult? = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val filename = getFileNameFromUri(context, uri) ?: "file_${System.currentTimeMillis()}"
            val size = getContentLength(context, uri)
            val requestBody = ProgressRequestBody(mimeType.toMediaType(), size, context.contentResolver.openInputStream(uri)!!.source()) { written ->
                onProgress?.invoke("original", written.toFloat() / size)
            }
            val request = Request.Builder().url(buildUploadUrl(homeserverUrl, filename, isEncrypted)).post(requestBody).addHeader("Cookie", "gomuks_auth=$authToken").addHeader("User-Agent", getUserAgent()).build()
            val mxcUrl = executeAndParseProgress(request) { _ -> } ?: return@withContext null
            FileUploadResult(mxcUrl = mxcUrl, size = size, mimeType = mimeType, filename = filename)
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun getAudioDuration(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            MediaPlayer().use { mp ->
                mp.setDataSource(context, uri)
                mp.prepare()
                mp.duration
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private inline fun <T : MediaPlayer, R> T.use(block: (T) -> R): R {
        return try {
            block(this)
        } finally {
            try { release() } catch (e: Exception) { }
        }
    }
}
