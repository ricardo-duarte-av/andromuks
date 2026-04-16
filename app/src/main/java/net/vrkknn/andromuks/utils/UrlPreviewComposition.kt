package net.vrkknn.andromuks.utils

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val urlRegex = Regex("https://\\S+")

data class UrlPreviewItemState(
    val url: String,
    val isLoading: Boolean = false,
    val data: JSONObject? = null,
    val isError: Boolean = false
)

class UrlPreviewController {
    val previews = mutableStateMapOf<String, UrlPreviewItemState>()

    fun getReadyPreviews(): JSONArray {
        val arr = JSONArray()
        previews.values
            .filter { it.data != null && !it.isLoading && !it.isError }
            .forEach { arr.put(it.data) }
        return arr
    }

    fun clearAll() {
        previews.clear()
    }
}

private suspend fun fetchUrlPreview(
    url: String,
    homeserverUrl: String,
    authToken: String,
    isRoomEncrypted: Boolean,
    client: OkHttpClient
): JSONObject? = withContext(Dispatchers.IO) {
    try {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val previewEndpoint = "${homeserverUrl.trimEnd('/')}/_gomuks/url_preview" +
            "?encrypt=$isRoomEncrypted&url=$encodedUrl"
        val request = Request.Builder()
            .url(previewEndpoint)
            .addHeader("Cookie", "gomuks_auth=$authToken")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            if (BuildConfig.DEBUG) android.util.Log.w(
                "Andromuks",
                "UrlPreview: fetch failed for $url: ${response.code}"
            )
            return@withContext null
        }
        val body = response.body?.string() ?: return@withContext null
        val obj = JSONObject(body)
        // Only return if there's at least a title
        if (obj.optString("og:title", "").isBlank()) null else obj
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) android.util.Log.w(
            "Andromuks",
            "UrlPreview: exception fetching $url: ${e.message}"
        )
        null
    }
}

@Composable
fun UrlPreviewCompositionBar(
    text: String,
    controller: UrlPreviewController,
    homeserverUrl: String,
    authToken: String,
    isRoomEncrypted: Boolean
) {
    val scope = rememberCoroutineScope()
    val httpClient = remember { OkHttpClient() }

    val detectedUrls = remember(text) {
        urlRegex.findAll(text).map { it.value }.toList().distinct()
    }

    LaunchedEffect(detectedUrls) {
        // Remove previews for URLs no longer in text
        val toRemove = controller.previews.keys.filter { it !in detectedUrls }
        toRemove.forEach { controller.previews.remove(it) }

        // Fetch previews for new URLs
        val toFetch = detectedUrls.filter { it !in controller.previews }
        toFetch.forEach { url ->
            controller.previews[url] = UrlPreviewItemState(url, isLoading = true)
            scope.launch {
                val result = fetchUrlPreview(url, homeserverUrl, authToken, isRoomEncrypted, httpClient)
                if (controller.previews[url]?.isLoading == true) {
                    controller.previews[url] = if (result != null) {
                        UrlPreviewItemState(url, data = result)
                    } else {
                        UrlPreviewItemState(url, isError = true)
                    }
                }
            }
        }
    }

    val visiblePreviews = controller.previews.values.toList()
    if (visiblePreviews.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visiblePreviews.forEach { item ->
            UrlPreviewCard(
                item = item,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                onReload = {
                    controller.previews[item.url] = UrlPreviewItemState(item.url, isLoading = true)
                    scope.launch {
                        val result = fetchUrlPreview(item.url, homeserverUrl, authToken, isRoomEncrypted, httpClient)
                        controller.previews[item.url] = if (result != null) {
                            UrlPreviewItemState(item.url, data = result)
                        } else {
                            UrlPreviewItemState(item.url, isError = true)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun UrlPreviewCard(
    item: UrlPreviewItemState,
    homeserverUrl: String,
    authToken: String,
    onReload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Box {
            when {
                item.isLoading -> {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.url.take(40) + if (item.url.length > 40) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                item.isError -> {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.url.take(40) + if (item.url.length > 40) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onReload, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Reload preview",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                item.data != null -> {
                    val data = item.data
                    val title = data.optString("og:title", "")
                    if (title.isNotBlank()) {
                    val description = data.optString("og:description", "").takeIf { it.isNotBlank() }
                    val encryptionObj = data.optJSONObject("beeper:image:encryption")
                    val encryptedMxcUrl = encryptionObj?.optString("url", "")?.takeIf { it.isNotBlank() }
                    val plainMxcUrl = data.optString("og:image", "").takeIf { it.isNotBlank() }
                    val imageMxcUrl = encryptedMxcUrl ?: plainMxcUrl
                    val imageHttpUrl = imageMxcUrl?.let {
                        val base = MediaUtils.mxcToHttpUrl(it, homeserverUrl) ?: return@let null
                        if (encryptedMxcUrl != null) "$base?encrypted=true" else base
                    }

                    Column {
                        // Image at top
                        if (imageHttpUrl != null) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val imageLoader = ImageLoaderSingleton.get(context)
                            val imageRequest = ImageRequest.Builder(context)
                                .data(imageHttpUrl)
                                .addHeader("Cookie", "gomuks_auth=$authToken")
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .build()
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            )
                        }
                        // Text section
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (description != null) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Reload button overlay
                    IconButton(
                        onClick = onReload,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(32.dp)
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload preview",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    } // end if (title.isNotBlank())
                }
            }
        }
    }
}
