package net.vrkknn.andromuks.utils

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.unit.Dp
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

// Only match URLs followed by whitespace — cards appear after the user presses space,
// not while they're still typing the URL character by character.
private val urlRegex = Regex("https://\\S+(?=\\s)")

data class UrlPreviewItemState(
    val url: String,
    val isLoading: Boolean = false,
    val data: JSONObject? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null
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

// Returns (previewData, errorMessage). Exactly one of the two is non-null on error/success.
private suspend fun fetchUrlPreview(
    url: String,
    homeserverUrl: String,
    authToken: String,
    isRoomEncrypted: Boolean,
    client: OkHttpClient
): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
    try {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val previewEndpoint = "${homeserverUrl.trimEnd('/')}/_gomuks/url_preview" +
            "?encrypt=$isRoomEncrypted&url=$encodedUrl"
        val request = Request.Builder()
            .url(previewEndpoint)
            .addHeader("Cookie", "gomuks_auth=$authToken")
            .build()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UrlPreview: fetching $previewEndpoint")
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful) {
            // Try to extract the "error" field from the JSON error body (e.g. {"errcode":"M_UNKNOWN","error":"Got error 403"})
            val errorMessage = body?.let {
                runCatching { JSONObject(it).optString("error", "").takeIf { m -> m.isNotBlank() } }.getOrNull()
            }
            if (BuildConfig.DEBUG) android.util.Log.w(
                "Andromuks",
                "UrlPreview: fetch failed for $url — HTTP ${response.code}, error: $errorMessage"
            )
            return@withContext Pair(null, errorMessage)
        }
        if (body == null) return@withContext Pair(null, null)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UrlPreview: response for $url: $body")
        val obj = JSONObject(body)
        if (obj.optString("og:title", "").isBlank()) Pair(null, null) else Pair(obj, null)
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) android.util.Log.w(
            "Andromuks",
            "UrlPreview: exception fetching $url: ${e.message}"
        )
        Pair(null, null)
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
        urlRegex.findAll(text).map { it.value }
            .filter { !it.startsWith("https://matrix.to/") }
            .toList().distinct()
    }

    LaunchedEffect(detectedUrls) {
        // Remove previews for URLs no longer in text
        val toRemove = controller.previews.keys.filter { it !in detectedUrls }
        toRemove.forEach { controller.previews.remove(it) }

        // Register new URLs in idle state — user must tap the refresh button to fetch
        val toAdd = detectedUrls.filter { it !in controller.previews }
        toAdd.forEach { url -> controller.previews[url] = UrlPreviewItemState(url) }
    }

    // Order matches text position — detectedUrls is already in regex match order
    val visiblePreviews = detectedUrls.mapNotNull { controller.previews[it] }
    if (visiblePreviews.isEmpty()) return

    val doFetch: (String) -> Unit = { url ->
        controller.previews[url] = UrlPreviewItemState(url, isLoading = true)
        scope.launch {
            val (data, errorMessage) = fetchUrlPreview(url, homeserverUrl, authToken, isRoomEncrypted, httpClient)
            controller.previews[url] = if (data != null) {
                UrlPreviewItemState(url, data = data)
            } else {
                UrlPreviewItemState(url, isError = true, errorMessage = errorMessage)
            }
        }
    }

    // BoxWithConstraints lets us measure the available width and cap each card to it,
    // so the refresh icon is never hidden off-screen.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth - 16.dp // subtract Row's horizontal padding (8dp each side)

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
                    cardWidth = cardWidth,
                    onFetch = { doFetch(item.url) }
                )
            }
        }
    }
}

@Composable
private fun UrlPreviewCard(
    item: UrlPreviewItemState,
    homeserverUrl: String,
    authToken: String,
    cardWidth: Dp,
    onFetch: () -> Unit
) {
    when {
        // ── Loading ─────────────────────────────────────────────────────────
        item.isLoading -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier.width(cardWidth)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = item.url.take(40) + if (item.url.length > 40) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Error ────────────────────────────────────────────────────────────
        item.isError -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
                modifier = Modifier.width(cardWidth)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.url.take(40) + if (item.url.length > 40) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.errorMessage != null) {
                            Text(
                                text = "Error: ${item.errorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Preview unavailable",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Idle (not yet fetched) ────────────────────────────────────────────
        item.data == null -> {
            Surface(
                onClick = onFetch,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier.width(cardWidth)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.url.take(40) + if (item.url.length > 40) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onFetch) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Fetch preview",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ── Loaded ───────────────────────────────────────────────────────────
        else -> {
            val data = item.data!!
            val title = data.optString("og:title", "")
            if (title.isBlank()) return

            val description = data.optString("og:description", "").takeIf { it.isNotBlank() }
            val encryptionObj = data.optJSONObject("beeper:image:encryption")
            val encryptedMxcUrl = encryptionObj?.optString("url", "")?.takeIf { it.isNotBlank() }
            val plainMxcUrl = data.optString("og:image", "").takeIf { it.isNotBlank() }
            val imageMxcUrl = encryptedMxcUrl ?: plainMxcUrl
            val imageHttpUrl = imageMxcUrl?.let {
                val base = MediaUtils.mxcToHttpUrl(it, homeserverUrl) ?: return@let null
                if (encryptedMxcUrl != null) "$base?encrypted=true" else base
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier.width(cardWidth)
            ) {
                Box {
                    Column {
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
                    // Reload button — top-right overlay
                    IconButton(
                        onClick = onFetch,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload preview",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
