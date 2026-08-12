package net.vrkknn.andromuks.utils

/**
 * Composer `/command` detection, shared by RoomTimelineScreen, BubbleTimelineScreen and
 * ThreadViewerScreen.
 *
 * This replaces three byte-identical `detectCommand` copies. Its behaviour for single-word commands
 * is exactly theirs: the `/` must be the first character of the input, and the query is live only
 * while the cursor is still inside the unbroken run of non-space characters that follows it — so the
 * suggestion list disappears the moment the user starts typing arguments.
 *
 * The one addition is [multiWordPrefixes]. MSC4391 commands nest with spaces (`rooms add`), so the
 * query is allowed to span a space while the words typed so far are a strict prefix of a known
 * multi-word command, and stops growing as soon as they are not. With an empty set the function is
 * behaviourally identical to the code it replaces.
 */

/**
 * The active command query and the index of its `/`, or null when the cursor is not in one.
 *
 * The returned query never includes the leading `/`. Callers pass it to [Commands.getSuggestions]
 * and [botCommandSuggestions], which both prefix-match on it.
 */
fun detectCommandQuery(
    text: String,
    cursorPosition: Int,
    multiWordPrefixes: Set<List<String>> = emptySet(),
): Pair<String, Int>? {
    if (cursorPosition < 1 || cursorPosition > text.length) return null
    if (!text.startsWith("/")) return null

    val upToCursor = text.substring(1, cursorPosition)
    if (upToCursor.contains('\n')) return null

    val words = upToCursor.split(' ')

    // How many words still belong to the command name. Starts at one and grows only while the
    // committed words are a *strict* prefix of some multi-word command — once they name a complete
    // command, everything after it is arguments.
    var wordCount = 1
    while (wordCount < words.size) {
        val committed = words.subList(0, wordCount)
        val extendable = multiWordPrefixes.any { it.size > committed.size && it.subList(0, committed.size) == committed }
        if (!extendable) break
        wordCount++
    }

    // The cursor has moved past the command name into the arguments; there is nothing to complete.
    if (words.size > wordCount) return null

    return words.subList(0, wordCount).joinToString(" ").trim() to 0
}
