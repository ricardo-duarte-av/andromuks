package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.base64Encode
import net.vrkknn.andromuks.utils.botCommandStateKey
import net.vrkknn.andromuks.utils.stateKeyMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSC4391 command-description state keys.
 *
 * The base64 vectors matter more than they look: `base64Encode` is hand-rolled because
 * `android.util.Base64` returns null under `isReturnDefaultValues` and `java.util.Base64` is above
 * this app's minSdk. Pinning it to the RFC 4648 §10 test vectors is what proves the encoder is real
 * rather than silently producing nothing — the same reasoning as `ReactionEventParsingTest`
 * asserting on parsed values.
 *
 * The state-key vectors were computed independently of this implementation, with
 * `printf '<command><mxid>' | openssl dgst -sha256 -binary | base64`.
 */
class BotCommandStateKeyTest {

    @Test
    fun `base64Encode matches RFC 4648 test vectors`() {
        assertEquals("", base64Encode("".toByteArray()))
        assertEquals("Zg==", base64Encode("f".toByteArray()))
        assertEquals("Zm8=", base64Encode("fo".toByteArray()))
        assertEquals("Zm9v", base64Encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", base64Encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", base64Encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", base64Encode("foobar".toByteArray()))
    }

    @Test
    fun `base64Encode covers the whole alphabet including the plus and slash characters`() {
        // 0xFB 0xFF 0xBF exercises the two alphabet entries a URL-safe encoder would get wrong.
        assertEquals("+/+/", base64Encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())))
        assertEquals("AAAA", base64Encode(byteArrayOf(0, 0, 0)))
        assertEquals("////", base64Encode(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `botCommandStateKey hashes command concatenated with sender`() {
        assertEquals(
            "DMHYfszXiVASgljWVjq0R4QQmS3HsqRiAnKRh9e14dY=",
            botCommandStateKey("ban", "@bot:example.org"),
        )
    }

    @Test
    fun `botCommandStateKey handles space separated nested commands`() {
        assertEquals(
            "ymxaSB2vHUxexYH9ta+pImteKb4Xs2O8mgCDrbljeAk=",
            botCommandStateKey("rooms add", "@draupnir:draupnir.space"),
        )
    }

    @Test
    fun `state key is always padded to a multiple of four characters`() {
        // A SHA-256 digest is 32 bytes, which is not a multiple of 3, so the encoding always pads.
        val key = botCommandStateKey("ban", "@bot:example.org")
        assertEquals(44, key.length)
        assertTrue(key.endsWith("="))
    }

    @Test
    fun `stateKeyMatches accepts the derived key and rejects everything else`() {
        val key = botCommandStateKey("ban", "@bot:example.org")
        assertTrue(stateKeyMatches(key, "ban", "@bot:example.org"))

        // A different bot advertising the same command gets a different key — this is the property
        // that stops one bot from overwriting another's description.
        assertFalse(stateKeyMatches(key, "ban", "@other:example.org"))
        // A different command from the same bot likewise.
        assertFalse(stateKeyMatches(key, "kick", "@bot:example.org"))
        // A squatted or hand-written key.
        assertFalse(stateKeyMatches("ban", "ban", "@bot:example.org"))
        assertFalse(stateKeyMatches("", "ban", "@bot:example.org"))
    }
}
