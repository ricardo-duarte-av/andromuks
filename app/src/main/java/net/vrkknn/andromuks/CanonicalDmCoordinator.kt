package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.RoomMetadataStore

/**
 * The canonical DM index: which person a DM room is with, and which room is *the* room for a person.
 *
 * Matrix has no dialable address for a person — a DM is a room, and until an MSC for canonical DMs
 * lands there is no interoperable way to say "this room is the DM with Alice". This is the local
 * equivalent, and it is what lets the rest of the app talk about a DM as a *person*: the Telecom
 * call address, the contact-card call actions, and the person shortcuts all need an mxid where they
 * currently only have a room id.
 *
 * Both directions are resolved from data the app already has:
 *
 * - **room → person** is `meta.dm_user_id`, which gomuks sends on the initial sync and thereafter
 *   only when a room's metadata changed. Because it is absent from most syncs it is persisted and
 *   sticky-merged rather than recomputed ([RoomItem.directUserId]).
 * - **person → room** is [RoomListUiCoordinator.getDirectRoomIdForUser], which already picks the
 *   most recently active candidate — that selection *is* the canonicalisation rule, so this
 *   delegates to it rather than inventing a second one.
 *
 * Every resolution degrades: an unknown partner is always a valid answer and callers must treat it
 * as "address the room instead", never as an error.
 */
internal class CanonicalDmCoordinator(private val vm: AppViewModel) {

    /**
     * The other party in a DM, or null when the room is not a DM or the partner cannot be resolved.
     *
     * Ordered cheapest and most authoritative first:
     * 1. the in-memory room, where a sync or a cold-start hydrate has already put it;
     * 2. the persisted row, which answers before the first `sync_complete` of a cold process;
     * 3. `m.direct`, authoritative but in-memory only and only after account data has arrived;
     * 4. the room's own membership, for rooms none of the above ever named.
     */
    fun getDirectUserIdForRoom(roomId: String): String? = with(vm) {
        if (roomId.isBlank()) return null
        roomMap[roomId]?.directUserId?.takeIf { it.isNotBlank() }?.let { return it }
        RoomMetadataStore.getRow(roomId)?.dmUserId?.takeIf { it.isNotBlank() }?.let { return it }
        directMessageUserMap.entries.firstOrNull { (_, roomIds) -> roomIds.contains(roomId) }?.key?.let { return it }
        return accountDataCoordinator.inferSingleOtherMemberMxid(roomId)
    }

    /**
     * The canonical DM room for a person, or null when we have never had one.
     *
     * Delegates to the existing resolver — which prefers `m.direct`, falls back to scanning DM rooms'
     * membership, and breaks ties on most recent activity.
     */
    fun getCanonicalDmRoomIdForUser(userId: String): String? = vm.getDirectRoomIdForUser(userId)
}
