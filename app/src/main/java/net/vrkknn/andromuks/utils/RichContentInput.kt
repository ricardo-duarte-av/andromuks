package net.vrkknn.andromuks.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BuildConfig
import java.io.File

// Rich content (GIF / sticker) insertion from the soft keyboard.
//
// Gboard greys out its GIF and sticker buttons unless the focused editor advertises
// EditorInfo.contentMimeTypes and backs it with an InputConnection that implements
// commitContent. The composer is the legacy BasicTextField(value, onValueChange) (see
// CustomBubbleTextField), which never sets either — and Modifier.contentReceiver is wired only
// into the newer BasicTextField(state), so it cannot help us here.
//
// InterceptPlatformTextInput is the way in: it wraps the platform IME session for every
// descendant regardless of which text field implementation is underneath, letting us stamp the
// MIME types onto the EditorInfo the IME reads and wrap the returned connection.

/**
 * What we tell the IME we accept. Gboard's GIF button commits `image/gif`; its sticker /
 * Emoji Kitchen button commits PNG or WebP. JPEG is listed because some third-party keyboards
 * send it.
 */
private val RICH_CONTENT_MIME_TYPES = arrayOf("image/gif", "image/png", "image/webp", "image/jpeg")

/** Subdirectory of `cacheDir` that committed content is copied into. Mirrors `file_paths.xml`. */
private const val RICH_CONTENT_CACHE_DIR = "rich_content"

/** How long a cached commit is kept before the next one may delete it — long enough to outlive its upload. */
private const val RICH_CONTENT_RETENTION_MS = 10 * 60 * 1000L

/**
 * Wraps [content] so any text field inside it accepts GIFs and stickers from the keyboard.
 *
 * Emits nothing of its own — it only installs a [PlatformTextInputInterceptor] around [content],
 * exactly as [InterceptPlatformTextInput] and `CompositionLocalProvider` do — hence no `modifier`
 * parameter.
 *
 * @param onReceiveContent invoked with a stable content URI owned by this app (the committed
 *   content is copied into the cache first, so the caller may take as long as it likes) and the
 *   MIME type the IME declared.
 */
@Suppress("ModifierMissing")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RichContentTextInput(onReceiveContent: suspend (Uri, String) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep the interceptor identity stable across recompositions while still calling the latest
    // lambda — the callback captures reply/thread state that changes on every keystroke.
    val currentOnContentReceived = rememberUpdatedState(onReceiveContent)

    val interceptor = remember(context) {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing {
                val wrapped = PlatformTextInputMethodRequest { outAttributes ->
                    val inputConnection = request.createInputConnection(outAttributes)
                    EditorInfoCompat.setContentMimeTypes(outAttributes, RICH_CONTENT_MIME_TYPES)
                    // The listener overload is deprecated in favour of createWrapper(view, ic, info),
                    // which delivers commitContent through an OnReceiveContentListener installed on
                    // the View. The only View here is Compose's own AndroidComposeView, and taking it
                    // over for the whole session would reach far beyond this text field — so keep the
                    // listener, whose scope is exactly the connection we wrapped.
                    @Suppress("DEPRECATION")
                    InputConnectionCompat.createWrapper(inputConnection, outAttributes) { info, flags, _ ->
                        val mimeType = info.description.getMimeType(0) ?: return@createWrapper false

                        // The IME grants us a temporary read permission that is only safe to hold
                        // while we are actively reading, so copy the bytes out immediately and
                        // release it — the upload that follows can take seconds.
                        val needsPermission =
                            flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                        if (needsPermission) {
                            try {
                                info.requestPermission()
                            } catch (e: Exception) {
                                Log.w("Andromuks", "RichContentInput: could not take read permission", e)
                                return@createWrapper false
                            }
                        }

                        scope.launch {
                            try {
                                val cached = withContext(Dispatchers.IO) {
                                    copyToCache(context, info.contentUri, mimeType)
                                }
                                if (cached == null) {
                                    Log.w("Andromuks", "RichContentInput: failed to cache committed content")
                                } else {
                                    currentOnContentReceived.value(cached, mimeType)
                                }
                            } finally {
                                if (needsPermission) {
                                    try {
                                        info.releasePermission()
                                    } catch (e: Exception) {
                                        Log.w("Andromuks", "RichContentInput: releasePermission failed", e)
                                    }
                                }
                            }
                        }
                        true
                    }
                }
                nextHandler.startInputMethod(wrapped)
            }
        }
    }

    InterceptPlatformTextInput(interceptor, content)
}

