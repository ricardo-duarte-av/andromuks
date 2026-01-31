package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.withSign

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
    
    /**
     * Upload media to the server and return upload result with metadata
     */
    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false,
        compressOriginal: Boolean = false
    ): MediaUploadResult? = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Starting upload for URI: $uri")
            
            // Get file metadata
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to open input stream")
                return@withContext null
            }
            
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            
            val size = fileBytes.size.toLong()
            
            // Get mime type
            val mimeType = context.contentResolver.getType(uri) ?: getMimeTypeFromUri(uri.toString())
            
            // Get filename
            val filename = getFileNameFromUri(context, uri) ?: "image_${System.currentTimeMillis()}.jpg"
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: File size: $size bytes, mimeType: $mimeType, filename: $filename")
            
            // Read EXIF orientation to handle rotated images
            var exifOrientation = ExifInterface.ORIENTATION_NORMAL
            try {
                // Create a temporary file to read EXIF data (ExifInterface needs a file path)
                val tempFile = File.createTempFile("exif_", ".jpg", context.cacheDir)
                tempFile.outputStream().use { it.write(fileBytes) }
                val exif = ExifInterface(tempFile.absolutePath)
                exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                tempFile.delete()
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: EXIF orientation: $exifOrientation")
            } catch (e: Exception) {
                Log.w("Andromuks", "MediaUploadUtils: Failed to read EXIF orientation", e)
            }
            
            // Decode image to get dimensions and calculate blurhash
            val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
            if (bitmap == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to decode image")
                return@withContext null
            }
            
            // Get bitmap dimensions (may be swapped if EXIF orientation is 6 or 8)
            var bitmapWidth = bitmap.width
            var bitmapHeight = bitmap.height
            
            // For EXIF orientations 6 and 8, width and height are swapped in the bitmap
            // We need to swap them back to get the actual image dimensions
            val needsDimensionSwap = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 || 
                                     exifOrientation == ExifInterface.ORIENTATION_ROTATE_270
            
            val width: Int
            val height: Int
            
            if (needsDimensionSwap) {
                // Swap dimensions for 90/270 degree rotations
                width = bitmapHeight
                height = bitmapWidth
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Swapped dimensions due to EXIF orientation $exifOrientation")
            } else {
                width = bitmapWidth
                height = bitmapHeight
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Bitmap dimensions: ${bitmapWidth}x${bitmapHeight}, Actual image dimensions: ${width}x${height}")
            
            // Rotate bitmap to correct orientation if needed
            val orientedBitmap = when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    // Rotate 90 degrees clockwise (to correct for 90° CCW EXIF)
                    val matrix = Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, matrix, true)
                }
                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    val matrix = Matrix().apply { postRotate(180f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, matrix, true)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    // Rotate 270 degrees clockwise (to correct for 270° CCW EXIF)
                    val matrix = Matrix().apply { postRotate(270f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, matrix, true)
                }
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                    val matrix = Matrix().apply { postScale(-1f, 1f, bitmapWidth / 2f, bitmapHeight / 2f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, matrix, true)
                }
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    val matrix = Matrix().apply { postScale(1f, -1f, bitmapWidth / 2f, bitmapHeight / 2f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, matrix, true)
                }
                else -> bitmap // No rotation needed
            }
            
            // If we created a new bitmap, recycle the original
            val finalBitmap = if (orientedBitmap != bitmap) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Rotated bitmap to correct orientation")
                bitmap.recycle()
                orientedBitmap
            } else {
                bitmap
            }
            
            // Use the oriented bitmap's dimensions (should match width/height now)
            val finalBitmapWidth = finalBitmap.width
            val finalBitmapHeight = finalBitmap.height
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Final bitmap dimensions after orientation: ${finalBitmapWidth}x${finalBitmapHeight}")
            
            // Create a thumbnail (max dimension 400px, keep aspect ratio)
            // Only create thumbnail if image is larger than 400px in any dimension
            // Use finalBitmap dimensions (already oriented correctly)
            val maxThumbnailDimension = 400
            val needsThumbnail = finalBitmapWidth > maxThumbnailDimension || finalBitmapHeight > maxThumbnailDimension
            
            val thumbnail: Bitmap?
            val thumbnailWidth: Int?
            val thumbnailHeight: Int?
            val thumbnailMxcUrl: String?
            val thumbnailMimeType: String?
            val thumbnailSize: Long?
            
            if (needsThumbnail) {
                // Find the greater dimension (width or height) - use finalBitmap dimensions
                val greaterDimension = maxOf(finalBitmapWidth, finalBitmapHeight)
                // Calculate scale so that the greater dimension becomes 400px
                val scale = maxThumbnailDimension.toFloat() / greaterDimension
                // Calculate thumbnail dimensions maintaining aspect ratio
                // IMPORTANT: Use finalBitmap dimensions (already correctly oriented)
                val thumbWidth = (finalBitmapWidth * scale).toInt()
                val thumbHeight = (finalBitmapHeight * scale).toInt()
                
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Original bitmap: ${finalBitmapWidth}x${finalBitmapHeight}, Greater dimension: $greaterDimension, Scale: $scale")
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Calculated thumbnail dimensions: ${thumbWidth}x${thumbHeight}")
                
                // Create thumbnail bitmap with correct dimensions (width first, then height)
                thumbnail = Bitmap.createScaledBitmap(finalBitmap, thumbWidth, thumbHeight, true)
                
                // Get actual dimensions from the created thumbnail bitmap
                val actualThumbWidth = thumbnail.width
                val actualThumbHeight = thumbnail.height
                
                if (BuildConfig.DEBUG) {
                    Log.d("Andromuks", "MediaUploadUtils: Created thumbnail actual dimensions: ${actualThumbWidth}x${actualThumbHeight}")
                    if (actualThumbWidth != thumbWidth || actualThumbHeight != thumbHeight) {
                        Log.w("Andromuks", "MediaUploadUtils: WARNING - Thumbnail dimensions mismatch! Expected: ${thumbWidth}x${thumbHeight}, Got: ${actualThumbWidth}x${actualThumbHeight}")
                    }
                }
                
                // Convert thumbnail to bytes for upload
                // Always use JPEG for thumbnails to keep file size small
                val thumbnailOutputStream = ByteArrayOutputStream()
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, thumbnailOutputStream)
                val thumbnailBytes = thumbnailOutputStream.toByteArray()
                val thumbSize = thumbnailBytes.size.toLong()
                val thumbMimeType = "image/jpeg"
                
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Thumbnail size: $thumbSize bytes, mimeType: $thumbMimeType")
                
                val client = OkHttpClient.Builder()
                    .build()
                
                // Upload thumbnail first
                val thumbnailFilename = "thumb_${filename}"
                val thumbnailUploadUrl = buildUploadUrl(homeserverUrl, thumbnailFilename, isEncrypted)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Uploading thumbnail to: $thumbnailUploadUrl")
                
                val thumbnailRequestBody = thumbnailBytes.toRequestBody(thumbMimeType.toMediaType())
                val thumbnailRequest = Request.Builder()
                    .url(thumbnailUploadUrl)
                    .post(thumbnailRequestBody)
                    .addHeader("Cookie", "gomuks_auth=$authToken")
                    .addHeader("Content-Type", thumbMimeType)
                    .build()
                
                val thumbnailResponse = client.newCall(thumbnailRequest).execute()
                if (!thumbnailResponse.isSuccessful) {
                    Log.e("Andromuks", "MediaUploadUtils: Thumbnail upload failed with code: ${thumbnailResponse.code}")
                    Log.e("Andromuks", "MediaUploadUtils: Response body: ${thumbnailResponse.body?.string()}")
                    // Continue with original upload even if thumbnail fails
                }
                
                val thumbnailResponseBody = thumbnailResponse.body?.string()
                thumbnailMxcUrl = if (thumbnailResponse.isSuccessful) {
                    parseMxcUrlFromResponse(thumbnailResponseBody)
                } else {
                    null
                }
                
                // Use actual bitmap dimensions, not calculated ones (in case they differ)
                thumbnailWidth = actualThumbWidth
                thumbnailHeight = actualThumbHeight
                thumbnailMimeType = thumbMimeType
                thumbnailSize = thumbSize
                
                if (BuildConfig.DEBUG) {
                    if (thumbnailMxcUrl != null) {
                        Log.d("Andromuks", "MediaUploadUtils: Thumbnail upload successful, mxc URL: $thumbnailMxcUrl")
                    } else {
                        Log.w("Andromuks", "MediaUploadUtils: Thumbnail upload failed, continuing without thumbnail")
                    }
                }
            } else {
                // Image is already small, no thumbnail needed
                thumbnail = null
                thumbnailWidth = null
                thumbnailHeight = null
                thumbnailMimeType = null
                thumbnailSize = null
                thumbnailMxcUrl = null
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Image is already small (${width}x${height}), skipping thumbnail creation")
            }
            
            // Calculate blurhash from thumbnail if available, otherwise from full image
            // IMPORTANT: Do this BEFORE recycling the thumbnail bitmap
            val blurHashBitmap = thumbnail ?: finalBitmap
            val blurHash = encodeBlurHash(blurHashBitmap)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: BlurHash calculated: $blurHash")
            
            // Now we can safely recycle the thumbnail bitmap (if it's different from the final bitmap)
            if (needsThumbnail && thumbnail != null && thumbnail != finalBitmap) {
                thumbnail.recycle()
            }
            
            val client = OkHttpClient.Builder()
                .build()
            
            // Compress original image if requested
            val finalImageBytes: ByteArray
            val finalImageSize: Long
            val finalImageWidth: Int
            val finalImageHeight: Int
            
            if (compressOriginal) {
                // Determine if portrait or landscape
                val isPortrait = finalBitmapHeight > finalBitmapWidth
                val maxDimension = if (isPortrait) 1080 else 1920
                
                // Calculate scale to compress
                val greaterDimension = maxOf(finalBitmapWidth, finalBitmapHeight)
                val scale = if (greaterDimension > maxDimension) {
                    maxDimension.toFloat() / greaterDimension
                } else {
                    1.0f // No compression needed
                }
                
                val compressedWidth = (finalBitmapWidth * scale).toInt()
                val compressedHeight = (finalBitmapHeight * scale).toInt()
                
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Compressing original image from ${finalBitmapWidth}x${finalBitmapHeight} to ${compressedWidth}x${compressedHeight}")
                
                // Create compressed bitmap
                val compressedBitmap = Bitmap.createScaledBitmap(finalBitmap, compressedWidth, compressedHeight, true)
                
                // Convert to bytes
                val compressedOutputStream = ByteArrayOutputStream()
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, compressedOutputStream)
                finalImageBytes = compressedOutputStream.toByteArray()
                finalImageSize = finalImageBytes.size.toLong()
                finalImageWidth = compressedBitmap.width
                finalImageHeight = compressedBitmap.height
                
                // Recycle compressed bitmap
                if (compressedBitmap != finalBitmap) {
                    compressedBitmap.recycle()
                }
                
                if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Compressed image size: $finalImageSize bytes (original: ${fileBytes.size} bytes)")
            } else {
                // Use original file bytes
                finalImageBytes = fileBytes
                finalImageSize = size
                finalImageWidth = finalBitmapWidth
                finalImageHeight = finalBitmapHeight
            }
            
            // Upload original image (compressed or not)
            val uploadUrl = buildUploadUrl(homeserverUrl, filename, isEncrypted)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Upload URL: $uploadUrl")
            
            val requestBody = finalImageBytes.toRequestBody(mimeType.toMediaType())
            
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("Content-Type", mimeType)
                .build()
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Sending upload request...")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("Andromuks", "MediaUploadUtils: Upload failed with code: ${response.code}")
                Log.e("Andromuks", "MediaUploadUtils: Response body: ${response.body?.string()}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Upload response: $responseBody")
            
            // Parse response to get mxc:// URL
            val mxcUrl = parseMxcUrlFromResponse(responseBody)
            if (mxcUrl == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to parse mxc URL from response")
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Upload successful, mxc URL: $mxcUrl")
            
            MediaUploadResult(
                mxcUrl = mxcUrl,
                width = finalImageWidth,  // Use final image dimensions (compressed or original)
                height = finalImageHeight,
                size = finalImageSize,
                mimeType = mimeType,
                blurHash = blurHash,
                thumbnailUrl = thumbnailMxcUrl,
                thumbnailWidth = if (thumbnailMxcUrl != null) thumbnailWidth else null,
                thumbnailHeight = if (thumbnailMxcUrl != null) thumbnailHeight else null,
                thumbnailMimeType = if (thumbnailMxcUrl != null) thumbnailMimeType else null,
                thumbnailSize = if (thumbnailMxcUrl != null) thumbnailSize else null
            )
            
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: Upload failed", e)
            null
        }
    }
    
    /**
     * Build upload URL with query parameters
     */
    private fun buildUploadUrl(homeserverUrl: String, filename: String, isEncrypted: Boolean): String {
        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
        return "$homeserverUrl/_gomuks/upload?encrypt=$isEncrypted&progress=false&filename=$encodedFilename&resize_percent=100"
    }
    
    /**
     * Parse mxc:// URL from upload response
     * The response format is: {"url":"mxc://server/mediaId", ...}
     */
    private fun parseMxcUrlFromResponse(responseBody: String?): String? {
        if (responseBody == null) return null
        
        return try {
            val json = JSONObject(responseBody)
            // Try "url" field first (actual format from server)
            val mxcUrl = json.optString("url").takeIf { it.isNotEmpty() }
            if (mxcUrl != null && mxcUrl.startsWith("mxc://")) {
                mxcUrl
            } else {
                // Fallback to "mxc" field if present
                json.optString("mxc").takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: Failed to parse response JSON", e)
            null
        }
    }
    
    /**
     * Get mime type from URI
     */
    private fun getMimeTypeFromUri(uriString: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uriString)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    }
    
    /**
     * Get filename from URI
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        
        // Try to get filename from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        
        // Fallback to last path segment
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        
        return fileName
    }
    
    /**
     * Encode a bitmap to BlurHash string
     * Based on the BlurHash algorithm: https://github.com/woltapp/blurhash
     */
    fun encodeBlurHash(bitmap: Bitmap, componentsX: Int = 4, componentsY: Int = 3): String {
        val width = bitmap.width
        val height = bitmap.height
        
        val factors = Array(componentsX * componentsY) { FloatArray(3) }
        
        for (y in 0 until componentsY) {
            for (x in 0 until componentsX) {
                val factor = multiplyBasisFunction(bitmap, x, y, componentsX, componentsY, width, height)
                factors[y * componentsX + x] = factor
            }
        }
        
        val dc = factors[0]
        val ac = factors.sliceArray(1 until factors.size)
        
        var hash = ""
        
        val sizeFlag = (componentsX - 1) + (componentsY - 1) * 9
        hash += encode83(sizeFlag, 1)
        
        val maximumValue: Float
        if (ac.isNotEmpty()) {
            val actualMaximumValue = ac.maxOf { it.maxOrNull() ?: 0f }
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
    
    private fun multiplyBasisFunction(
        bitmap: Bitmap,
        xComponent: Int,
        yComponent: Int,
        componentsX: Int,
        componentsY: Int,
        width: Int,
        height: Int
    ): FloatArray {
        var r = 0f
        var g = 0f
        var b = 0f
        val normalisation = if (xComponent == 0 && yComponent == 0) 1f else 2f
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val basis = (normalisation *
                        cos(PI * xComponent * x / width) *
                        cos(PI * yComponent * y / height)).toFloat()
                
                val pixel = bitmap.getPixel(x, y)
                r += basis * sRGBToLinear((pixel shr 16) and 0xFF)
                g += basis * sRGBToLinear((pixel shr 8) and 0xFF)
                b += basis * sRGBToLinear(pixel and 0xFF)
            }
        }
        
        val scale = 1f / (width * height)
        return floatArrayOf(r * scale, g * scale, b * scale)
    }
    
    private fun sRGBToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).pow(2.4f)
        }
    }
    
    private fun linearToSRGB(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) {
            (v * 12.92f * 255f + 0.5f).toInt()
        } else {
            ((1.055f * v.pow(1f / 2.4f) - 0.055f) * 255f + 0.5f).toInt()
        }
    }
    
    private fun encodeDC(value: FloatArray): Int {
        val r = linearToSRGB(value[0])
        val g = linearToSRGB(value[1])
        val b = linearToSRGB(value[2])
        return (r shl 16) + (g shl 8) + b
    }
    
    private fun encodeAC(value: FloatArray, maximumValue: Float): Int {
        val quantR = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[0] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        val quantG = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[1] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        val quantB = kotlin.math.max(0, kotlin.math.min(18, kotlin.math.floor(signPow(value[2] / maximumValue, 0.5f) * 9 + 9.5).toInt()))
        
        return quantR * 19 * 19 + quantG * 19 + quantB
    }
    
    private fun signPow(value: Float, exp: Float): Float {
        return value.absoluteValue.pow(exp).withSign(value)
    }
    
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
    
    /**
     * Upload audio to the server and return upload result with metadata
     */
    suspend fun uploadAudio(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false
    ): AudioUploadResult? = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Starting audio upload for URI: $uri")
            
            // Get file metadata
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to open audio input stream")
                return@withContext null
            }
            
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            
            val size = fileBytes.size.toLong()
            
            // Get mime type
            val mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg"
            
            // Get filename
            val filename = getFileNameFromUri(context, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Audio file size: $size bytes, mimeType: $mimeType, filename: $filename")
            
            // Get audio duration
            val duration = getAudioDuration(context, uri)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Audio duration: ${duration}ms")
            
            // Upload to server
            val uploadUrl = buildUploadUrl(homeserverUrl, filename, isEncrypted)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Upload URL: $uploadUrl")
            
            val client = OkHttpClient.Builder()
                .build()
            
            val requestBody = fileBytes.toRequestBody(mimeType.toMediaType())
            
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("Content-Type", mimeType)
                .build()
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Sending audio upload request...")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("Andromuks", "MediaUploadUtils: Audio upload failed with code: ${response.code}")
                Log.e("Andromuks", "MediaUploadUtils: Response body: ${response.body?.string()}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Audio upload response: $responseBody")
            
            // Parse response to get mxc:// URL
            val mxcUrl = parseMxcUrlFromResponse(responseBody)
            if (mxcUrl == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to parse mxc URL from audio response")
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Audio upload successful, mxc URL: $mxcUrl")
            
            AudioUploadResult(
                mxcUrl = mxcUrl,
                duration = duration,
                size = size,
                mimeType = mimeType,
                filename = filename
            )
            
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: Audio upload failed", e)
            null
        }
    }
    
    /**
     * Upload file to the server and return upload result with metadata
     */
    suspend fun uploadFile(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false
    ): FileUploadResult? = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Starting file upload for URI: $uri")
            
            // Get file metadata
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to open file input stream")
                return@withContext null
            }
            
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            
            val size = fileBytes.size.toLong()
            
            // Get mime type
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            
            // Get filename
            val filename = getFileNameFromUri(context, uri) ?: "file_${System.currentTimeMillis()}"
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: File size: $size bytes, mimeType: $mimeType, filename: $filename")
            
            // Upload to server
            val uploadUrl = buildUploadUrl(homeserverUrl, filename, isEncrypted)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Upload URL: $uploadUrl")
            
            val client = OkHttpClient.Builder()
                .build()
            
            val requestBody = fileBytes.toRequestBody(mimeType.toMediaType())
            
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("Content-Type", mimeType)
                .build()
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: Sending file upload request...")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("Andromuks", "MediaUploadUtils: File upload failed with code: ${response.code}")
                Log.e("Andromuks", "MediaUploadUtils: Response body: ${response.body?.string()}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: File upload response: $responseBody")
            
            // Parse response to get mxc:// URL
            val mxcUrl = parseMxcUrlFromResponse(responseBody)
            if (mxcUrl == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to parse mxc URL from file response")
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "MediaUploadUtils: File upload successful, mxc URL: $mxcUrl")
            
            FileUploadResult(
                mxcUrl = mxcUrl,
                size = size,
                mimeType = mimeType,
                filename = filename
            )
            
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUploadUtils: File upload failed", e)
            null
        }
    }
    
    /**
     * Get audio duration from URI using MediaPlayer
     */
    private suspend fun getAudioDuration(context: Context, uri: Uri): Int {
        return withContext(Dispatchers.IO) {
            try {
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(context, uri)
                mediaPlayer.prepare()
                val duration = mediaPlayer.duration
                mediaPlayer.release()
                duration
            } catch (e: Exception) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to get audio duration", e)
                0 // Return 0 if duration cannot be determined
            }
        }
    }
}

