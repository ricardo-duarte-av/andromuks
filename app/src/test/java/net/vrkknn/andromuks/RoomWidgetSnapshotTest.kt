package net.vrkknn.andromuks

import net.vrkknn.andromuks.widget.RoomWidgetSnapshot
import net.vrkknn.andromuks.widget.WidgetMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the room widget's persisted snapshot.
 *
 * The snapshot is the widget's *only* source of truth at paint time — there is no persisted
 * timeline to fall back on — so a codec bug does not degrade the widget, it blanks it. These assert
 * on parsed values rather than null-ness so they also pin the real `org.json` on the unit-test
 * classpath (see the note in `ReactionEventParsingTest` and `testOptions` in build.gradle.kts).
 */
class RoomWidgetSnapshotTest {

    private val room = "!room:example.org"

    private fun sampleMessage(id: String = "\$evt-1", sender: String = "@alice:example.org") = WidgetMessage(
        eventId = id,
        senderId = sender,
        senderName = "Alice",
        senderAvatarPath = "/data/cache/room_widget_avatars/abc.png",
        text = "📷 Sent a photo",
        timestamp = 1_700_000_000_000L,
    )

    @Test
    fun `round trips every field`() {
        val original = RoomWidgetSnapshot(
            roomId = room,
            roomName = "Test Room",
            roomAvatarPath = "/data/cache/room_widget_avatars/room.png",
            messages = listOf(sampleMessage("\$a"), sampleMessage("\$b", "@bob:example.org")),
            updatedAt = 1_700_000_123_456L,
            state = RoomWidgetSnapshot.State.OK,
            stale = true,
            refreshing = true,
        )

        val restored = RoomWidgetSnapshot.fromJson(original.toJson())

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `preserves message order and content`() {
        val snapshot = RoomWidgetSnapshot(
            roomId = room,
            roomName = "Test Room",
            messages = listOf(
                sampleMessage("\$first").copy(text = "first"),
                sampleMessage("\$second").copy(text = "second"),
                sampleMessage("\$third").copy(text = "third"),
            ),
        )

        val restored = RoomWidgetSnapshot.fromJson(snapshot.toJson())!!

        assertEquals(listOf("first", "second", "third"), restored.messages.map { it.text })
        assertEquals(listOf("\$first", "\$second", "\$third"), restored.messages.map { it.eventId })
    }

    @Test
    fun `keeps a null avatar null rather than inventing an empty path`() {
        // An empty-string path would make the widget try to decode "" on every paint.
        val snapshot = RoomWidgetSnapshot(
            roomId = room,
            roomName = "Test Room",
            roomAvatarPath = null,
            messages = listOf(sampleMessage().copy(senderAvatarPath = null)),
        )

        val restored = RoomWidgetSnapshot.fromJson(snapshot.toJson())!!

        assertNull(restored.roomAvatarPath)
        assertNull(restored.messages.single().senderAvatarPath)
    }

    @Test
    fun `rejects a snapshot written by a different schema version`() {
        val future = RoomWidgetSnapshot(room, "Test Room").toJson().put("v", RoomWidgetSnapshot.SCHEMA_VERSION + 1)

        // Discarded, not migrated — the widget then re-fetches rather than rendering guesses.
        assertNull(RoomWidgetSnapshot.fromJson(future))
    }

    @Test
    fun `rejects json with no room id`() {
        assertNull(RoomWidgetSnapshot.fromJson(JSONObject().put("v", RoomWidgetSnapshot.SCHEMA_VERSION)))
        assertNull(RoomWidgetSnapshot.fromJson(null))
    }

    @Test
    fun `drops malformed messages but keeps the rest of the snapshot`() {
        val json = RoomWidgetSnapshot(
            roomId = room,
            roomName = "Test Room",
            messages = listOf(sampleMessage("\$good")),
        ).toJson()
        json.getJSONArray("messages").put(JSONObject().put("sender_name", "No event id"))

        val restored = RoomWidgetSnapshot.fromJson(json)!!

        assertEquals("Test Room", restored.roomName)
        assertEquals(listOf("\$good"), restored.messages.map { it.eventId })
    }

    @Test
    fun `falls back to OK for an unrecognised state name`() {
        val json = RoomWidgetSnapshot(room, "Test Room").toJson().put("state", "SOMETHING_NEW")

        assertEquals(RoomWidgetSnapshot.State.OK, RoomWidgetSnapshot.fromJson(json)!!.state)
    }

    @Test
    fun `loading factory marks the widget as not yet fetched`() {
        val snapshot = RoomWidgetSnapshot.loading(room, "Test Room")

        assertEquals(RoomWidgetSnapshot.State.LOADING, snapshot.state)
        assertTrue(snapshot.messages.isEmpty())
        assertEquals(room, snapshot.roomId)
    }
}
