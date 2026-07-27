package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.MediaInfo
import net.vrkknn.andromuks.MediaMessage
import org.json.JSONObject

// Parses Matrix media content objects into MediaMessage.
//
// The same shape shows up in three places: a plaintext `m.room.message`, the decrypted content of an
// encrypted one, and — with `itemtype` instead of `msgtype` — each entry of an MSC4274 gallery's
// `itemtypes` array (see docs/GALLERIES.md). They all go through parseMediaMessage.

/**
 * Extracts the MSC1767 voice-message waveform from a message content object.
 *
 * The samples live under `org.matrix.msc1767.audio.waveform` as a JSON array of amplitude
 * values. Returns null when absent or empty so callers can fall back to a plain seek bar.
 */
fun extractWaveform(content: JSONObject?): List<Int>? {
    val arr = content?.optJSONObject("org.matrix.msc1767.audio")?.optJSONArray("waveform")
        ?: return null
    if (arr.length() == 0) return null
    return List(arr.length()) { arr.optInt(it, 0) }
}

/**
 * Whether the media itself is encrypted, i.e. it is delivered as a `file` object rather than a
 * plain `url`. Note this is a property of the *media*, not of the event: an unencrypted event can
 * never carry one, but each item of an MSC4274 gallery decides for itself, so the flag has to
 * travel per item rather than being derived once per event.
 */
fun mediaContentHasEncryptedFile(content: JSONObject?): Boolean = content?.optJSONObject("file") != null

/**
 * One-line summary of an MSC4274 gallery for surfaces that only show text: the room list preview,
 * notifications, reply previews and the edit bar.
 *
 * A gallery carries no plain-text fallback of its own (the MSC prescribes none), so without this a
 * gallery would show as a bare caption — or as nothing at all when it has none. The caption is
 * preferred when present, with the item count appended either way.
 *
 * @return the label, or null when [content] is not a gallery
 */
fun galleryPreviewLabel(content: JSONObject?): String? {
    if (content == null) return null
    if (content.optString("msgtype", "") !in GALLERY_MSGTYPES) return null
    val count = content.optJSONArray("itemtypes")?.length() ?: 0
    val suffix = if (count > 0) " ($count)" else ""
    val caption = content.optString("body", "").takeIf { it.isNotBlank() }
    return if (caption != null) "🖼️ $caption$suffix" else "🖼️ Gallery$suffix"
}

/**
 * Parses a media content object into a [MediaMessage].
 *
 * @param content the media content: an `m.room.message` content, a decrypted content, or one entry
 *   of an MSC4274 `itemtypes` array
 * @param body the message body, used for the caption and as the `m.file` filename fallback. Callers
 *   rendering an edited event pass the edit's body. Gallery items pass their own `body`, which is
 *   the item filename — they have no per-item caption.
 * @param typeKey key holding the media type: `"msgtype"` for message content, `"itemtype"` for
 *   gallery items
 * @param localContent the event's `local_content`, used for the HTML caption. Callers should pass
 *   `editedBy?.localContent ?: event.localContent` so a stale `orig_local_content` (e.g. from a
 *   failed-decryption notice later replaced by an m.image edit) is not used. Gallery items pass
 *   null: their caption lives on the gallery, not the item.
 * @return the parsed media, or null when there is no usable URL (neither a direct `url` nor
 *   `file.url`) — callers fall back to rendering [body] as plain text
 */
fun parseMediaMessage(content: JSONObject?, body: String, typeKey: String = "msgtype", localContent: JSONObject? = null): MediaMessage? {
    if (content == null) return null

    val msgType = content.optString(typeKey, "")

    // Media is either encrypted (url nested in a `file` object) or unencrypted (top-level `url`).
    val directUrl = content.optString("url", "")
    val fileUrl = content.optJSONObject("file")?.optString("url", "") ?: ""
    val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl
    if (url.isBlank()) return null

    // For m.file, body is the filename when no explicit filename field is present (Matrix spec).
    // For other media types, body may be a caption or fallback text, so we don't use it as filename.
    val filename = content.optString("filename", "").takeIf { it.isNotBlank() }
        ?: if (msgType == "m.file") body.takeIf { it.isNotBlank() } ?: "" else ""

    // `info` is optional per the Matrix spec: an m.image/m.video/m.audio/m.file with only a
    // `url` (e.g. bridged media) is valid. Default to an empty object so a missing `info`
    // still renders as media (width=0 → renderer falls back to aspect-ratio sizing) instead
    // of dropping to the plain-text filename fallback.
    val info = content.optJSONObject("info") ?: JSONObject()

    val blurHash = info.optString("xyz.amorgan.blurhash").takeIf { it.isNotBlank() }
        ?: info.optString("blurhash").takeIf { it.isNotBlank() }

    // Thumbnails: encrypted media carries it as thumbnail_file.url, unencrypted as thumbnail_url.
    val thumbnailFile = info.optJSONObject("thumbnail_file")
    val thumbnailIsEncrypted = thumbnailFile != null
    val thumbnailUrl = if (thumbnailFile != null) {
        thumbnailFile.optString("url", "").takeIf { it.isNotBlank() }
    } else {
        info.optString("thumbnail_url", "").takeIf { it.isNotBlank() }
    }

    val thumbnailInfo = info.optJSONObject("thumbnail_info")
    val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
        ?: thumbnailInfo?.optString("blurhash")?.takeIf { it.isNotBlank() }
    // Nullable and only populated when > 0: a 0 here would break the renderer's size calculations,
    // which treat "no thumbnail dimensions" and "zero-sized thumbnail" very differently.
    val thumbnailWidth = thumbnailInfo?.optInt("w", 0)?.takeIf { it > 0 }
    val thumbnailHeight = thumbnailInfo?.optInt("h", 0)?.takeIf { it > 0 }

    val duration = if (msgType == "m.video" || msgType == "m.audio") {
        info.optInt("duration", 0).takeIf { it > 0 }
    } else {
        null
    }

    val waveform = if (msgType == "m.audio") extractWaveform(content) else null

    // MSC4230: true when the original image is animated (GIF, animated PNG, animated WebP).
    val isAnimated = if (msgType == "m.image") {
        info.optBoolean("is_animated", false).takeIf { info.has("is_animated") }
    } else {
        null
    }

    // Caption is only body if: 1) filename field exists, 2) body differs from filename, 3) body is
    // not blank. If the filename field is missing, body IS the filename, not a caption.
    val caption = if (filename.isNotBlank() && body != filename && body.isNotBlank()) {
        val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
        if (sanitizedHtml != null && sanitizedHtml != filename) sanitizedHtml else body
    } else {
        null
    }

    return MediaMessage(
        url = url,
        filename = filename,
        caption = caption,
        info = MediaInfo(
            width = info.optInt("w", 0),
            height = info.optInt("h", 0),
            size = info.optLong("size", 0),
            mimeType = info.optString("mimetype", ""),
            blurHash = blurHash,
            thumbnailUrl = thumbnailUrl,
            thumbnailBlurHash = thumbnailBlurHash,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight,
            duration = duration,
            thumbnailIsEncrypted = thumbnailIsEncrypted,
            isAnimated = isAnimated,
            waveform = waveform,
        ),
        msgType = msgType,
    )
}
