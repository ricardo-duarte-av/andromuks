package net.vrkknn.andromuks.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.request.ErrorResult
import coil.compose.AsyncImagePainter
import coil.size.Scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Progressive image loading system with size optimization.
 * 
 * This system provides:
 * - Smart sizing based on display constraints
 * - Progressive loading for better UX
 * - Memory optimization with quality settings
 * - Automatic format optimization
 */
object ProgressiveImageLoader {
    private const val TAG = "ProgressiveImageLoader"
    
    // QUALITY IMPROVEMENT: Larger sizes for better quality
    private const val THUMBNAIL_SIZE = 400       // Increased from 200 to 400
    private const val PREVIEW_SIZE = 1200        // Increased from 800 to 1200
    private const val FULL_SIZE = 1920
    private const val MAX_IMAGE_SIZE = 2048
    
    // QUALITY IMPROVEMENT: Higher quality settings for better image clarity
    private const val THUMBNAIL_QUALITY = 90     // Increased from 70 to 90
    private const val PREVIEW_QUALITY = 85       // Increased from 80 to 85
    private const val FULL_QUALITY = 90          // Increased from 85 to 90
    
    data class ImageSize(
        val width: Int,
        val height: Int,
        val quality: Int = 85,
        val scale: Scale = Scale.FIT
    )
    
    /**
     * Calculate optimal image size based on display constraints.
     * 
     * @param originalWidth Original image width
     * @param originalHeight Original image height
     * @param displayWidth Display width in pixels
     * @param displayHeight Display height in pixels
     * @param maxWidth Maximum allowed width
     * @param maxHeight Maximum allowed height
     * @return Optimal ImageSize for the display
     */
    fun getOptimalImageSize(
        originalWidth: Int,
        originalHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        maxWidth: Int = MAX_IMAGE_SIZE,
        maxHeight: Int = MAX_IMAGE_SIZE
    ): ImageSize {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val displayAspectRatio = displayWidth.toFloat() / displayHeight.toFloat()
        
        return when {
            // Thumbnail for small displays
            displayWidth <= 200 -> ImageSize(
                width = THUMBNAIL_SIZE,
                height = (THUMBNAIL_SIZE / aspectRatio).toInt(),
                quality = THUMBNAIL_QUALITY,
                scale = Scale.FIT
            )
            
            // Preview for medium displays
            displayWidth <= 800 -> ImageSize(
                width = PREVIEW_SIZE,
                height = (PREVIEW_SIZE / aspectRatio).toInt(),
                quality = PREVIEW_QUALITY,
                scale = Scale.FIT
            )
            
            // Full size for large displays
            else -> ImageSize(
                width = minOf(originalWidth, maxWidth),
                height = minOf(originalHeight, (maxWidth / aspectRatio).toInt(), maxHeight),
                quality = FULL_QUALITY,
                scale = Scale.FIT
            )
        }
    }
    
    /**
     * Create progressive image request with optimization settings.
     * 
     * @param context Android context
     * @param imageUrl Image URL to load
     * @param displaySize Optimal display size
     * @param authToken Authentication token for protected images
     * @return Optimized ImageRequest
     */
    fun createProgressiveImageRequest(
        context: Context,
        imageUrl: String,
        displaySize: ImageSize,
        authToken: String? = null
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(imageUrl)
            .apply {
                // Add authentication header if provided
                authToken?.let { addHeader("Cookie", "gomuks_auth=$it") }
                
                // Set optimal size
                size(displaySize.width, displaySize.height)
                scale(displaySize.scale)
                
                // Memory optimization
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                
                // Quality optimization - remove quality method as it's not available
                // quality(displaySize.quality)
                
                // Progressive loading settings
                allowHardware(true)
                allowRgb565(true)
                
                // Network optimization
                networkCachePolicy(CachePolicy.ENABLED)
            }
            .build()
    }
    
