package net.vrkknn.andromuks.utils

import java.text.BreakIterator

/**
 * Positive classification of Unicode emoji, used to decide whether a message should be rendered
 * "jumbo" (sticker-sized).
 *
 * The primary check is exact membership in the emoji table the picker already ships
 * ([EmojiData.getAllEmojis]), normalised so skin-tone and variation-selector spellings collapse onto
 * the table's base entry. A code-point range fallback covers emoji newer than the generated table.
 *
 * Deliberately pure (no Android dependencies) so it is unit-testable on the JVM.
 */
object EmojiClassifier {

    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private val SkinToneRange = 0x1F3FB..0x1F3FF
    private val TagRange = 0xE0020..0xE007F

    /**
     * Code point blocks that are emoji-presentation by default. Bare BMP symbols (arrows, ©, ™, ⌚)
     * are intentionally absent: they are text-presentation by default, and the ones that really are
     * emoji already match via [emojiSet].
     */
    private val EmojiBlocks = listOf(
        0x1F000..0x1F0FF, // Mahjong, dominoes, playing cards
        0x1F1E6..0x1F1FF, // Regional indicators (flags)
        0x1F300..0x1FAFF, // Pictographs, emoticons, transport, supplemental, extended-A
    )

    /**
     * Lazily built so the picker's generated tables are not touched until a message needs them.
     *
     * Entries keep their variation selectors: the table stores "©️" and "❤️" (with VS16) precisely
     * because bare "©" and "❤" are *text* presentation and must not be enlarged.
     */
    private val emojiSet: Set<String> by lazy {
        EmojiData.getAllEmojis().map(::stripSkinTones).toSet()
    }

    /**
     * Drops skin-tone modifiers, which the generated table does not enumerate, so that e.g. "👍🏽"
     * resolves to its base table entry.
     */
    private fun stripSkinTones(text: String): String = filterCodePoints(text) { it !in SkinToneRange }

    private fun stripVariationSelectors(text: String): String = filterCodePoints(text) { it != VS15 && it != VS16 }

    private inline fun filterCodePoints(text: String, keep: (Int) -> Boolean): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (keep(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isEmojiBlockCodePoint(cp: Int): Boolean = EmojiBlocks.any { cp in it }

    private fun isGlueCodePoint(cp: Int): Boolean = cp == ZWJ ||
        cp == VS15 ||
        cp == VS16 ||
        cp == COMBINING_ENCLOSING_KEYCAP ||
        cp in SkinToneRange ||
        cp in TagRange

    /** True when [cluster] (a single grapheme cluster) is a user-visible emoji. */
    fun isEmojiCluster(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        // VS15 explicitly requests text presentation — never enlarge it.
        if (cluster.any { it.code == VS15 }) return false

        val candidate = stripSkinTones(cluster)
        if (candidate in emojiSet) return true
        // A candidate carrying VS16 has explicitly asked for emoji presentation, so it may also
        // match a table entry spelled without one. The reverse is deliberately not allowed: bare
        // "©" or "❤" must not match the table's "©️"/"❤️".
        if (cluster.any { it.code == VS16 } && stripVariationSelectors(candidate) in emojiSet) return true

        // Fallback for emoji newer than the generated table: every code point must be either an
        // emoji-block code point or glue, and at least one must come from an emoji block.
        var sawEmojiBlock = false
        var i = 0
        while (i < cluster.length) {
            val cp = cluster.codePointAt(i)
            when {
                isEmojiBlockCodePoint(cp) -> sawEmojiBlock = true
                isGlueCodePoint(cp) -> Unit
                else -> return false
            }
            i += Character.charCount(cp)
        }
        return sawEmojiBlock
    }

    /**
     * Counts the grapheme clusters in [text], requiring every one of them to be an emoji.
     *
     * @return the cluster count when all clusters are emoji and the count is within [limit],
     *   otherwise -1.
     */
    fun countEmojiClusters(text: String, limit: Int): Int {
        if (text.isEmpty()) return -1

        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)

        var count = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (!isEmojiCluster(text.substring(start, end))) return -1
            count++
            if (count > limit) return -1
            start = end
            end = iterator.next()
        }
        return if (count == 0) -1 else count
    }
}
