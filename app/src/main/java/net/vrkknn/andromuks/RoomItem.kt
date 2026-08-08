package net.vrkknn.andromuks

import androidx.compose.runtime.Immutable

// roomId: String
// name: String
// lastMessagePreview: String?
// unreadCount: Int
// avatarUrl: String?
// isInvite: Boolean

@Immutable
data class RoomItem(
    val id: String,
    val name: String,
    val messagePreview: String?,
    val messageSender: String?,
    val unreadCount: Int?,
    val highlightCount: Int?,
    val avatarUrl: String?,
    val sortingTimestamp: Long? = null,
    val isDirectMessage: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,
    val bridgeProtocolAvatarUrl: String? = null,
    /**
     * Stable identity of the remote network this room is bridged to (`BridgeInfo.protocolId`,
     * e.g. `"discord"`), or null if not bridged / not yet resolved from get_room_state.
     *
     * The Bridges tab groups on THIS, not on [bridgeProtocolAvatarUrl]: two bridge
     * implementations for the same network report the same protocol id but different icons
     * (mautrix-discord ships the Discord logo on `protocol`, OOYE ships a per-guild icon on
     * `network`), and avatar-URL grouping splintered them into one pseudo-space per guild.
     */
    val bridgeProtocolId: String? = null,
    /**
     * True when [bridgeProtocolAvatarUrl] came from the event's `protocol` descriptor — i.e. it is
     * the network's own logo, identical for every room on that protocol. False when it fell back to
     * the `network`/`channel` descriptor, which is per-instance (a Discord guild icon).
     *
     * The Bridges tab needs this to pick ONE stable icon for a pseudo-space whose rooms disagree:
     * a group mixing mautrix-discord (Discord logo) and OOYE (per-guild icons) must show the logo,
     * not whichever room happens to have been active most recently.
     */
    val bridgeAvatarIsProtocolLevel: Boolean = false,
    /**
     * Non-null when the room has been tombstoned. Never cleared once observed — a tombstone is
     * permanent, so the sync merges preserve it the same way they preserve bridge info.
     */
    val tombstone: TombstoneInfo? = null,
    val canonicalAlias: String? = null,
    val latestEventId: String? = null,
    /**
     * Display name of [messageSender], resolved at sync-parse time from RoomMemberCache.
     * Cached here so RoomListItem doesn't have to do a per-row RoomMemberCache lookup —
     * the previous pattern read appViewModel.memberUpdateCounter inside `remember`, which
     * invalidated all 500+ rows on any user profile update. Refreshed per-room (only the
     * rooms whose messageSender matches a changed user are updated) by
     * AppViewModel.refreshSenderDisplayNameForRooms.
     * Null means "not yet resolved" — RoomListItem falls back to usernameFromMatrixId.
     */
    val senderDisplayName: String? = null,
)

/**
 * A room that has been tombstoned — upgraded to a new room version, replaced after corruption, or
 * otherwise closed off. Parsed from `meta.tombstone` in sync_complete (the backend surfaces it
 * there rather than making clients wait for the `m.room.tombstone` state event to arrive in a
 * timeline).
 *
 * Both fields are optional in practice: a tombstone can carry no reason, and a room can be closed
 * without a successor. The mere presence of the object is what marks the room dead.
 */
@Immutable
data class TombstoneInfo(val body: String?, val replacementRoomId: String?)

@Immutable
data class SpaceItem(val id: String, val name: String, val avatarUrl: String?, val rooms: List<RoomItem>)

@Immutable
data class RoomSection(val type: RoomSectionType, val rooms: List<RoomItem>, val spaces: List<SpaceItem> = emptyList(), val unreadCount: Int = 0)

enum class RoomSectionType {
    HOME,
    SPACES,
    DIRECT_CHATS,
    UNREAD,
    FAVOURITES,
    BRIDGES,
    MENTIONS,
}

@Immutable
data class SyncUpdateResult(
    val updatedRooms: List<RoomItem>,
    val newRooms: List<RoomItem>,
    val removedRoomIds: List<String>,
    // Room IDs whose `account_data.m.tag` was actually present in this delta, so their
    // isFavourite/isLowPriority values are AUTHORITATIVE (a complete truth, including
    // removals). For rooms NOT in this set the delta didn't mention tags, so the merge must
    // preserve the existing flags instead of overwriting them. See the merge sites in
    // SyncRoomsCoordinator.processParsedSyncResult — without this a tag REMOVAL made on
    // another client while disconnected (delivered by a catchup sync) would be re-stuck by
    // the OR-merge.
    val authoritativeTagRoomIds: Set<String> = emptySet(),
)

@Immutable
data class RoomState(
    val roomId: String,
    val name: String?,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?,
    // Tri-state: null means "we have never fetched this room's state", which must render as
    // unknown rather than as an open padlock. A non-null value came from a get_room_state
    // response, which always carries the complete room state and is therefore authoritative.
    val isEncrypted: Boolean? = null,
    val powerLevels: PowerLevelsInfo? = null,
    val pinnedEventIds: List<String> = emptyList(),
    val bridgeInfo: BridgeInfo? = null,
)

/** Power levels information for a room */
@Immutable
data class PowerLevelsInfo(
    val users: Map<String, Int>,
    val usersDefault: Int,
    val redact: Int,
    val kick: Int = 50, // Default kick power level
    val ban: Int = 50, // Default ban power level
    val invite: Int = 50, // Default invite power level (used to accept knocks)
    val events: Map<String, Int> = emptyMap(),
    val eventsDefault: Int = 0,
    val stateDefault: Int = 50, // Per Matrix spec: default PL for state events not in events map
)

@Immutable
data class RoomAnimationState(
    val roomId: String,
    val lastUpdateTime: Long,
    val isAnimating: Boolean = false,
    val previousPosition: Int? = null,
    val currentPosition: Int? = null,
)

/**
 * Bridge delivery info for a single message sent via a Mautrix bridge.
 *
 * @param sentAt     Timestamp (ms) of the first SUCCESS com.beeper.message_send_status event —
 *                   i.e. when the bridge confirmed the message reached the other network.
 * @param deliveries Map of userId → timestamp (ms) for each user confirmed to have received it.
 */
data class BridgeDeliveryInfo(val sentAt: Long? = null, val deliveries: Map<String, Long> = emptyMap())
