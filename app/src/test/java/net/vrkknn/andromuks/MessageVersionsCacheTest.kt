package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MessageVersionsCache], which holds a message's edit history keyed by the *original*
 * event ID, plus an `editEventId -> originalEventId` reverse index.
 *
 * The reverse index is the part with teeth: an edit arriving from sync references only its own event
 * ID, so without it the app cannot tell which message an edit belongs to.
 */
class MessageVersionsCacheTest {

    private val original = "\$original"
    private val edit1 = "\$edit1"
    private val edit2 = "\$edit2"

    private fun event(id: String) = TimelineEvent.fromJson(
        JSONObject("""{"room_id": "!r:example.org", "event_id": "$id", "sender": "@a:example.org", "type": "m.room.message", "content": {"body": "$id"}}"""),
    )

    /** A message with its original plus [edits], newest first, as the cache expects. */
    private fun versioned(originalId: String = original, vararg edits: String): VersionedMessage {
        val versions = edits.mapIndexed { i, editId ->
            MessageVersion(eventId = editId, event = event(editId), timestamp = (i + 2) * 1000L, isOriginal = false)
        } + MessageVersion(eventId = originalId, event = event(originalId), timestamp = 1000L, isOriginal = true)
        return VersionedMessage(originalEventId = originalId, originalEvent = event(originalId), versions = versions)
    }

    @Before
    fun setUp() = MessageVersionsCache.clear()

    @After
    fun tearDown() = MessageVersionsCache.clear()

    @Test
    fun `versions round-trip by original event id`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))

        assertNotNull(MessageVersionsCache.getVersion(original))
        assertEquals(original, MessageVersionsCache.getVersion(original)?.originalEventId)
        assertNull(MessageVersionsCache.getVersion("\$unknown"))
    }

    @Test
    fun `every edit is indexed back to its original`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1, edit2)))

        assertEquals(original, MessageVersionsCache.getOriginalEventId(edit1))
        assertEquals(original, MessageVersionsCache.getOriginalEventId(edit2))
    }

    @Test
    fun `the original is not indexed as an edit of itself`() {
        // The reverse index answers "which message does this edit belong to?"; a self-mapping would
        // make the original look like an edit and could send resolution in a circle.
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))

        assertNull(MessageVersionsCache.getOriginalEventId(original))
    }

    @Test
    fun `re-storing a message adds newly arrived edits to the index`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1, edit2)))

        assertEquals(2, MessageVersionsCache.getVersion(original)?.versions?.count { !it.isOriginal })
        assertEquals(original, MessageVersionsCache.getOriginalEventId(edit2))
    }

    @Test
    fun `clearForEventIds drops a message and the index entries pointing at it`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1, edit2)))

        MessageVersionsCache.clearForEventIds(setOf(original))

        assertNull(MessageVersionsCache.getVersion(original))
        assertNull("edits of an evicted original must not linger", MessageVersionsCache.getOriginalEventId(edit1))
        assertNull(MessageVersionsCache.getOriginalEventId(edit2))
    }

    @Test
    fun `clearForEventIds also drops an evicted edit's own index entry`() {
        // Eviction is by event id and an edit is an event too, so the edit itself can be the one
        // trimmed out of a room while its original survives.
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1, edit2)))

        MessageVersionsCache.clearForEventIds(setOf(edit1))

        assertNull(MessageVersionsCache.getOriginalEventId(edit1))
        assertEquals("the surviving edit keeps its mapping", original, MessageVersionsCache.getOriginalEventId(edit2))
        assertNotNull("the original itself is untouched", MessageVersionsCache.getVersion(original))
    }

    @Test
    fun `clearForEventIds leaves unrelated messages alone`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))
        MessageVersionsCache.updateVersion("\$other", versioned("\$other", "\$otherEdit"))

        MessageVersionsCache.clearForEventIds(setOf(original))

        assertNotNull(MessageVersionsCache.getVersion("\$other"))
        assertEquals("\$other", MessageVersionsCache.getOriginalEventId("\$otherEdit"))
    }

    @Test
    fun `clearForEventIds tolerates unknown ids`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))

        MessageVersionsCache.clearForEventIds(setOf("\$nothing"))
        MessageVersionsCache.clearForEventIds(emptySet())

        assertNotNull(MessageVersionsCache.getVersion(original))
    }

    @Test
    fun `clear empties both the versions and the index`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))

        MessageVersionsCache.clear()

        assertTrue(MessageVersionsCache.getAllVersions().isEmpty())
        assertNull(MessageVersionsCache.getOriginalEventId(edit1))
    }

    @Test
    fun `getAllVersions returns a snapshot, not a live view`() {
        MessageVersionsCache.updateVersion(original, versioned(edits = arrayOf(edit1)))

        val snapshot = MessageVersionsCache.getAllVersions()
        MessageVersionsCache.updateVersion("\$other", versioned("\$other", "\$otherEdit"))

        assertEquals(1, snapshot.size)
        assertEquals(2, MessageVersionsCache.getAllVersions().size)
    }
}
