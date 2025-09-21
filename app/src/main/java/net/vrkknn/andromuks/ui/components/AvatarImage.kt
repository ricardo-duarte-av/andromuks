package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Composable
fun AvatarImage(
    mxcUrl: String?,
    homeserverUrl: String,
    authToken: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    var imageBitmap by remember(mxcUrl) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(mxcUrl) { mutableStateOf(false) }
    var hasError by remember(mxcUrl) { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    LaunchedEffect(mxcUrl, homeserverUrl, authToken) {
        if (mxcUrl.isNullOrBlank()) {
            hasError = true
            return@LaunchedEffect
        }
        
        isLoading = true
        hasError = false
        
        try {
            val httpUrl = net.vrkknn.andromuks.utils.AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
            if (httpUrl == null) {
                hasError = true
                isLoading = false
                return@LaunchedEffect
            }
            
            val bitmap = withContext(Dispatchers.IO) {
                loadAvatarBitmap(httpUrl, authToken)
            }
            
            if (bitmap != null) {
                imageBitmap = bitmap.asImageBitmap()
                hasError = false
            } else {
                hasError = true
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "AvatarImage: Error loading avatar: $mxcUrl", e)
            hasError = true
        } finally {
            isLoading = false
        }
    }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        when {
            imageBitmap != null -> {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = "Room avatar",
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            isLoading -> {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            else -> {
                Text(
                    text = fallbackText.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private suspend fun loadAvatarBitmap(url: String, authToken: String): android.graphics.Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    BitmapFactory.decodeStream(inputStream)
                } else {
                    null
                }
            } else {
                Log.w("Andromuks", "AvatarImage: Failed to load avatar: ${response.code}")
                null
            }
        } catch (e: IOException) {
            Log.e("Andromuks", "AvatarImage: IOException loading avatar", e)
            null
        } catch (e: Exception) {
            Log.e("Andromuks", "AvatarImage: Error loading avatar", e)
            null
        }
    }
}
