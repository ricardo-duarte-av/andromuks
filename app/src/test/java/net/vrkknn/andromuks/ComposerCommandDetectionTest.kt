package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.detectCommandQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Composer `/command` detection.
 *
 * The single-word cases pin the behaviour of the three `detectCommand` copies this replaced, so the
 * refactor is provably behaviour-preserving for built-in commands. The multi-word cases are the new
 * part, needed because MSC4391 commands nest with spaces.
 */
class ComposerCommandDetectionTest {

    private val nested = setOf(listOf("rooms", "add"), listOf("rooms", "remove"))

    @Test
    fun `a query is live while the cursor is inside the command word`() {
        assertEquals("" to 0, detectCommandQuery("/", 1))
        assertEquals("b" to 0, detectCommandQuery("/ban", 2))
        assertEquals("ban" to 0, detectCommandQuery("/ban", 4))
    }

    @Test
    fun `no query before the slash or when the input does not start with one`() {
        assertNull(detectCommandQuery("/ban", 0))
        assertNull(detectCommandQuery("hello /ban", 10))
        assertNull(detectCommandQuery("", 0))
        assertNull(detectCommandQuery("/ban", 99))
    }

    @Test
    fun `the query ends as soon as arguments begin`() {
        // This is what hides the suggestion list once the user starts typing arguments.
        assertNull(detectCommandQuery("/ban ", 5))
        assertNull(detectCommandQuery("/ban alice", 10))
    }

    @Test
    fun `a newline before the cursor ends the query`() {
        assertNull(detectCommandQuery("/ban\nmore", 9))
    }

    @Test
    fun `a multi-word command keeps the query alive across the space`() {
        assertEquals("rooms" to 0, detectCommandQuery("/rooms", 6, nested))
        assertEquals("rooms" to 0, detectCommandQuery("/rooms ", 7, nested))
        assertEquals("rooms a" to 0, detectCommandQuery("/rooms a", 8, nested))
        assertEquals("rooms add" to 0, detectCommandQuery("/rooms add", 10, nested))
    }

    @Test
    fun `the query stops growing once the full multi-word command is typed`() {
        // "rooms add" is complete, so everything after it is arguments.
        assertNull(detectCommandQuery("/rooms add ", 11, nested))
        assertNull(detectCommandQuery("/rooms add !r:x", 15, nested))
    }

    @Test
    fun `an unknown first word does not span the space`() {
        assertNull(detectCommandQuery("/ban alice", 10, nested))
    }

    @Test
    fun `with no multi-word commands the behaviour is unchanged`() {
        assertEquals("rooms" to 0, detectCommandQuery("/rooms", 6))
        assertNull(detectCommandQuery("/rooms ", 7))
    }
}
