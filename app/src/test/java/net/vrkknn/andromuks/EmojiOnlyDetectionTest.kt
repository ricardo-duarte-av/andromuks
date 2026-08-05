package net.vrkknn.andromuks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "jumbo emoji" predicate.
 *
 * The original implementation classified negatively — anything that was a single grapheme cluster
 * and contained no ASCII letter or digit was rendered sticker-sized — so "?", "€" and "漢" were all
 * enlarged, while keycap emoji like "1️⃣" were not. These tests pin the positive rule.
 */
class EmojiOnlyDetectionTest {

    @Test
    fun `punctuation and symbols are not emoji`() {
        listOf("?", "!", "€", "…", "~", ".", ",", "£", "§", "±", "→", "←", "©", "™").forEach {
            assertFalse("\"$it\" must not be treated as emoji-only", isEmojiOnlyMessage(it))
        }
    }

    @Test
    fun `non-latin text is not emoji`() {
        listOf("漢", "ß", "a", "Я", "あ", "ñ").forEach {
            assertFalse("\"$it\" must not be treated as emoji-only", isEmojiOnlyMessage(it))
        }
    }

    @Test
    fun `blank bodies are not emoji`() {
        assertFalse(isEmojiOnlyMessage(""))
        assertFalse(isEmojiOnlyMessage("   "))
        assertFalse(isEmojiOnlyMessage("\n\t"))
    }

    @Test
    fun `single emoji is emoji-only`() {
        listOf("😀", "❤️", "🎉", "✅", "☺️").forEach {
            assertTrue("\"$it\" must be treated as emoji-only", isEmojiOnlyMessage(it))
        }
    }

    @Test
    fun `composed emoji are emoji-only`() {
        assertTrue("skin tone", isEmojiOnlyMessage("👍🏽"))
        assertTrue("ZWJ family", isEmojiOnlyMessage("👨‍👩‍👧‍👦"))
        assertTrue("flag", isEmojiOnlyMessage("🇵🇹"))
    }

    @Test
    fun `text-presentation symbols need a variation selector to count as emoji`() {
        // U+00A9 alone is text; U+00A9 U+FE0F is the copyright emoji. Same for U+2764 / heart.
        assertFalse(isEmojiOnlyMessage("©"))
        assertTrue(isEmojiOnlyMessage("©️"))
        assertFalse(isEmojiOnlyMessage("❤"))
        assertTrue(isEmojiOnlyMessage("❤️"))
        // VS15 explicitly requests text presentation.
        assertFalse(isEmojiOnlyMessage("❤︎"))
    }

    @Test
    fun `keycap emoji is emoji-only despite containing an ascii digit`() {
        assertTrue(isEmojiOnlyMessage("1️⃣"))
    }

    @Test
    fun `up to three emoji are emoji-only`() {
        assertTrue(isEmojiOnlyMessage("😀😁"))
        assertTrue(isEmojiOnlyMessage("😀😁😂"))
        assertTrue("whitespace separated", isEmojiOnlyMessage("😀 😁"))
    }

    @Test
    fun `more than three emoji is not emoji-only`() {
        assertFalse(isEmojiOnlyMessage("😀😁😂😃"))
        assertFalse(isEmojiOnlyMessage("😀😁😂😃😄😅"))
    }

    @Test
    fun `emoji mixed with text is not emoji-only`() {
        assertFalse(isEmojiOnlyMessage("😀 hello"))
        assertFalse(isEmojiOnlyMessage("hello 😀"))
        assertFalse(isEmojiOnlyMessage("😀?"))
    }

    @Test
    fun `custom emoji shortcodes and markdown are emoji-only`() {
        assertTrue(isEmojiOnlyMessage(":shrug:"))
        assertTrue(isEmojiOnlyMessage("![:party:](mxc://example.org/abc123)"))
    }

    @Test
    fun `html tags are stripped before classification`() {
        assertTrue(isEmojiOnlyMessage("<p>😀</p>"))
        assertFalse(isEmojiOnlyMessage("<p>?</p>"))
    }

    @Test
    fun `custom emoji images are emoji-only up to three`() {
        val img = """<img src="mxc://example.org/abc" alt=":party:">"""
        assertTrue(isCustomEmojiOnlyHtml(img))
        assertTrue(isCustomEmojiOnlyHtml(img + img))
        assertTrue(isCustomEmojiOnlyHtml(img + img + img))
        assertFalse(isCustomEmojiOnlyHtml(img + img + img + img))
    }

    @Test
    fun `non-mxc images are not custom emoji`() {
        assertFalse(isCustomEmojiOnlyHtml("""<img src="https://example.org/cat.png">"""))
        assertFalse(
            "one mxc emoji plus a regular image is not emoji-only",
            isCustomEmojiOnlyHtml(
                """<img src="mxc://example.org/abc"><img src="https://example.org/cat.png">""",
            ),
        )
        assertFalse(isCustomEmojiOnlyHtml(null))
        assertFalse(isCustomEmojiOnlyHtml(""))
        assertFalse(isCustomEmojiOnlyHtml("<p>hello</p>"))
    }
}
