package net.vrkknn.andromuks

import org.json.JSONObject

/**
 * Sticker/emoji pack subscriptions — the account data key that decides which packs this account
 * loads, and the reads and writes over it.
 *
 * A pack is a `im.ponies.room_emotes` (or MSC2545 `m.image_pack`) **state event** in some room,
 * identified by its state key. Subscribing means listing `roomId → packName` under the
 * `rooms` object of the account data key; the sync path
 * ([SyncRoomsCoordinator.processAccountData]) then fetches each listed pack via
 * [AppViewModel.requestEmojiPackData]. Nothing else makes a pack load, so unsubscribing is simply
 * removing the entry and evicting the caches.
 *
 * The legacy-vs-official key choice lives here rather than at the sync call site so the read and
 * write sides cannot drift apart: writing a subscription into a key the loader does not read would
 * silently do nothing.
 */
internal class StickerPackCoordinator(private val vm: AppViewModel) {

    /** One subscribed pack: the room hosting it and the state key naming it. */
    data class PackRef(val roomId: String, val packName: String)

    /** The account data key in use and the room state event type that pairs with it. */
    data class PackKeys(val accountDataKey: String, val stateEventType: String)

    /**
     * A pack a room hosts, as the room-info browser renders it: enough to show a row without
     * re-parsing, plus whether this account already has it.
     */
    data class RoomPack(
        val roomId: String,
        val packName: String,
        val displayName: String,
        val stickerCount: Int,
        val emojiCount: Int,
        val thumbnailMxc: String?,
        val subscribed: Boolean,
        val stickerPack: AppViewModel.StickerPack?,
        val emojiPack: AppViewModel.EmojiPack?,
    )

    /**
     * Which key this account is using.
     *
     * The official MSC2545 key wins when present; otherwise the legacy im.ponies one. When neither
     * exists yet — a first subscription — we write the legacy key, which is what gomuks and the
     * maunium sticker picker produce and what every other client still reads.
     */
    fun activePackKeys(): PackKeys = resolveKeys(
        hasOfficial = AccountDataCache.getAccountData(OFFICIAL_KEY) != null,
    )

    /** Every pack currently subscribed to, in account data order. */
    fun subscribedPacks(): List<PackRef> {
        val key = activePackKeys().accountDataKey
        return parseSubscriptions(AccountDataCache.getAccountData(key)?.optJSONObject("content"))
    }

    /** True when [roomId]/[packName] is already subscribed. */
    fun isSubscribed(roomId: String, packName: String): Boolean = subscribedPacks().any { it.roomId == roomId && it.packName == packName }

