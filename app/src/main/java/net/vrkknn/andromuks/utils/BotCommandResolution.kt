package net.vrkknn.andromuks.utils

/**
 * MSC4391 bot commands — which of a room's advertised commands the composer may actually offer.
 *
 * Two filters, both required by the MSC and both applied at read time rather than at ingest so that
 * membership changes and built-in additions take effect immediately:
 *
 *  - **Built-in commands win.** A bot may advertise `myroomnick` or `ban` and thereby break this
 *    client's own commands, so anything colliding with a built-in name or alias is dropped. The
 *    MSC calls this out as a security consideration.
 *  - **The advertising bot must be in the room.** A description left behind by a departed bot is
 *    dead weight; invoking it would mention a user who cannot answer.
 *
 * The escape hatch for the first filter is [BotCommand.parsePrefix]'s qualified form,
 * `/ban@bot:example.org` — that still reaches a shadowed bot command, which matters because this
 * client's built-ins (`/ban`, `/kick`, `/invite`, `/redact`, `/join`, `/alias`) are exactly a
 * moderation bot's vocabulary.
 *
 * No Android or Compose dependencies — see `BotCommandPrecedenceTest`.
 */

/** Every name a built-in command answers to, lowercased and without the leading sigil. */
private fun CommandDefinition.reservedNames(): List<String> =
    (listOf(command) + aliases).map { it.removePrefix("/").lowercase() }

/**
 * Filters and orders a room's raw command descriptions for display.
 *
 * [isJoined] must **fail open**: `RoomMemberCache` holds joined members only and is populated
 * lazily, so "not in the cache" is not proof of "not in the room". Hiding every bot command until
 * the member list loads would make the feature look broken on a cold start. Callers pass a
 * predicate that returns true when membership is simply unknown.
 */
fun resolveBotCommands(
    raw: List<BotCommand>,
    builtIns: List<CommandDefinition> = Commands.allCommands,
    isJoined: (String) -> Boolean,
): List<BotCommand> {
    val reserved = builtIns.flatMapTo(mutableSetOf()) { it.reservedNames() }
    return raw
        .filter { command ->
            // Only the first word can collide: a built-in is always a single word.
            val firstWord = command.words.firstOrNull()?.lowercase()
            firstWord != null && firstWord !in reserved && isJoined(command.sender)
        }
        // Deterministic ordering: two bots may advertise the same command name, and the list must
        // not reshuffle between recompositions.
        .sortedWith(compareBy({ it.command }, { it.sender }))
}

/**
 * Prefix-matches [query] (the text typed after `/`, without the sigil) against resolved commands.
 *
 * Matches the command and its aliases, mirroring [Commands.getSuggestions] so built-in and bot rows
 * in the same list behave identically. A blank query lists everything.
 */
fun botCommandSuggestions(resolved: List<BotCommand>, query: String): List<BotCommand> {
    val lowerQuery = query.lowercase().trim()
    if (lowerQuery.isEmpty()) return resolved
    return resolved.filter { command ->
        command.allNames.any { it.lowercase().startsWith(lowerQuery) }
    }
}

/**
 * Word sequences of every multi-word command, for the composer's `/` detection.
 *
 * Command detection normally stops at the first space; a command like `rooms add` needs it to keep
 * going while the words typed so far are still a prefix of something real.
 */
fun multiWordPrefixesOf(resolved: List<BotCommand>): Set<List<String>> =
    resolved.map { it.words }.filterTo(mutableSetOf()) { it.size > 1 }
