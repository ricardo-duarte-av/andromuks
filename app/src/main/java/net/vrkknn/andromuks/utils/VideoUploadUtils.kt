package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Data class representing the result of a video upload
 */
data class VideoUploadResult(
    val videoMxcUrl: String,
    val thumbnailMxcUrl: String,
    val width: Int,
    val height: Int,
    val duration: Int, // in milliseconds
    val size: Long,
    val mimeType: String,
    val thumbnailBlurHash: String,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val thumbnailSize: Long
)

/**
 * Utilities for uploading videos with thumbnail extraction
 */
object VideoUploadUtils {
    
    /**
     * Upload video to the server with thumbnail extraction
     */
    suspend fun uploadVideo(
        context: Context,
        uri: Uri,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean = false
    ): VideoUploadResult? = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Starting video upload for URI: $uri")
            
            // Extract video metadata
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            
            // Get video dimensions
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            // Get video duration in milliseconds
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Video dimensions: ${width}x${height}, duration: ${duration}ms")
            
            // Extract frame at 10% of video duration for thumbnail
            val thumbnailTimeUs = (duration * 1000L * 0.1).toLong() // Convert to microseconds and get 10%
            var thumbnailFrame = retriever.getFrameAtTime(thumbnailTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            
            // Fallback to first frame if extraction fails
            if (thumbnailFrame == null) {
                Log.w("Andromuks", "VideoUploadUtils: Failed to extract frame at 10%, trying first frame")
                thumbnailFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            
            if (thumbnailFrame == null) {
                Log.e("Andromuks", "VideoUploadUtils: Failed to extract any frame from video")
                retriever.release()
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Extracted thumbnail frame: ${thumbnailFrame.width}x${thumbnailFrame.height}")
            
            // Use the frame dimensions for video dimensions (they're already correctly oriented)
            // This handles cases where the video has rotation metadata
            val displayWidth = thumbnailFrame.width
            val displayHeight = thumbnailFrame.height
            
            // Thumbnail the frame to max 400px (same as images) while maintaining aspect ratio
            val maxThumbnailDimension = 400
            val thumbnailBitmap = if (displayWidth > maxThumbnailDimension || displayHeight > maxThumbnailDimension) {
                // Calculate scale based on the greater dimension
                val scale = if (displayWidth > displayHeight) {
                    maxThumbnailDimension.toFloat() / displayWidth
                } else {
                    maxThumbnailDimension.toFloat() / displayHeight
                }
                val thumbnailWidth = (displayWidth * scale).toInt()
                val thumbnailHeight = (displayHeight * scale).toInt()
                
                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Scaling thumbnail to ${thumbnailWidth}x${thumbnailHeight}")
                // Use high-quality thumbnail creation with subtle blur to reduce pixelation
                MediaUploadUtils.createHighQualityThumbnail(thumbnailFrame, thumbnailWidth, thumbnailHeight, blurRadius = 1.5f).also {
                    if (it != thumbnailFrame) {
                        thumbnailFrame.recycle()
                    }
                }
            } else {
                thumbnailFrame
            }
            
            // Compress thumbnail to JPEG (quality 85, same as images) to keep file size reasonable
            val thumbnailOutputStream = ByteArrayOutputStream()
            thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 85, thumbnailOutputStream)
            val thumbnailBytes = thumbnailOutputStream.toByteArray()
            val thumbnailSize = thumbnailBytes.size.toLong()
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Thumbnail JPEG size: $thumbnailSize bytes")
            
            // Calculate blurhash from thumbnail (use smaller version for speed)
            val blurHashSize = 400
            val blurHashScale = minOf(blurHashSize.toFloat() / thumbnailBitmap.width, blurHashSize.toFloat() / thumbnailBitmap.height)
            val blurHashWidth = (thumbnailBitmap.width * blurHashScale).toInt()
            val blurHashHeight = (thumbnailBitmap.height * blurHashScale).toInt()
            val blurHashBitmap = Bitmap.createScaledBitmap(thumbnailBitmap, blurHashWidth, blurHashHeight, true)
            
            val thumbnailBlurHash = MediaUploadUtils.encodeBlurHash(blurHashBitmap)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Thumbnail BlurHash calculated: $thumbnailBlurHash")
            
            if (blurHashBitmap != thumbnailBitmap) {
                blurHashBitmap.recycle()
            }
            
            // Upload thumbnail first
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Uploading thumbnail...")
            val thumbnailFilename = "video_thumbnail_${System.currentTimeMillis()}.jpg"
            val thumbnailMxcUrl = uploadBytes(
                bytes = thumbnailBytes,
                filename = thumbnailFilename,
                mimeType = "image/jpeg",
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = isEncrypted
            )
            
            if (thumbnailMxcUrl == null) {
                Log.e("Andromuks", "VideoUploadUtils: Failed to upload thumbnail")
                thumbnailBitmap.recycle()
                retriever.release()
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Thumbnail uploaded: $thumbnailMxcUrl")
            
            // Save thumbnail dimensions before recycling
            val thumbnailWidth = thumbnailBitmap.width
            val thumbnailHeight = thumbnailBitmap.height
            
            // Clean up thumbnail bitmap
            thumbnailBitmap.recycle()
            retriever.release()
            
            // Get video file bytes and metadata
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Andromuks", "VideoUploadUtils: Failed to open video input stream")
                return@withContext null
            }
            
            val videoBytes = inputStream.readBytes()
            inputStream.close()
            val videoSize = videoBytes.size.toLong()
            
            // Get mime type
            val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
            
            // Get filename
            val videoFilename = getFileNameFromUri(context, uri) ?: "video_${System.currentTimeMillis()}.mp4"
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Video size: $videoSize bytes, mimeType: $mimeType, filename: $videoFilename")
            
            // Upload video
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Uploading video...")
            val videoMxcUrl = uploadBytes(
                bytes = videoBytes,
                filename = videoFilename,
                mimeType = mimeType,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = isEncrypted
            )
            
            if (videoMxcUrl == null) {
                Log.e("Andromuks", "VideoUploadUtils: Failed to upload video")
                return@withContext null
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Video uploaded: $videoMxcUrl")
            
            VideoUploadResult(
                videoMxcUrl = videoMxcUrl,
                thumbnailMxcUrl = thumbnailMxcUrl,
                width = displayWidth,  // Use display dimensions from frame (correctly oriented)
                height = displayHeight, // Use display dimensions from frame (correctly oriented)
                duration = duration,
                size = videoSize,
                mimeType = mimeType,
                thumbnailBlurHash = thumbnailBlurHash,
                thumbnailWidth = thumbnailWidth,
                thumbnailHeight = thumbnailHeight,
                thumbnailSize = thumbnailSize
            )
            
        } catch (e: Exception) {
            Log.e("Andromuks", "VideoUploadUtils: Video upload failed", e)
            null
        }
    }
    
    /**
     * Upload raw bytes to the server
     */
    private suspend fun uploadBytes(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        homeserverUrl: String,
        authToken: String,
        isEncrypted: Boolean
    ): String? = withContext(Dispatchers.IO) {
        try {
            val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
            val uploadUrl = "$homeserverUrl/_gomuks/upload?encrypt=$isEncrypted&progress=false&filename=$encodedFilename&resize_percent=100"
            
            val client = okhttp3.OkHttpClient.Builder().build()
            
            val requestBody = bytes.toRequestBody(mimeType.toMediaType())
            
            val request = okhttp3.Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("Content-Type", mimeType)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("Andromuks", "VideoUploadUtils: Upload failed with code: ${response.code}")
                Log.e("Andromuks", "VideoUploadUtils: Response body: ${response.body?.string()}")
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            
            // Parse mxc URL from response
            val json = org.json.JSONObject(responseBody ?: "")
            val mxcUrl = json.optString("url").takeIf { it.isNotEmpty() }
            
            if (mxcUrl != null && mxcUrl.startsWith("mxc://")) {
                mxcUrl
            } else {
                json.optString("mxc").takeIf { it.isNotEmpty() }
            }
            
        } catch (e: Exception) {
            Log.e("Andromuks", "VideoUploadUtils: Upload bytes failed", e)
            null
        }
    }
    
    /**
     * Get filename from URI
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        
        return fileName
    }
}