    /**
     * Every pack [roomId] hosts, parsed straight from the room's cached state, subscribed or not.
     *
     * A room can host any number of packs as separate state keys; nothing surfaces them on its own
     * because only packs named in account data are ever fetched. Reads the state the room already
     * loaded on open, so this costs no round trip — and returns nothing for a room whose state is
     * not resident, which is the honest answer rather than a claim that it has no packs.
     */
    fun roomPackEntries(roomId: String): List<RoomPack> {
        val keys = activePackKeys()
        val subscribed = subscribedPacks().toSet()
        return net.vrkknn.andromuks.utils.RoomStateStore
            .getRawByType(roomId, keys.stateEventType)
            .mapNotNull { (packName, content) ->
                val parsed = net.vrkknn.andromuks.utils.StickerPackParsing
                    .parsePackContent(roomId, packName, content)
                val stickers = parsed.stickerPack?.stickers.orEmpty()
                val emojis = parsed.emojiPack?.emojis.orEmpty()
                if (stickers.isEmpty() && emojis.isEmpty()) {
                    return@mapNotNull null
                }
                RoomPack(
                    roomId = roomId,
                    packName = packName,
                    displayName = parsed.stickerPack?.displayName ?: parsed.emojiPack?.displayName ?: packName,
                    stickerCount = stickers.size,
                    emojiCount = emojis.size,
                    thumbnailMxc = stickers.firstOrNull()?.mxcUrl ?: emojis.firstOrNull()?.mxcUrl,
                    subscribed = PackRef(roomId, packName) in subscribed,
                    stickerPack = parsed.stickerPack,
                    emojiPack = parsed.emojiPack,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    /** The unsubscribed sticker packs of [roomId], for the sticker picker's trailing tabs. */
    fun roomPacks(roomId: String): List<AppViewModel.StickerPack> = roomPackEntries(roomId).filterNot {
        it.subscribed
    }.mapNotNull { it.stickerPack }

    /** The unsubscribed emoji packs of [roomId], for the emoji picker's trailing tabs. */
    fun roomEmojiPacks(roomId: String): List<AppViewModel.EmojiPack> = roomPackEntries(roomId).filterNot {
        it.subscribed
    }.mapNotNull { it.emojiPack }

    /**
     * Subscribe to a pack: write the account data entry, then fetch the pack immediately so it is
     * usable without waiting for the server to echo the account data back.
     */
    fun subscribe(roomId: String, packName: String) {
        val keys = activePackKeys()
        if (isSubscribed(roomId, packName)) return
        writeSubscriptions(keys.accountDataKey, roomId, packName, subscribed = true)
        vm.requestEmojiPackData(roomId, packName, keys.stateEventType)
        android.util.Log.i(
            "Andromuks",
            "StickerPackCoordinator: Subscribed to pack $packName in $roomId via ${keys.accountDataKey}",
        )
    }

    /**
     * Unsubscribe from a pack: drop the account data entry and evict both caches, so the pickers
     * stop offering it without waiting for a reconnect.
     */
    fun unsubscribe(roomId: String, packName: String) {
        val keys = activePackKeys()
        writeSubscriptions(keys.accountDataKey, roomId, packName, subscribed = false)
        StickerPacksCache.removePack(roomId, packName)
        EmojiPacksCache.removePack(roomId, packName)
        android.util.Log.i(
            "Andromuks",
            "StickerPackCoordinator: Unsubscribed from pack $packName in $roomId via ${keys.accountDataKey}",
        )
    }

    private fun writeSubscriptions(accountDataKey: String, roomId: String, packName: String, subscribed: Boolean) {
        val current = AccountDataCache.getAccountData(accountDataKey)?.optJSONObject("content")
        val updated = withSubscription(current, roomId, packName, subscribed)
        vm.accountDataCoordinator.setAccountDataRaw(accountDataKey, updated)
    }

    companion object {
        const val OFFICIAL_KEY = "m.image_pack.rooms"
        const val LEGACY_KEY = "im.ponies.emote_rooms"
        const val OFFICIAL_STATE_TYPE = "m.image_pack"
        const val LEGACY_STATE_TYPE = "im.ponies.room_emotes"

        /** Pure form of [activePackKeys], so the sync path and the tests can share it. */
        fun resolveKeys(hasOfficial: Boolean): PackKeys = if (hasOfficial) {
            PackKeys(OFFICIAL_KEY, OFFICIAL_STATE_TYPE)
        } else {
            PackKeys(LEGACY_KEY, LEGACY_STATE_TYPE)
        }

        /**
         * Flatten the `content` object of an emote-rooms account data key into pack references.
         * A room whose value is not an object, or is empty, contributes nothing.
         */
        fun parseSubscriptions(content: JSONObject?): List<PackRef> {
            val rooms = content?.optJSONObject("rooms") ?: return emptyList()
            val roomIds = rooms.names() ?: return emptyList()
            return (0 until roomIds.length())
                .map { roomIds.optString(it) }
                .filter { it.isNotBlank() }
                .flatMap { roomId ->
                    val packNames = rooms.optJSONObject(roomId)?.names()
                    if (packNames == null) {
                        emptyList()
                    } else {
                        (0 until packNames.length()).map { PackRef(roomId, packNames.optString(it)) }
                    }
                }
        }

        /**
         * The `content` object to write for adding or removing one subscription.
         *
         * Rebuilt from [current] rather than mutated in place so an optimistic cache update cannot
         * alias the object we just sent. A room key whose last pack is removed is dropped entirely
         * — an empty room object would have the loader request nothing while still claiming the
         * room is a pack source.
         */
        fun withSubscription(current: JSONObject?, roomId: String, packName: String, subscribed: Boolean): JSONObject {
            val existing = parseSubscriptions(current)
            val kept = existing.filterNot { it.roomId == roomId && it.packName == packName }
            val result = if (subscribed) kept + PackRef(roomId, packName) else kept

            val rooms = JSONObject()
            for (ref in result) {
                val room = rooms.optJSONObject(ref.roomId) ?: JSONObject().also { rooms.put(ref.roomId, it) }
                // The value is the pack's per-user overrides; we keep whatever shape was there, and
                // an empty object for packs we add — which is what every other client writes.
                room.put(ref.packName, preservedPackValue(current, ref))
            }
            return JSONObject().put("rooms", rooms)
        }

        private fun preservedPackValue(current: JSONObject?, ref: PackRef): JSONObject = current
            ?.optJSONObject("rooms")
            ?.optJSONObject(ref.roomId)
            ?.optJSONObject(ref.packName)
            ?: JSONObject()
    }
}
