package net.vrkknn.andromuks.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
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
    val blurHash: String
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
        isEncrypted: Boolean = false
    ): MediaUploadResult? = withContext(Dispatchers.IO) {
        try {
            Log.d("Andromuks", "MediaUploadUtils: Starting upload for URI: $uri")
            
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
            
            Log.d("Andromuks", "MediaUploadUtils: File size: $size bytes, mimeType: $mimeType, filename: $filename")
            
            // Decode image to get dimensions and calculate blurhash
            val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
            if (bitmap == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to decode image")
                return@withContext null
            }
            
            val width = bitmap.width
            val height = bitmap.height
            
            Log.d("Andromuks", "MediaUploadUtils: Image dimensions: ${width}x${height}")
            
            // Create a small thumbnail for blurhash calculation (much faster)
            val thumbnailSize = 400
            val scale = minOf(thumbnailSize.toFloat() / width, thumbnailSize.toFloat() / height)
            val thumbnailWidth = (width * scale).toInt()
            val thumbnailHeight = (height * scale).toInt()
            val thumbnail = Bitmap.createScaledBitmap(bitmap, thumbnailWidth, thumbnailHeight, true)
            
            Log.d("Andromuks", "MediaUploadUtils: Created thumbnail for blurhash: ${thumbnailWidth}x${thumbnailHeight}")
            
            // Calculate blurhash from thumbnail (much faster than full image)
            val blurHash = encodeBlurHash(thumbnail)
            Log.d("Andromuks", "MediaUploadUtils: BlurHash calculated: $blurHash")
            
            // Clean up thumbnail
            if (thumbnail != bitmap) {
                thumbnail.recycle()
            }
            
            // Upload to server
            val uploadUrl = buildUploadUrl(homeserverUrl, filename, isEncrypted)
            Log.d("Andromuks", "MediaUploadUtils: Upload URL: $uploadUrl")
            
            val client = OkHttpClient.Builder()
                .build()
            
            val requestBody = fileBytes.toRequestBody(mimeType.toMediaType())
            
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("Content-Type", mimeType)
                .build()
            
            Log.d("Andromuks", "MediaUploadUtils: Sending upload request...")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("Andromuks", "MediaUploadUtils: Upload failed with code: ${response.code}")
                Log.e("Andromuks", "MediaUploadUtils: Response body: ${response.body?.string()}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            Log.d("Andromuks", "MediaUploadUtils: Upload response: $responseBody")
            
            // Parse response to get mxc:// URL
            val mxcUrl = parseMxcUrlFromResponse(responseBody)
            if (mxcUrl == null) {
                Log.e("Andromuks", "MediaUploadUtils: Failed to parse mxc URL from response")
                return@withContext null
            }
            
            Log.d("Andromuks", "MediaUploadUtils: Upload successful, mxc URL: $mxcUrl")
            
            MediaUploadResult(
                mxcUrl = mxcUrl,
                width = width,
                height = height,
                size = size,
                mimeType = mimeType,
                blurHash = blurHash
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
            val mxcUrl = json.optString("url", null)
            if (mxcUrl != null && mxcUrl.startsWith("mxc://")) {
                mxcUrl
            } else {
                // Fallback to "mxc" field if present
                json.optString("mxc", null)
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
}

