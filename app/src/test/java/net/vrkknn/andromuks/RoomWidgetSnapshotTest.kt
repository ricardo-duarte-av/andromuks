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
    fun `merge orders by timestamp, not by list position`() {
        // The regression this guards: an event that reaches the cache without a rowid mapping is
        // stored with timeline_rowid = 0, which sorts below every real rowid. Ordering the widget
        // on rowid therefore pushed the NEWEST message to the oldest end, where takeLast discarded
        // it — the widget showed a short, stale slice with a gap at the bottom.
        val older = sampleMessage("\$older").copy(timestamp = 1_000L)
        val newest = sampleMessage("\$newest").copy(timestamp = 3_000L)
        val middle = sampleMessage("\$middle").copy(timestamp = 2_000L)

        val merged = WidgetMessage.merge(listOf(newest, older, middle), emptyList(), limit = 10)

        assertEquals(listOf("\$older", "\$middle", "\$newest"), merged.map { it.eventId })
    }

    @Test
    fun `merge keeps the newest when over the limit`() {
        val messages = (1..10).map { sampleMessage("\$e$it").copy(timestamp = it * 1_000L) }

        val merged = WidgetMessage.merge(messages, emptyList(), limit = 3)

        assertEquals(listOf("\$e8", "\$e9", "\$e10"), merged.map { it.eventId })
    }

    @Test
    fun `merge replaces an optimistic row rather than doubling it`() {
        // The notification path writes a row built from the push payload; the refresh that follows
        // carries the authoritative version of the same event.
        val optimistic = sampleMessage("\$evt").copy(text = "📷 Image", timestamp = 5_000L)
        val authoritative = sampleMessage("\$evt").copy(text = "📷 Sent a photo", timestamp = 5_000L)

        val merged = WidgetMessage.merge(listOf(optimistic), listOf(authoritative), limit = 10)

        assertEquals(1, merged.size)
        assertEquals("📷 Sent a photo", merged.single().text)
    }

    @Test
    fun `merge appends genuinely new messages`() {
        val existing = listOf(sampleMessage("\$a").copy(timestamp = 1_000L))
        val incoming = listOf(sampleMessage("\$b").copy(timestamp = 2_000L))

        val merged = WidgetMessage.merge(existing, incoming, limit = 10)

        assertEquals(listOf("\$a", "\$b"), merged.map { it.eventId })
    }

    @Test
    fun `merge is stable for messages sharing a timestamp`() {
        // Bridged and bulk-sent messages routinely share a millisecond; ordering must still be
        // deterministic or rows would shuffle between refreshes.
        val a = sampleMessage("\$aaa").copy(timestamp = 1_000L)
        val b = sampleMessage("\$bbb").copy(timestamp = 1_000L)

        assertEquals(
            WidgetMessage.merge(listOf(a, b), emptyList(), limit = 10).map { it.eventId },
            WidgetMessage.merge(listOf(b, a), emptyList(), limit = 10).map { it.eventId },
        )
    }

    @Test
    fun `loading factory marks the widget as not yet fetched`() {
        val snapshot = RoomWidgetSnapshot.loading(room, "Test Room")

        assertEquals(RoomWidgetSnapshot.State.LOADING, snapshot.state)
        assertTrue(snapshot.messages.isEmpty())
        assertEquals(room, snapshot.roomId)
    }
}
