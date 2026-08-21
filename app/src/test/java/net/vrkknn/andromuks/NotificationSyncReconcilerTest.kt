package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NotificationSyncReconciler.collectDismissibleRooms] — the pure selector behind clearing
 * a notification from `sync_complete` instead of waiting for a dismiss push that gomuks may never
 * send, may cap away, or may ship at a priority Doze defers.
 *
 * The selector is deliberately split out from the Android calls so it can be tested at all; the
 * assertions below check the *selected room ids*, not just the shape of the traversal, because
 * `isReturnDefaultValues` would let a shape-only test pass while parsing nothing (see the testOptions
 * note in app/build.gradle.kts).
 */
class NotificationSyncReconcilerTest {

    /** Build a `sync_complete`-shaped payload from raw per-room JSON fragments. */
    private fun sync(vararg rooms: Pair<String, String>): JSONObject {
        val roomsObj = JSONObject()
        for ((id, body) in rooms) roomsObj.put(id, JSONObject(body))
        return JSONObject().put("data", JSONObject().put("rooms", roomsObj))
    }

    private fun meta(unread: Int = 0, highlights: Int = 0) = """{"unread_messages":$unread,"unread_highlights":$highlights}"""

    @Test
    fun `explicit dismiss_notifications selects the room`() {
        val json = sync("!a:example.org" to """{"dismiss_notifications":true}""")
        assertEquals(setOf("!a:example.org"), NotificationSyncReconciler.collectDismissibleRooms(json, emptySet()))
    }

    @Test
    fun `explicit dismiss_notifications does not require a posted notification`() {
        // The backend's own signal is authoritative — cancel() is a harmless no-op if nothing is up,
        // and recording the tombstone still matters for an in-flight post (Race 1).
        val json = sync("!a:example.org" to """{"dismiss_notifications":true,"meta":${meta(unread = 3)}}""")
        assertTrue("!a:example.org" in NotificationSyncReconciler.collectDismissibleRooms(json, emptySet()))
    }

    @Test
    fun `zeroed unread selects a room that has a notification posted`() {
        val json = sync("!a:example.org" to """{"meta":${meta()}}""")
        assertEquals(
            setOf("!a:example.org"),
            NotificationSyncReconciler.collectDismissibleRooms(json, setOf("!a:example.org")),
        )
    }

    @Test
    fun `zeroed unread is ignored for a room with no notification posted`() {
        // Unread-zero is the steady state for nearly every room in the account. Without the
        // posted-rooms gate this arm would fire on all of them, every sync.
        val json = sync("!a:example.org" to """{"meta":${meta()}}""")
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(json, emptySet()).isEmpty())
    }

    @Test
    fun `non-zero unread is not dismissible`() {
        val json = sync("!a:example.org" to """{"meta":${meta(unread = 2)}}""")
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(json, setOf("!a:example.org")).isEmpty())
    }

    @Test
    fun `a highlight alone keeps the room undismissible`() {
        val json = sync("!a:example.org" to """{"meta":${meta(unread = 0, highlights = 1)}}""")
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(json, setOf("!a:example.org")).isEmpty())
    }

    @Test
    fun `zeroed unread with a fresh notification in the same sync is not dismissible`() {
        // Read on another device, then a new message lands in the same batch. The new message must
        // still notify — dismissing here would swallow it.
        val json = sync(
            "!a:example.org" to """{"meta":${meta()},"notifications":[{"event_rowid":42}]}""",
        )
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(json, setOf("!a:example.org")).isEmpty())
    }

    @Test
    fun `explicit dismiss wins even alongside a notifications array`() {
        // dismiss_notifications is checked before the notifications guard; gomuks only sets it when
        // the batch produced no new notifications for the room, so this pairing means the flag is
        // what the backend actually decided.
        val json = sync(
            "!a:example.org" to """{"dismiss_notifications":true,"notifications":[{"event_rowid":42}]}""",
        )
        assertEquals(setOf("!a:example.org"), NotificationSyncReconciler.collectDismissibleRooms(json, emptySet()))
    }

    @Test
    fun `a room object with no meta is skipped`() {
        // Rooms carrying only timeline or receipt deltas arrive without meta; absent counters are
        // not evidence of being read.
        val json = sync("!a:example.org" to """{"timeline":[]}""")
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(json, setOf("!a:example.org")).isEmpty())
    }

    @Test
    fun `a payload with no rooms yields nothing`() {
        assertTrue(
            NotificationSyncReconciler.collectDismissibleRooms(JSONObject("""{"data":{}}"""), setOf("!a:example.org")).isEmpty(),
        )
        assertTrue(NotificationSyncReconciler.collectDismissibleRooms(JSONObject("{}"), setOf("!a:example.org")).isEmpty())
    }

    @Test
    fun `both arms select across a multi-room sync`() {
        val json = sync(
            "!explicit:example.org" to """{"dismiss_notifications":true}""",
            "!read:example.org" to """{"meta":${meta()}}""",
            "!unread:example.org" to """{"meta":${meta(unread = 5)}}""",
            "!untracked:example.org" to """{"meta":${meta()}}""",
        )
        val posted = setOf("!read:example.org", "!unread:example.org")
        assertEquals(
            setOf("!explicit:example.org", "!read:example.org"),
            NotificationSyncReconciler.collectDismissibleRooms(json, posted),
        )
    }
}
