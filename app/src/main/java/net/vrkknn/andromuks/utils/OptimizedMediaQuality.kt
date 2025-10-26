package net.vrkknn.andromuks.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
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
 * PERFORMANCE: Optimized media quality system for better image quality
 * 
 * Key optimizations:
 * - Smart quality settings based on use case (avatar, thumbnail, full image)
 * - Higher quality for avatars and important thumbnails
 * - Balanced compression for different media types
 * - Smart sizing to maintain quality while optimizing performance
 */

object OptimizedMediaQuality {
    private const val TAG = "OptimizedMediaQuality"
    
    // QUALITY IMPROVEMENT: Maximum quality settings for crystal clear images
    private const val AVATAR_QUALITY = 100       // Maximum quality for avatars
    private const val THUMBNAIL_QUALITY = 100    // Maximum quality for thumbnails
    private const val PREVIEW_QUALITY = 100      // Maximum quality for previews
    private const val FULL_QUALITY = 100         // Maximum quality for full images
    
    // Size constants optimized for maximum quality
    private const val AVATAR_SIZE = 512          // Even larger for maximum avatar quality
    private const val THUMBNAIL_SIZE = 600       // Even larger for maximum thumbnail quality
    private const val PREVIEW_SIZE = 1600        // Even larger for maximum preview quality
    private const val FULL_SIZE = 1920           // Full size for maximum quality images
    
    data class QualitySettings(
        val width: Int,
        val height: Int,
        val quality: Int,
        val scale: Scale = Scale.FIT,
        val useHighQuality: Boolean = true
    )
    
