package net.vrkknn.andromuks.utils

/**
 * MSC4391 bot commands — rendering bound arguments back to a command line.
 *
 * A port of mautrix-go's `event/cmdschema/stringify.go`. This produces the `body` of the outgoing
 * message: MSC4391 treats the body as non-authoritative (the JSON envelope is the source of truth,
 * and the body may be omitted entirely), but bots that have not adopted the MSC still read it, and
 * every other client renders it. Generating it with the exact inverse of [parseQuoted] means the
 * fallback round-trips: a bot re-parsing the body gets the arguments we sent.
 *
 * No Android or Compose dependencies — see `BotCommandStringifyTest`.
 */

private const val ARRAY_OPENER = "<"
private const val ARRAY_CLOSER = ">"

/**
 * Quotes a value only when it would otherwise re-parse as several arguments, or as none.
 *
 * Backslashes are escaped before quotes so the escaping is single-pass and idempotent, matching
 * Go's `strings.Replacer`. An empty string must be quoted or it would vanish entirely.
 */
internal fun quoteCommandArg(value: String): String {
    if (value.isEmpty()) return "\"\""
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    val needsQuotes = escaped.any { it == ' ' || it == '\\' || it == '<' || it == '>' }
    return if (needsQuotes) "\"$escaped\"" else escaped
}

/**
 * The `matrix:` URI form of a room or event reference, which is what the text syntax uses.
 *
 * `via` servers are preserved because they are the only routing information a bot has for a room it
 * is not already in.
 */
internal fun ArgValue.RoomRef.toMatrixUri(): String = buildString {
    append("matrix:roomid/")
    append(id.removePrefix("!"))
    if (eventId != null) {
        append("/e/")
        append(eventId.removePrefix("$"))
    }
    if (via.isNotEmpty()) {
        append("?")
        append(via.joinToString("&") { "via=$it" })
    }
}

private fun singleArgumentToString(value: ArgValue): String = when (value) {
    is ArgValue.Str -> quoteCommandArg(value.value)

    is ArgValue.Num -> value.value.toString()

    is ArgValue.Bool -> value.value.toString()

    is ArgValue.RoomRef -> quoteCommandArg(value.toMatrixUri())

    // Nested arrays are rejected at parse time, so this is unreachable; render nothing rather than
    // emitting something that would not re-parse.
    is ArgValue.Arr -> ""
}

/**
 * Renders an array argument.
 *
 * An array in last position may be written bare, because there is nothing after it to confuse; one
 * anywhere else needs the `<`…`>` delimiters or a following parameter would swallow its tail.
 */
private fun arrayArgumentToString(value: ArgValue.Arr, isLast: Boolean): String {
    val parts = value.items.map { singleArgumentToString(it) }.filter { it.isNotEmpty() }
    val joined = parts.joinToString(" ")
    return if (isLast && parts.isNotEmpty()) joined else ARRAY_OPENER + joined + ARRAY_CLOSER
}

/**
 * Renders [arguments] as the argument portion of a command line, in parameter declaration order.
 *
 * A parameter with no supplied value falls back to its declared default and then to its schema
 * default; an optional parameter with neither is skipped entirely.
 */
fun BotCommand.stringifyArgs(arguments: Map<String, ArgValue>): String = parameters
    .mapIndexedNotNull { index, param ->
        val value = arguments[param.key] ?: param.effectiveDefault() ?: return@mapIndexedNotNull null
        val rendered = if (value is ArgValue.Arr) {
            arrayArgumentToString(value, isLast = index == parameters.size - 1)
        } else {
            singleArgumentToString(value)
        }
        rendered.takeIf { it.isNotEmpty() }
    }
    .joinToString(" ")

/**
 * The full `body` fallback for an invocation: the sigil, the command, and its arguments.
 *
 * This is what appears in the timeline for clients that do not understand the command envelope.
 */
fun BotCommand.commandFallbackBody(arguments: Map<String, ArgValue>): String {
    val args = stringifyArgs(arguments)
    return if (args.isEmpty()) "/$command" else "/$command $args"
}