/**
 * Copies the IME's content URI into `cacheDir/rich_content/` and returns a FileProvider URI for
 * it, so the upload path sees a stable URI with a resolvable mimetype and display name.
 *
 * Returns null if the content could not be read.
 */
private fun copyToCache(context: Context, source: Uri, mimeType: String): Uri? = try {
    val dir = File(context.cacheDir, RICH_CONTENT_CACHE_DIR).apply { mkdirs() }
    // Drop files from earlier sends so this directory does not grow without bound. Only stale ones:
    // a send started moments ago may still be streaming its file to the homeserver.
    val staleBefore = System.currentTimeMillis() - RICH_CONTENT_RETENTION_MS
    dir.listFiles()?.forEach { if (it.lastModified() < staleBefore) it.delete() }

    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
    val target = File(dir, "andromuks_ime_${System.currentTimeMillis()}.$extension")
    context.contentResolver.openInputStream(source)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null

    if (target.length() == 0L) {
        target.delete()
        null
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }
} catch (e: Exception) {
    Log.e("Andromuks", "RichContentInput: copy to cache failed", e)
    null
}

private const val UPLOAD_RETRY_ATTEMPTS = 3

/**
 * Uploads content committed by the keyboard and sends it straight away — there is deliberately no
 * preview or caption step, so failures must be surfaced loudly.
 *
 * The IME never tells us which of its buttons was tapped, so the msgtype is chosen from the MIME
 * type: Gboard's GIF button commits `image/gif`, while its sticker / Emoji Kitchen button commits
 * PNG or WebP. Anything that is not a GIF is therefore sent as `m.sticker`.
 */
suspend fun uploadAndSendRichContent(
    context: Context,
    appViewModel: AppViewModel,
    roomId: String,
    homeserverUrl: String,
    authToken: String,
    uri: Uri,
    mimeType: String,
    threadRootEventId: String?,
    replyToEventId: String?,
) {
    val isGif = mimeType.startsWith("image/gif")
    // Only a message the user explicitly replied to is a real reply; a bare thread post is the
    // thread-fallback reply that buildMediaRelatesTo synthesises from the last thread message.
    val isThreadFallback = replyToEventId == null

    appViewModel.beginUpload(roomId, "image")
    val result = try {
        var attemptResult: MediaUploadResult? = null
        for (attempt in 0..UPLOAD_RETRY_ATTEMPTS) {
            if (attempt > 0) {
                appViewModel.setUploadRetryCount(roomId, attempt)
                delay(1000L * attempt)
            }
            attemptResult = MediaUploadUtils.uploadMedia(
                context = context,
                uri = uri,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = false,
                // Never re-encode: it would flatten an animated GIF to its first frame.
                compressOriginal = false,
                onProgress = { key, progress -> appViewModel.setUploadProgress(roomId, key, progress) },
            )
            if (attemptResult != null) break
        }
        appViewModel.setUploadRetryCount(roomId, 0)
        attemptResult
    } finally {
        appViewModel.endUpload(roomId, "image")
    }

    if (result == null) {
        Log.e("Andromuks", "RichContentInput: upload failed after retries")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                if (isGif) "Failed to send GIF" else "Failed to send sticker",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        return
    }

    if (BuildConfig.DEBUG) {
        Log.d("Andromuks", "RichContentInput: uploaded ${result.mxcUrl} ($mimeType), sending as ${if (isGif) "image" else "sticker"}")
    }

    if (isGif) {
        appViewModel.sendImageMessage(
            roomId = roomId,
            mxcUrl = result.mxcUrl,
            width = result.width,
            height = result.height,
            size = result.size,
            mimeType = result.mimeType,
            blurHash = result.blurHash,
            thumbnailUrl = result.thumbnailUrl,
            thumbnailWidth = result.thumbnailWidth,
            thumbnailHeight = result.thumbnailHeight,
            thumbnailMimeType = result.thumbnailMimeType,
            thumbnailSize = result.thumbnailSize,
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId,
            isThreadFallback = isThreadFallback,
        )
    } else {
        // sendStickerMessage takes no thumbnail: a sticker is rendered from the original.
        appViewModel.sendStickerMessage(
            roomId = roomId,
            mxcUrl = result.mxcUrl,
            body = "Sticker",
            mimeType = result.mimeType,
            size = result.size,
            width = result.width,
            height = result.height,
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId,
            isThreadFallback = isThreadFallback,
        )
    }
}