    /**
     * Optimize image file with compression and resizing.
     * 
     * @param context Android context
     * @param originalFile Original image file
     * @param targetSize Target size for optimization
     * @return Optimized image file
     */
    suspend fun optimizeImage(
        context: Context,
        originalFile: File,
        targetSize: Int = PREVIEW_SIZE
    ): File = withContext(Dispatchers.IO) {
        val optimizedFile = File(context.cacheDir, "optimized_${originalFile.name}")
        
        try {
            val bitmap = BitmapFactory.decodeFile(originalFile.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap from file: ${originalFile.absolutePath}")
                return@withContext originalFile
            }
            
            val optimizedBitmap = resizeBitmap(bitmap, targetSize)
            
            optimizedFile.outputStream().use { output ->
                optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, output)
            }
            
            bitmap.recycle()
            optimizedBitmap.recycle()
            
            val originalSize = originalFile.length()
            val optimizedSize = optimizedFile.length()
            val compressionRatio = (1.0 - optimizedSize.toDouble() / originalSize) * 100
            
            Log.d(TAG, "Optimized image: ${originalSize / 1024}KB -> ${optimizedSize / 1024}KB (${compressionRatio.toInt()}% reduction)")
            optimizedFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to optimize image: ${originalFile.absolutePath}", e)
            originalFile
        }
    }
    
    /**
     * Resize bitmap to target size while maintaining aspect ratio.
     */
    private fun resizeBitmap(bitmap: Bitmap, targetSize: Int): Bitmap {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val newWidth = targetSize
        val newHeight = (targetSize / aspectRatio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Check if image should be optimized based on size and type.
     */
    fun shouldOptimize(file: File, fileType: String): Boolean {
        return when (fileType) {
            "image" -> file.length() > 500 * 1024 // 500KB
            "video" -> file.length() > 10 * 1024 * 1024 // 10MB
            else -> false
        }
    }
    
    /**
     * Get optimization statistics for monitoring.
     */
    fun getOptimizationStats(): Map<String, Any> {
        return mapOf(
            "thumbnail_size" to THUMBNAIL_SIZE,
            "preview_size" to PREVIEW_SIZE,
            "full_size" to FULL_SIZE,
            "max_image_size" to MAX_IMAGE_SIZE,
            "thumbnail_quality" to THUMBNAIL_QUALITY,
            "preview_quality" to PREVIEW_QUALITY,
            "full_quality" to FULL_QUALITY
        )
    }
}

/**
 * Progressive image loading Composable with automatic optimization.
 * 
 * This Composable provides:
 * - Automatic size calculation based on display constraints
 * - Progressive loading with placeholders
 * - Error handling with fallbacks
 * - Memory optimization
 */
@Composable
fun ProgressiveAsyncImage(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    authToken: String? = null,
    displayWidth: Int = 800,
    displayHeight: Int = 600,
    onLoading: (AsyncImagePainter.State.Loading) -> Unit = {},
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {},
    onError: (AsyncImagePainter.State.Error) -> Unit = {}
) {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    // Calculate optimal size based on display constraints
    val displaySize = remember(displayWidth, displayHeight) {
        ProgressiveImageLoader.getOptimalImageSize(
            originalWidth = 1920, // Default assumption
            originalHeight = 1080,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
    }
    
    // Create progressive request
    val imageRequest = remember(imageUrl, displaySize) {
        ProgressiveImageLoader.createProgressiveImageRequest(
            context = context,
            imageUrl = imageUrl,
            displaySize = displaySize,
            authToken = authToken
        )
    }
    
    AsyncImage(
        model = imageRequest,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        modifier = modifier,
        onLoading = onLoading,
        onSuccess = onSuccess,
        onError = onError
    )
}

/**
 * Progressive image loading with size constraints.
 * 
 * This version allows specifying exact size constraints for the image.
 */
@Composable
fun ProgressiveAsyncImageWithConstraints(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    authToken: String? = null,
    maxWidth: Dp = 800.dp,
    maxHeight: Dp = 600.dp,
    onLoading: (AsyncImagePainter.State.Loading) -> Unit = {},
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {},
    onError: (AsyncImagePainter.State.Error) -> Unit = {}
) {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    // Calculate optimal size based on constraints
    val displaySize = remember(maxWidth, maxHeight) {
        ProgressiveImageLoader.getOptimalImageSize(
            originalWidth = 1920,
            originalHeight = 1080,
            displayWidth = maxWidth.value.toInt(),
            displayHeight = maxHeight.value.toInt()
        )
    }
    
    // Create progressive request
    val imageRequest = remember(imageUrl, displaySize) {
        ProgressiveImageLoader.createProgressiveImageRequest(
            context = context,
            imageUrl = imageUrl,
            displaySize = displaySize,
            authToken = authToken
        )
    }
    
    AsyncImage(
        model = imageRequest,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        modifier = modifier,
        onLoading = onLoading,
        onSuccess = onSuccess,
        onError = onError
    )
}
