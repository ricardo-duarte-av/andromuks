package net.vrkknn.andromuks

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the backend's `meta.has_member_list` flag per room — i.e. whether gomuks' own database
 * holds the room's complete `m.room.member` state, or only the lazy-loaded subset that came with
 * the timeline.
 *
 * Why this exists: `get_room_state` returns whatever gomuks has. For a room it never fetched
 * members for, that is a handful of senders, not the member list — so RoomInfo's member dialog and
 * the `@`-mention picker silently show a partial room. `fetch_members=true` is the fix, but it is a
 * federated round trip per room, so it must only fire where it is actually needed.
 *
 * Values:
 *   - absent → unknown (no sync meta seen yet this process). Never triggers a fetch: the first
 *     sync_complete after connect carries meta for every room, and before that we aren't connected
 *     enough to issue the command anyway.
 *   - false  → gomuks says it does NOT have the list. Opening the room tops it up.
 *   - true   → gomuks has it, or we have already asked it to fetch this process.
 *
 * [markFetchRequested] sets the flag optimistically at request time rather than waiting for the
 * echo. gomuks only re-dispatches a room's meta when something visible changed, so a room whose
 * *only* change is `has_member_list: false → true` may never produce a sync we can observe; without
 * the optimistic mark every single room open would re-issue the fetch. A later sync that genuinely
 * says `false` (fetch failed, state reset) wins over the mark and the next open retries.
 *
 * Deliberately in-memory only. The flag is a statement about the *backend's* current database, it
 * is re-sent in full on every connect, and persisting it would mean acting on a claim that may be
 * hours stale — the DB migration would buy nothing but a wrong answer on cold start.
 */
object RoomMemberListStatus {
    private val hasMemberList = ConcurrentHashMap<String, Boolean>()

    /** Records `meta.has_member_list` from a sync_complete room object. */
    fun setFromSync(roomId: String, value: Boolean) {
        hasMemberList[roomId] = value
    }

    /**
     * True when the backend has told us it lacks this room's member list. Unknown rooms return
     * false — see the class docs; we never guess a federated fetch into existence.
     */
    fun needsFetch(roomId: String): Boolean = hasMemberList[roomId] == false

    /** Marks a `fetch_members=true` request as issued, so repeat opens don't re-fetch. */
    fun markFetchRequested(roomId: String) {
        hasMemberList[roomId] = true
    }

    /** Clears everything. Called on `clear_state`, alongside the other singleton caches. */
    fun clear() {
        hasMemberList.clear()
    }
}
