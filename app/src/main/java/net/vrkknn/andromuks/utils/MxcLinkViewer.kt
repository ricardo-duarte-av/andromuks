package net.vrkknn.andromuks.utils

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.MediaInfo
import net.vrkknn.andromuks.MediaMessage
import okhttp3.Request

/**
 * A bare `mxc://` URI referenced from message text, together with the encryption flag the link
 * carried (gomuks writes `?encrypted=` into the href it linkifies).
 */
internal data class MxcMediaLink(val mxc: String, val encrypted: Boolean)

/**
 * What the backend says a [MxcMediaLink] actually is. [encrypted] is the flag that finally worked —
 * [EncryptedMediaRetryInterceptor] may have flipped the one we asked with.
 */
internal data class MxcMediaProbe(val mimeType: String, val filename: String, val encrypted: Boolean)

/**
 * Recognises the several shapes a link to Matrix media arrives in and normalises them to an
 * `mxc://server/id` plus its encryption flag. Returns null for anything that isn't Matrix media.
 *
 * Accepted:
 *  - `mxc://server/id` — a raw URI, e.g. linkified out of a plain-text body
 *  - `_gomuks/media/server/id?encrypted=false` — what gomuks puts in `sanitized_html` hrefs
 *  - `https://host/_gomuks/media/server/id?…` — the same, absolute
 *
 * [dataMxc] is the `data-mxc` attribute gomuks sets on `a.hicli-mxc-url`; it wins over the href
 * because it is the unambiguous original, but the flag still comes from the href's query.
 */
internal fun parseMxcMediaLink(href: String, dataMxc: String? = null): MxcMediaLink? {
    val encrypted = href.contains("encrypted=true")
    val explicit = dataMxc?.takeIf { it.startsWith("mxc://") }
    if (explicit != null) {
        return MxcMediaLink(explicit.substringBefore('?'), encrypted)
    }
    if (href.startsWith("mxc://")) {
        val mxc = href.substringBefore('?')
        return if (mxc.removePrefix("mxc://").contains('/')) MxcMediaLink(mxc, encrypted) else null
    }
    val marker = "_gomuks/media/"
    val markerIndex = href.indexOf(marker)
    if (markerIndex < 0) return null
    val path = href.substring(markerIndex + marker.length).substringBefore('?')
    val parts = path.split('/', limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    return MxcMediaLink("mxc://${parts[0]}/${parts[1]}", encrypted)
}

/**
 * The one place a raw mxc:// link is opened from message text.
 *
 * Nothing in the event says what the media *is* — a bare mxc URI in a message body carries no
 * `info.mimetype` the way an `m.image` event does — so the type has to come from the backend.
 * We ask for a single byte (`Range: bytes=0-0`) rather than issuing a HEAD: gomuks answers a
 * ranged GET with the real `Content-Type`, and a GET is also what [EncryptedMediaRetryInterceptor]
 * needs in order to see (and correct) a wrong `?encrypted=` flag, which a bodyless HEAD would hide.
 */
private val probeClient by lazy {
    HttpClientProvider.derived { addInterceptor(EncryptedMediaRetryInterceptor()) }
}

/**
 * Asks the backend what [link] is. Returns null when the media can't be reached at all, so the
 * caller can say so instead of guessing a viewer.
 */
internal suspend fun probeMxcMedia(link: MxcMediaLink, homeserverUrl: String, authToken: String): MxcMediaProbe? {
    return withContext(Dispatchers.IO) {
        val base = MediaUtils.mxcToHttpUrl(link.mxc, homeserverUrl) ?: return@withContext null
        val separator = if (base.contains("?")) "&" else "?"
        val url = "$base${separator}encrypted=${link.encrypted}"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "gomuks_auth=$authToken")
            .header("Range", "bytes=0-0")
            .build()
        try {
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("Andromuks", "probeMxcMedia: ${response.code} for ${link.mxc}")
                    return@withContext null
                }
                val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                val corrected = response.request.url.queryParameter("encrypted") == "true"
                val filename = filenameFromDisposition(response.header("Content-Disposition"))
                    ?: link.mxc.substringAfterLast('/')
                if (BuildConfig.DEBUG) {
                    Log.d("Andromuks", "probeMxcMedia: ${link.mxc} is $mimeType (encrypted=$corrected)")
                }
                MxcMediaProbe(mimeType = mimeType, filename = filename, encrypted = corrected)
            }
        } catch (e: Exception) {
            Log.w("Andromuks", "probeMxcMedia: failed for ${link.mxc}", e)
            null
        }
    }
}