    /**
     * QUALITY IMPROVEMENT: Get optimized quality settings for avatars
     */
    fun getAvatarQualitySettings(
        originalWidth: Int,
        originalHeight: Int,
        displaySize: Int = AVATAR_SIZE
    ): QualitySettings {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val size = minOf(displaySize, originalWidth, originalHeight)
        
        return QualitySettings(
            width = size,
            height = (size / aspectRatio).toInt(),
            quality = AVATAR_QUALITY,
            scale = Scale.FIT,
            useHighQuality = true
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Get optimized quality settings for thumbnails
     */
    fun getThumbnailQualitySettings(
        originalWidth: Int,
        originalHeight: Int,
        displayWidth: Int = THUMBNAIL_SIZE,
        displayHeight: Int = THUMBNAIL_SIZE
    ): QualitySettings {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val displayAspectRatio = displayWidth.toFloat() / displayHeight.toFloat()
        
        val width = if (aspectRatio > displayAspectRatio) {
            displayWidth
        } else {
            (displayHeight * aspectRatio).toInt()
        }
        
        val height = (width / aspectRatio).toInt()
        
        return QualitySettings(
            width = width,
            height = height,
            quality = THUMBNAIL_QUALITY,
            scale = Scale.FIT,
            useHighQuality = true
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Get optimized quality settings for previews
     */
    fun getPreviewQualitySettings(
        originalWidth: Int,
        originalHeight: Int,
        displayWidth: Int = PREVIEW_SIZE,
        displayHeight: Int = PREVIEW_SIZE
    ): QualitySettings {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val displayAspectRatio = displayWidth.toFloat() / displayHeight.toFloat()
        
        val width = if (aspectRatio > displayAspectRatio) {
            minOf(displayWidth, originalWidth)
        } else {
            minOf((displayHeight * aspectRatio).toInt(), originalWidth)
        }
        
        val height = (width / aspectRatio).toInt()
        
        return QualitySettings(
            width = width,
            height = height,
            quality = PREVIEW_QUALITY,
            scale = Scale.FIT,
            useHighQuality = true
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Get optimized quality settings for full images
     */
    fun getFullImageQualitySettings(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int = FULL_SIZE,
        maxHeight: Int = FULL_SIZE
    ): QualitySettings {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        
        val width = minOf(originalWidth, maxWidth)
        val height = minOf(originalHeight, (maxWidth / aspectRatio).toInt(), maxHeight)
        
        return QualitySettings(
            width = width,
            height = height,
            quality = FULL_QUALITY,
            scale = Scale.FIT,
            useHighQuality = true
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Create optimized image request with quality settings
     */
    fun createOptimizedImageRequest(
        context: Context,
        imageUrl: String,
        qualitySettings: QualitySettings,
        authToken: String? = null
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(imageUrl)
            .apply {
                // QUALITY IMPROVEMENT: Use higher quality settings
                if (qualitySettings.useHighQuality) {
                    // Enable high-quality decoding
                    allowHardware(false) // Disable hardware acceleration for better quality
                    allowRgb565(false)   // Use full color depth
                }
                
                // Set size with quality settings
                size(qualitySettings.width, qualitySettings.height)
                scale(qualitySettings.scale)
                
                // QUALITY IMPROVEMENT: Better cache settings for quality
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                
                // Add authentication if needed
                authToken?.let { token ->
                    addHeader("Cookie", "gomuks_auth=$token")
                }
            }
            .build()
    }
    
    /**
     * QUALITY IMPROVEMENT: Optimized avatar image component
     */
    @Composable
    fun OptimizedAvatarImage(
        avatarUrl: String,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        size: Dp = 48.dp,
        authToken: String? = null
    ) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoaderSingleton.get(context) }
        
        // QUALITY IMPROVEMENT: Use high-quality settings for avatars
        val qualitySettings = remember(avatarUrl, size) {
            getAvatarQualitySettings(
                originalWidth = 256, // Assume standard avatar size
                originalHeight = 256,
                displaySize = size.value.toInt()
            )
        }
        
        val imageRequest = remember(avatarUrl, qualitySettings, authToken) {
            createOptimizedImageRequest(
                context = context,
                imageUrl = avatarUrl,
                qualitySettings = qualitySettings,
                authToken = authToken
            )
        }
        
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Optimized thumbnail image component
     */
    @Composable
    fun OptimizedThumbnailImage(
        imageUrl: String,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        displayWidth: Int = THUMBNAIL_SIZE,
        displayHeight: Int = THUMBNAIL_SIZE,
        authToken: String? = null
    ) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoaderSingleton.get(context) }
        
        // QUALITY IMPROVEMENT: Use high-quality settings for thumbnails
        val qualitySettings = remember(imageUrl, displayWidth, displayHeight) {
            getThumbnailQualitySettings(
                originalWidth = 800, // Assume standard thumbnail size
                originalHeight = 600,
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )
        }
        
        val imageRequest = remember(imageUrl, qualitySettings, authToken) {
            createOptimizedImageRequest(
                context = context,
                imageUrl = imageUrl,
                qualitySettings = qualitySettings,
                authToken = authToken
            )
        }
        
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Optimized preview image component
     */
    @Composable
    fun OptimizedPreviewImage(
        imageUrl: String,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        displayWidth: Int = PREVIEW_SIZE,
        displayHeight: Int = PREVIEW_SIZE,
        authToken: String? = null
    ) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoaderSingleton.get(context) }
        
        // QUALITY IMPROVEMENT: Use high-quality settings for previews
        val qualitySettings = remember(imageUrl, displayWidth, displayHeight) {
            getPreviewQualitySettings(
                originalWidth = 1920, // Assume standard preview size
                originalHeight = 1080,
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )
        }
        
        val imageRequest = remember(imageUrl, qualitySettings, authToken) {
            createOptimizedImageRequest(
                context = context,
                imageUrl = imageUrl,
                qualitySettings = qualitySettings,
                authToken = authToken
            )
        }
        
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Optimized full image component
     */
    @Composable
    fun OptimizedFullImage(
        imageUrl: String,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        maxWidth: Int = FULL_SIZE,
        maxHeight: Int = FULL_SIZE,
        authToken: String? = null
    ) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoaderSingleton.get(context) }
        
        // QUALITY IMPROVEMENT: Use high-quality settings for full images
        val qualitySettings = remember(imageUrl, maxWidth, maxHeight) {
            getFullImageQualitySettings(
                originalWidth = 1920, // Assume standard full size
                originalHeight = 1080,
                maxWidth = maxWidth,
                maxHeight = maxHeight
            )
        }
        
        val imageRequest = remember(imageUrl, qualitySettings, authToken) {
            createOptimizedImageRequest(
                context = context,
                imageUrl = imageUrl,
                qualitySettings = qualitySettings,
                authToken = authToken
            )
        }
        
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
    
    /**
     * QUALITY IMPROVEMENT: Smart quality detection based on image type
     */
    fun detectImageType(imageUrl: String): ImageType {
        return when {
            imageUrl.contains("avatar") || imageUrl.contains("profile") -> ImageType.AVATAR
            imageUrl.contains("thumbnail") || imageUrl.contains("thumb") -> ImageType.THUMBNAIL
            imageUrl.contains("preview") -> ImageType.PREVIEW
            else -> ImageType.FULL
        }
    }
    
    enum class ImageType {
        AVATAR,
        THUMBNAIL,
        PREVIEW,
        FULL
    }
    
    /**
     * QUALITY IMPROVEMENT: Get quality settings based on detected image type
     */
    fun getQualitySettingsForType(
        imageType: ImageType,
        originalWidth: Int,
        originalHeight: Int,
        displayWidth: Int = 400,
        displayHeight: Int = 400
    ): QualitySettings {
        return when (imageType) {
            ImageType.AVATAR -> getAvatarQualitySettings(originalWidth, originalHeight, displayWidth)
            ImageType.THUMBNAIL -> getThumbnailQualitySettings(originalWidth, originalHeight, displayWidth, displayHeight)
            ImageType.PREVIEW -> getPreviewQualitySettings(originalWidth, originalHeight, displayWidth, displayHeight)
            ImageType.FULL -> getFullImageQualitySettings(originalWidth, originalHeight, displayWidth, displayHeight)
        }
    }
}
