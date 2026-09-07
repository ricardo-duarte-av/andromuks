package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.AppViewModel
import org.json.JSONObject

/**
 * Parsing for one image pack — the `content` of an `im.ponies.room_emotes` / `m.image_pack` state
 * event — into the emoji and sticker packs the pickers render.
 *
 * Two callers need this and must agree: the response handler for a *subscribed* pack fetched by
 * state key, and the in-room scan that offers a room's *unsubscribed* packs. Keeping the usage
 * rules in one pure function is what stops the same pack from looking different depending on how
 * it was reached — and makes those rules testable without a WebSocket.
 */
object StickerPackParsing {

    /** What one pack's content yields. Either half is null when the pack has nothing of that kind. */
    data class ParsedPack(val emojiPack: AppViewModel.EmojiPack?, val stickerPack: AppViewModel.StickerPack?)

    /**
     * Parse [content] into an emoji pack and/or a sticker pack.
     *
     * Per MSC2545 each image carries a `usage` array: `emoticon` for inline emoji, `sticker` for
     * the sticker picker, both when it lists both. **An absent or empty `usage` means both** — that
     * is the spec's default and the reason most packs show up in either picker.
     *
     * Images without a usable `mxc://` URL are skipped rather than failing the pack.
     */
    fun parsePackContent(roomId: String, packName: String, content: JSONObject?): ParsedPack {
        val images = content?.optJSONObject("images") ?: return ParsedPack(null, null)
        val displayName = content.optJSONObject("pack")?.optString("display_name")?.takeIf {
            it.isNotBlank()
        } ?: packName

        val emojis = mutableListOf<AppViewModel.CustomEmoji>()
        val stickers = mutableListOf<AppViewModel.Sticker>()

        val imageKeys = images.names()
        val names = (0 until (imageKeys?.length() ?: 0)).mapNotNull { imageKeys?.optString(it) }
        for (imageName in names) {
            val imageData = images.optJSONObject(imageName)
            val mxcUrl = imageData?.optString("url").orEmpty()
            if (!mxcUrl.startsWith("mxc://")) continue

            val info = imageData?.optJSONObject("info")
            val usage = imageData?.optJSONArray("usage")
            val declared = (0 until (usage?.length() ?: 0)).map { usage?.optString(it) }
            val hasSticker = declared.isEmpty() || declared.contains("sticker")
            val hasEmoticon = declared.isEmpty() || declared.contains("emoticon")

            if (hasSticker) {
                stickers.add(
                    AppViewModel.Sticker(
                        name = imageName,
                        mxcUrl = mxcUrl,
                        body = imageName,
                        info = info,
                    ),
                )
            }
            if (hasEmoticon) {
                emojis.add(AppViewModel.CustomEmoji(name = imageName, mxcUrl = mxcUrl, info = info))
            }
        }

        return ParsedPack(
            emojiPack = emojis.takeIf { it.isNotEmpty() }?.let {
                AppViewModel.EmojiPack(packName = packName, displayName = displayName, roomId = roomId, emojis = it)
            },
            stickerPack = stickers.takeIf { it.isNotEmpty() }?.let {
                AppViewModel.StickerPack(packName = packName, displayName = displayName, roomId = roomId, stickers = it)
            },
        )
    }
}