/** Pulls `filename="…"` out of a Content-Disposition header, if it has one worth using. */
private fun filenameFromDisposition(header: String?): String? {
    val value = header ?: return null
    val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(value)
    return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * Hosts the viewer for a tapped `mxc://` link: probes the type, then opens the image viewer, the
 * video/audio player, or — for anything with no in-app viewer — falls back to a download, which is
 * the same thing the file chip on an `m.file` message does.
 *
 * Renders nothing when [link] is null, so callers can host it unconditionally.
 */
@Composable
internal fun MxcLinkViewerHost(link: MxcMediaLink?, homeserverUrl: String, authToken: String, onDismiss: () -> Unit) {
    if (link == null) return
    val context = LocalContext.current
    // onDismiss is read from inside LaunchedEffects that key on the link, not on the callback:
    // rememberUpdatedState keeps the latest one without restarting the probe when it changes.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var probe by remember(link) { mutableStateOf<MxcMediaProbe?>(null) }
    var failed by remember(link) { mutableStateOf(false) }

    LaunchedEffect(link, homeserverUrl, authToken) {
        val result = probeMxcMedia(link, homeserverUrl, authToken)
        if (result == null) {
            failed = true
        } else {
            probe = result
        }
    }

    LaunchedEffect(failed) {
        if (failed) {
            Toast.makeText(context, "Couldn't open this media", Toast.LENGTH_SHORT).show()
            currentOnDismiss()
        }
    }

    val resolved = probe
    if (resolved == null) {
        if (!failed) {
            Dialog(onDismissRequest = onDismiss) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                    Box(modifier = Modifier.padding(24.dp)) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        return
    }

    val msgType = when {
        resolved.mimeType.startsWith("image/") -> "m.image"
        resolved.mimeType.startsWith("video/") -> "m.video"
        resolved.mimeType.startsWith("audio/") -> "m.audio"
        else -> "m.file"
    }
    val media = remember(link, resolved) {
        MediaMessage(
            url = link.mxc,
            filename = resolved.filename,
            caption = null,
            info = MediaInfo(width = 0, height = 0, size = 0L, mimeType = resolved.mimeType, blurHash = null),
            msgType = msgType,
        )
    }

    when (msgType) {
        "m.image" -> ImageViewerDialog(
            mediaMessage = media,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isEncrypted = resolved.encrypted,
            onDismiss = onDismiss,
        )

        "m.video", "m.audio" -> VideoPlayerDialog(
            mediaMessage = media,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isEncrypted = resolved.encrypted,
            shouldAutoPlay = true,
            onDismiss = onDismiss,
        )

        else -> {
            // No in-app viewer for this type — hand it to the download path and get out of the way.
            LaunchedEffect(media, link, homeserverUrl, authToken) {
                val httpUrl = MediaUtils.mxcToHttpUrl(link.mxc, homeserverUrl)
                if (httpUrl == null) {
                    Toast.makeText(context, "Couldn't open this media", Toast.LENGTH_SHORT).show()
                } else {
                    val separator = if (httpUrl.contains("?")) "&" else "?"
                    Toast.makeText(context, "Downloading ${resolved.filename}", Toast.LENGTH_SHORT).show()
                    downloadFile(
                        context = context,
                        url = "$httpUrl${separator}encrypted=${resolved.encrypted}",
                        filename = resolved.filename,
                        authToken = authToken,
                    )
                }
                currentOnDismiss()
            }
        }
    }
}
