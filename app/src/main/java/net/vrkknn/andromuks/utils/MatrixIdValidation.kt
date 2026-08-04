package net.vrkknn.andromuks.utils

/**
 * Boundary validation for Matrix IDs coming in from untrusted sources (intent extras,
 * external URIs). Used by MainActivity and ShortcutActivity, both of which are exported.
 *
 * Without this, any third-party app on the device can call
 *   startActivity(Intent().setComponent(...).putExtra("room_id", "anything"))
 * and force the app to open a chosen room (UI spoof) or cause the homeserver to receive
 * paginate requests for arbitrary IDs.
 *
 * The validation is intentionally permissive on character set (the spec allows a lot)
 * but strict on the leading sigil and overall sanity — any control character, space,
 * or non-ASCII byte fails immediately. We do not enforce the optional ":server" suffix
 * because modern (v12) room IDs omit it.
 */

/** Hard upper bound — Matrix IDs in the wild top out around 100 chars, 255 leaves headroom. */
private const val MAX_MATRIX_ID_LEN = 255

/**
 * Returns true if [roomId] looks like a syntactically valid Matrix room ID.
 *
 * Requirements:
 *  - Non-null, non-blank.
 *  - Starts with `!` (Matrix room ID sigil).
 *  - At most [MAX_MATRIX_ID_LEN] characters.
 *  - Every byte is printable ASCII (0x21..0x7E) — no whitespace, no controls, no UTF-8.
 *
 * This is a defensive shape check, not a spec-conformant parser. It is the minimum
 * needed to refuse intent injection from third-party apps.
 */
internal fun isValidMatrixRoomId(roomId: String?): Boolean {
    if (roomId.isNullOrBlank()) return false
    if (!roomId.startsWith("!")) return false
    if (roomId.length > MAX_MATRIX_ID_LEN) return false
    for (i in roomId.indices) {
        val c = roomId[i].code
        if (c < 0x21 || c > 0x7E) return false
    }
    return true
}

/**
 * The server name embedded in a room ID, or null when the ID does not carry one.
 *
 * Only legacy room IDs have a server part (`!opaque:example.org`); **room version 12 dropped it**,
 * so a v12 ID is a bare opaque string (`!gomuks2fjNJgXSZ-lZPoQWB_2za-KW_l2Hs6roxWKk4`) and no server
 * can be derived from it at all. Callers must treat null as "omit `via`", never as a reason to
 * invent one — `via` is `omitempty` in both `JoinRoomParams` and `GetRoomSummaryParams`, so leaving
 * it out is well-defined, while sending a bogus server is not.
 *
 * Everything after the first colon is returned, so a server with an explicit port
 * (`!opaque:example.org:8448`) keeps it. Note this is a best-effort hint for `via`, not a
 * spec-conformant parse: the server part of a room ID is only ever the server that *created* the
 * room, which may no longer be resident in it.
 */
internal fun serverNameFromRoomId(roomId: String): String? = roomId.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotBlank() }
