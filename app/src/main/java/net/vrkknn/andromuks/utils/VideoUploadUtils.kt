package net.vrkknn.andromuks.utils

import okio.source

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import net.vrkknn.andromuks.utils.getUserAgent

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
        isEncrypted: Boolean = false,
        onProgress: ((String, Float) -> Unit)? = null
    ): VideoUploadResult? = withContext(Dispatchers.IO) {
        onProgress?.invoke("thumbnail", 0.01f)
        onProgress?.invoke("original", 0.01f)
        
        try {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoUploadUtils: Starting video upload for URI: $uri")
            
            // Extract video metadata
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            
            // Get video dimensions and duration
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            
            // Extract frame at 10% for thumbnail
            val thumbnailTimeUs = (duration * 1000L * 0.1).toLong()
            var thumbnailFrame = retriever.getFrameAtTime(thumbnailTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            
            if (thumbnailFrame == null) {
                Log.e("Andromuks", "VideoUploadUtils: Failed to extract frame")
                retriever.release()
                return@withContext null
            }
            
            val displayWidth = thumbnailFrame.width
            val displayHeight = thumbnailFrame.height
            
            // Create high-quality thumbnail
            val maxThumbDim = 800
            val thumbnailBitmap = if (displayWidth > maxThumbDim || displayHeight > maxThumbDim) {
                val scale = maxThumbDim.toFloat() / maxOf(displayWidth, displayHeight)
                MediaUploadUtils.createHighQualityThumbnail(thumbnailFrame, (displayWidth * scale).toInt(), (displayHeight * scale).toInt()).also {
                    if (it != thumbnailFrame) thumbnailFrame.recycle()
                }
            } else thumbnailFrame
            
            val thumbBytes = ByteArrayOutputStream().use { 
                thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                it.toByteArray()
            }
            
            // OPTIMIZATION: Scale down for BlurHash calculation
            val blurHashInput = Bitmap.createScaledBitmap(thumbnailBitmap, 64, 64, true)
            val thumbnailBlurHash = MediaUploadUtils.encodeBlurHash(blurHashInput)
            if (blurHashInput != thumbnailBitmap) blurHashInput.recycle()

            val thumbWidth = thumbnailBitmap.width
            val thumbHeight = thumbnailBitmap.height
            thumbnailBitmap.recycle()
            retriever.release()

            val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
            val videoFilename = getFileNameFromUri(context, uri) ?: "video_${System.currentTimeMillis()}.mp4"
            val contentLength = getContentLength(context, uri)

            // Prepare requests
            val thumbRequest = Request.Builder()
                .url(MediaUploadUtils.buildUploadUrl(homeserverUrl, "thumb_$videoFilename", isEncrypted))
                .post(thumbBytes.toRequestBody("image/jpeg".toMediaType()))
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("User-Agent", getUserAgent())
                .build()

            val videoRequestBody = ProgressRequestBody(
                mimeType.toMediaType(),
                contentLength,
                context.contentResolver.openInputStream(uri)!!.source()
            ) { written ->
                onProgress?.invoke("original", written.toFloat() / contentLength)
            }

            val videoRequest = Request.Builder()
                .url(MediaUploadUtils.buildUploadUrl(homeserverUrl, videoFilename, isEncrypted))
                .post(videoRequestBody)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .addHeader("User-Agent", getUserAgent())
                .build()

            // Execute in parallel
            coroutineScope {
                val thumbDeferred = async { MediaUploadUtils.executeAndParseProgress(thumbRequest) { p -> onProgress?.invoke("thumbnail", p) } }
                val videoDeferred = async { MediaUploadUtils.executeAndParseProgress(videoRequest) { _ -> /* Already handled by local progress */ } }
                
                val thumbMxc = thumbDeferred.await()
                val videoMxc = videoDeferred.await() ?: return@coroutineScope null

                VideoUploadResult(
                    videoMxcUrl = videoMxc,
                    thumbnailMxcUrl = thumbMxc ?: "",
                    width = displayWidth,
                    height = displayHeight,
                    duration = duration,
                    size = contentLength,
                    mimeType = mimeType,
                    thumbnailBlurHash = thumbnailBlurHash,
                    thumbnailWidth = thumbWidth,
                    thumbnailHeight = thumbHeight,
                    thumbnailSize = thumbBytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "VideoUploadUtils: Video upload failed", e)
            null
        }
    }
    
    private fun getContentLength(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
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
}
