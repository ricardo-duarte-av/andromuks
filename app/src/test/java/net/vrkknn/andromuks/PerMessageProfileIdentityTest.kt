package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.isConsecutiveMessage
import net.vrkknn.andromuks.utils.isOwnMessage
import net.vrkknn.andromuks.utils.perMessageProfileForEdit
import net.vrkknn.andromuks.utils.stripPerMessageProfileFallback
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping and ownership for messages carrying `com.beeper.per_message_profile`.
 *
 * GH #37: any per-message profile used to break grouping outright, and "mine" was decided by the
 * profile's `id` alone. Our own MSC4461 profiles have ids like `black_cat`, so every message we sent
 * under one started a new group, rendered left-aligned as someone else's, and could not be edited.
 * The bridge case the old rule protected — one relay bot speaking for many remote users — must keep
 * working, which is why grouping compares the profile rather than ignoring it.
 */
class PerMessageProfileIdentityTest {

    private val me = "@me:example.org"
    private val bot = "@relaybot:example.org"

    private fun profile(id: String?, name: String = "Name", avatar: String? = null) = JSONObject().apply {
        if (id != null) put("id", id)
        put("displayname", name)
        if (avatar != null) put("avatar_url", avatar)
    }

    private fun message(sender: String, ts: Long, pmp: JSONObject? = null, e2ee: Boolean = false): TimelineEvent {
        val body = JSONObject().put("msgtype", "m.text").put("body", "hi")
        if (pmp != null) body.put("com.beeper.per_message_profile", pmp)
        return TimelineEvent(
            rowid = 0L,
            timelineRowid = ts,
            roomId = "!room:example.org",
            eventId = "\$e$ts",
            sender = sender,
            type = if (e2ee) "m.room.encrypted" else "m.room.message",
            timestamp = ts,
            content = if (e2ee) JSONObject() else body,
            decrypted = if (e2ee) body else null,
        )
    }

    @Test
    fun `messages under the same own profile group together`() {
        val cat = profile("black_cat")
        assertTrue(isConsecutiveMessage(message(me, 1_000, cat), message(me, 2_000, cat)))
    }

    @Test
    fun `same profile inside encrypted payloads groups together`() {
        val cat = profile("black_cat")
        assertTrue(isConsecutiveMessage(message(me, 1_000, cat, e2ee = true), message(me, 2_000, cat, e2ee = true)))
    }

    @Test
    fun `switching profile starts a new group`() {
        assertFalse(isConsecutiveMessage(message(me, 1_000, profile("black_cat")), message(me, 2_000, profile("dog"))))
    }

    @Test
    fun `switching between a profile and no profile starts a new group`() {
        assertFalse(isConsecutiveMessage(message(me, 1_000), message(me, 2_000, profile("black_cat"))))
        assertFalse(isConsecutiveMessage(message(me, 1_000, profile("black_cat")), message(me, 2_000)))
    }

    @Test
    fun `relay bot speaking for different remote users does not group`() {
        val alice = profile("@alice:remote", "Alice")
        val bob = profile("@bob:remote", "Bob")
        assertFalse(isConsecutiveMessage(message(bot, 1_000, alice), message(bot, 2_000, bob)))
        assertTrue(isConsecutiveMessage(message(bot, 1_000, alice), message(bot, 2_000, alice)))
    }

    @Test
    fun `id-less profiles are told apart by how they look`() {
        val a = profile(null, "Alice", "mxc://x/a")
        val b = profile(null, "Bob", "mxc://x/b")
        assertTrue(isConsecutiveMessage(message(bot, 1_000, a), message(bot, 2_000, profile(null, "Alice", "mxc://x/a"))))
        assertFalse(isConsecutiveMessage(message(bot, 1_000, a), message(bot, 2_000, b)))
    }

    @Test
    fun `plain messages still group by sender and time window`() {
        assertTrue(isConsecutiveMessage(message(me, 1_000), message(me, 2_000)))
        assertFalse(isConsecutiveMessage(message(me, 1_000), message(bot, 2_000)))
        assertFalse(isConsecutiveMessage(message(me, 0), message(me, 5 * 60 * 1000L + 1)))
        assertFalse(isConsecutiveMessage(null, message(me, 1_000)))
    }

    @Test
    fun `a message we sent is ours whatever profile it wears`() {
        assertTrue(isOwnMessage(me, profile("black_cat"), me))
    }

    @Test
    fun `a relayed message is ours only when the profile names us`() {
        assertTrue(isOwnMessage(bot, profile(me), me))
        assertFalse(isOwnMessage(bot, profile("@alice:remote"), me))
        assertFalse(isOwnMessage(bot, null, me))
    }

    @Test
    fun `edit carries the original profile without has_fallback`() {
        val original = profile("black_cat", "Cat", "mxc://x/cat").put("has_fallback", true).put("extra", "kept")
        val forEdit = perMessageProfileForEdit(original)!!
        assertEquals("black_cat", forEdit["id"])
        assertEquals("Cat", forEdit["displayname"])
        assertEquals("mxc://x/cat", forEdit["avatar_url"])
        assertEquals("kept", forEdit["extra"])
        assertFalse(forEdit.containsKey("has_fallback"))
    }

    @Test
    fun `no profile means nothing to carry into an edit`() {
        assertNull(perMessageProfileForEdit(null))
    }

    @Test
    fun `fallback prefix is stripped from an edit prefill`() {
        val cat = profile("black_cat", "Given PMP")
        assertEquals("original text", stripPerMessageProfileFallback("Given PMP: original text", cat))
    }

    @Test
    fun `body without the prefix is left alone`() {
        val cat = profile("black_cat", "Given PMP")
        assertEquals("original text", stripPerMessageProfileFallback("original text", cat))
        assertEquals("Given PMP: text", stripPerMessageProfileFallback("Given PMP: text", null))
        // Only a leading prefix is a fallback; the name elsewhere is real text.
        assertEquals("hi Given PMP: there", stripPerMessageProfileFallback("hi Given PMP: there", cat))
    }

    @Test
    fun `nothing is ours without a user id`() {
        assertFalse(isOwnMessage(me, profile(me), null))
        assertFalse(isOwnMessage(me, null, ""))
    }
}
