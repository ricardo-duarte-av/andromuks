package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-data transform behind sticker pack subscribe/unsubscribe.
 *
 * This is the whole write side of GH #34: subscribing edits the `rooms` map of
 * `im.ponies.emote_rooms` (or the MSC2545 `m.image_pack.rooms`), and everything downstream —
 * which packs get fetched, which appear in the pickers — follows from what this produces. The
 * transform is pure so it can be pinned here rather than through a WebSocket round trip.
 */
class StickerPackSubscriptionTest {

    private val nexRoom = "!OsgPt00PZet9BvF2v0:nexy7574.co.uk"
    private val stickerRoom = "!stickers-v11:maunium.net"

    private fun content(json: String) = JSONObject(json)

    private val twoPacks = content(
        """
        {
          "rooms": {
            "$nexRoom": { "nex-pack": {} },
            "$stickerRoom": { "frowncat": {} }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `parses every pack across rooms`() {
        val refs = StickerPackCoordinator.parseSubscriptions(twoPacks)
        assertEquals(2, refs.size)
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(nexRoom, "nex-pack")))
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(stickerRoom, "frowncat")))
    }

    @Test
    fun `absent or malformed content yields no subscriptions`() {
        assertTrue(StickerPackCoordinator.parseSubscriptions(null).isEmpty())
        assertTrue(StickerPackCoordinator.parseSubscriptions(JSONObject()).isEmpty())
        assertTrue(
            StickerPackCoordinator.parseSubscriptions(content("""{"rooms": {}}""")).isEmpty(),
        )
    }

    @Test
    fun `subscribing adds the pack under its room and leaves the others alone`() {
        val updated = StickerPackCoordinator.withSubscription(twoPacks, stickerRoom, "yeah", subscribed = true)
        val refs = StickerPackCoordinator.parseSubscriptions(updated)

        assertEquals(3, refs.size)
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(stickerRoom, "yeah")))
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(stickerRoom, "frowncat")))
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(nexRoom, "nex-pack")))
    }

    @Test
    fun `subscribing twice does not duplicate the entry`() {
        val once = StickerPackCoordinator.withSubscription(twoPacks, stickerRoom, "yeah", subscribed = true)
        val twice = StickerPackCoordinator.withSubscription(once, stickerRoom, "yeah", subscribed = true)
        assertEquals(3, StickerPackCoordinator.parseSubscriptions(twice).size)
    }

    @Test
    fun `subscribing into a room with no packs yet creates the room key`() {
        val fresh = StickerPackCoordinator.withSubscription(null, stickerRoom, "yeah", subscribed = true)
        assertEquals(
            listOf(StickerPackCoordinator.PackRef(stickerRoom, "yeah")),
            StickerPackCoordinator.parseSubscriptions(fresh),
        )
    }

    @Test
    fun `unsubscribing the last pack in a room drops the room key entirely`() {
        val updated = StickerPackCoordinator.withSubscription(twoPacks, stickerRoom, "frowncat", subscribed = false)

        assertEquals(
            listOf(StickerPackCoordinator.PackRef(nexRoom, "nex-pack")),
            StickerPackCoordinator.parseSubscriptions(updated),
        )
        // An empty room object would leave the loader with a pack source that yields nothing.
        assertFalse(updated.getJSONObject("rooms").has(stickerRoom))
    }

    @Test
    fun `unsubscribing one of several packs keeps the room and its siblings`() {
        val threePacks = StickerPackCoordinator.withSubscription(twoPacks, stickerRoom, "yeah", subscribed = true)
        val updated = StickerPackCoordinator.withSubscription(threePacks, stickerRoom, "frowncat", subscribed = false)
        val refs = StickerPackCoordinator.parseSubscriptions(updated)

        assertEquals(2, refs.size)
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(stickerRoom, "yeah")))
        assertTrue(refs.contains(StickerPackCoordinator.PackRef(nexRoom, "nex-pack")))
    }

    @Test
    fun `unsubscribing something not subscribed changes nothing`() {
        val updated = StickerPackCoordinator.withSubscription(twoPacks, stickerRoom, "absent", subscribed = false)
        assertEquals(2, StickerPackCoordinator.parseSubscriptions(updated).size)
    }

    @Test
    fun `per-pack overrides survive an unrelated edit`() {
        val withOverride = content(
            """
            {
              "rooms": {
                "$nexRoom": { "nex-pack": { "usage": ["emoticon"] } }
              }
            }
            """.trimIndent(),
        )
        val updated = StickerPackCoordinator.withSubscription(withOverride, stickerRoom, "yeah", subscribed = true)

        val preserved = updated.getJSONObject("rooms").getJSONObject(nexRoom).getJSONObject("nex-pack")
        assertEquals("emoticon", preserved.getJSONArray("usage").getString(0))
    }

    @Test
    fun `the official key wins over the legacy one, with its own state event type`() {
        val official = StickerPackCoordinator.resolveKeys(hasOfficial = true)
        assertEquals(StickerPackCoordinator.OFFICIAL_KEY, official.accountDataKey)
        assertEquals(StickerPackCoordinator.OFFICIAL_STATE_TYPE, official.stateEventType)

        val legacy = StickerPackCoordinator.resolveKeys(hasOfficial = false)
        assertEquals(StickerPackCoordinator.LEGACY_KEY, legacy.accountDataKey)
        assertEquals(StickerPackCoordinator.LEGACY_STATE_TYPE, legacy.stateEventType)
    }
}
