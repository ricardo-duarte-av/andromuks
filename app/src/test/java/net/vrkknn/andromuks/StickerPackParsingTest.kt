package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.StickerPackParsing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The usage rules that decide whether an image pack shows up in the sticker picker, the emoji
 * picker, or both.
 *
 * Two call paths share this parser — the response for a subscribed pack, and the in-room scan for
 * unsubscribed ones (GH #34) — so a rule that drifts would make the same pack look different
 * depending on how it was reached. The assertions are on parsed *values*, which is only meaningful
 * because a real org.json is on the unit-test classpath; see the Testing section of CLAUDE.md.
 */
class StickerPackParsingTest {

    private val room = "!kQAHmgK77VerUsse:example.org"

    private fun pack(images: String, packBlock: String = """"pack": { "display_name": "Yeah!" },""") = JSONObject(
        """
        {
          $packBlock
          "images": { $images }
        }
        """.trimIndent(),
    )

    private fun image(name: String, usage: String? = null): String {
        val usageJson = if (usage == null) "" else ""","usage": $usage"""
        return """
            "$name": {
              "url": "mxc://example.org/$name",
              "info": { "w": 128, "h": 106, "mimetype": "image/png", "size": 11105 }
              $usageJson
            }
        """.trimIndent()
    }

    @Test
    fun `an image with no usage is both a sticker and an emoji`() {
        val parsed = StickerPackParsing.parsePackContent(room, "yeah", pack(image("yeah")))

        assertEquals(listOf("yeah"), parsed.stickerPack?.stickers?.map { it.name })
        assertEquals(listOf("yeah"), parsed.emojiPack?.emojis?.map { it.name })
    }

    @Test
    fun `an empty usage array also means both`() {
        val parsed = StickerPackParsing.parsePackContent(room, "yeah", pack(image("yeah", usage = "[]")))

        assertNotNull(parsed.stickerPack)
        assertNotNull(parsed.emojiPack)
    }

    @Test
    fun `a sticker-only pack produces no emoji pack`() {
        val parsed = StickerPackParsing.parsePackContent(
            room,
            "yeah",
            pack(image("yeah", usage = """["sticker"]""")),
        )

        assertEquals(listOf("yeah"), parsed.stickerPack?.stickers?.map { it.name })
        assertNull(parsed.emojiPack)
    }

    @Test
    fun `an emoticon-only pack produces no sticker pack`() {
        val parsed = StickerPackParsing.parsePackContent(
            room,
            "frowncat",
            pack(image("frowncat", usage = """["emoticon"]""")),
        )

        assertEquals(listOf("frowncat"), parsed.emojiPack?.emojis?.map { it.name })
        assertNull(parsed.stickerPack)
    }

    @Test
    fun `an image declaring both usages lands in both packs`() {
        val parsed = StickerPackParsing.parsePackContent(
            room,
            "yeah",
            pack(image("yeah", usage = """["sticker", "emoticon"]""")),
        )

        assertEquals(1, parsed.stickerPack?.stickers?.size)
        assertEquals(1, parsed.emojiPack?.emojis?.size)
    }

    @Test
    fun `mixed usages split the pack across both pickers`() {
        val parsed = StickerPackParsing.parsePackContent(
            room,
            "mixed",
            pack(
                image("only_sticker", usage = """["sticker"]""") + "," +
                    image("only_emoji", usage = """["emoticon"]""") + "," +
                    image("both"),
            ),
        )

        // Set comparison: JSON object key order is not part of the contract.
        assertEquals(setOf("only_sticker", "both"), parsed.stickerPack?.stickers?.map { it.name }?.toSet())
        assertEquals(setOf("only_emoji", "both"), parsed.emojiPack?.emojis?.map { it.name }?.toSet())
    }

    @Test
    fun `the pack display name is used, falling back to the state key`() {
        val named = StickerPackParsing.parsePackContent(room, "yeah", pack(image("yeah")))
        assertEquals("Yeah!", named.stickerPack?.displayName)

        val unnamed = StickerPackParsing.parsePackContent(room, "yeah", pack(image("yeah"), packBlock = ""))
        assertEquals("yeah", unnamed.stickerPack?.displayName)

        val blank = StickerPackParsing.parsePackContent(
            room,
            "yeah",
            pack(image("yeah"), packBlock = """"pack": { "display_name": "" },"""),
        )
        assertEquals("yeah", blank.stickerPack?.displayName)
    }

    @Test
    fun `an image without an mxc url is skipped without failing the pack`() {
        val content = JSONObject(
            """
            {
              "pack": { "display_name": "Yeah!" },
              "images": {
                "broken": { "url": "https://example.org/not-mxc.png" },
                "ok": { "url": "mxc://example.org/ok" }
              }
            }
            """.trimIndent(),
        )
        val parsed = StickerPackParsing.parsePackContent(room, "yeah", content)

        assertEquals(listOf("ok"), parsed.stickerPack?.stickers?.map { it.name })
    }

    @Test
    fun `content with no images yields nothing at all`() {
        assertNull(StickerPackParsing.parsePackContent(room, "yeah", null).stickerPack)
        assertNull(StickerPackParsing.parsePackContent(room, "yeah", JSONObject()).emojiPack)
        val empty = StickerPackParsing.parsePackContent(room, "yeah", JSONObject("""{"images": {}}"""))
        assertNull(empty.stickerPack)
        assertNull(empty.emojiPack)
    }

    @Test
    fun `parsed stickers carry the room, url and dimensions the timeline needs`() {
        val parsed = StickerPackParsing.parsePackContent(room, "yeah", pack(image("yeah")))
        val sticker = parsed.stickerPack?.stickers?.single()

        assertEquals(room, parsed.stickerPack?.roomId)
        assertEquals("yeah", parsed.stickerPack?.packName)
        assertEquals("mxc://example.org/yeah", sticker?.mxcUrl)
        assertEquals("yeah", sticker?.body)
        assertEquals(128, sticker?.info?.getInt("w"))
    }
}
