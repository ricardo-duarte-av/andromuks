package net.vrkknn.andromuks.utils

import androidx.compose.runtime.Immutable

/**
 * MSC4391 bot commands — turning a typed command line into typed arguments.
 *
 * A port of mautrix-go's `event/cmdschema/parse.go`. Matching it matters: the same text has to mean
 * the same thing to us, to gomuks and to any bot that parses the `body` fallback, so the quirks
 * below are reproduced deliberately rather than "improved":
 *
 *  - **Named arguments** `--key=value` are consumed greedily before each positional slot, and a
 *    bare `--flag` on a boolean-accepting parameter means `true`. Note that `--key value` does
 *    *not* bind the value: the reference implementation trims the `=` but leaves the space, so the
 *    value reads as empty and the next token falls through to the following parameter.
 *  - **Optional non-tail parameters are never bound positionally.** They exist only as `--key`. This
 *    contradicts a plain reading of the MSC ("the position of parameters that are not required is
 *    not significant") but it is what the reference implementation does, and it is the only way an
 *    unambiguous positional grammar is possible.
 *  - **The last parameter, or the one named by `fi.mau.tail_parameter`, swallows the rest of the
 *    line** when it is unquoted. This is what makes a trailing free-text `reason` work.
 *  - **Arrays** are delimited with `<`…`>`. An array in last position may omit the delimiters and
 *    consumes everything; an undelimited array anywhere else takes exactly one item.
 *  - An optional parameter that fails to parse **puts its input back** for the next parameter.
 *
 * On top of the Go behaviour we accept the looser inputs the MSC's TIP invites — `on`/`off` as
 * booleans, Markdown `[label](url)` wrappers (our own composer produces these for mentions), and a
 * display name that resolves to exactly one joined member. Canonical values always go on the wire;
 * see [ArgValue.toWireValue].
 *
 * No Android or Compose dependencies, so it is unit tested directly — `BotCommandParseTest` and
 * `BotCommandCoercionTest`.
 */

private const val ARRAY_OPENER = "<"
private const val ARRAY_CLOSER = ">"
private const val NAMED_PREFIX = "--"

/** A parsed argument value, in the canonical shape MSC4391 puts on the wire. */
@Immutable
sealed interface ArgValue {
    @Immutable
    data class Str(val value: String) : ArgValue

    @Immutable
    data class Num(val value: Long) : ArgValue

    @Immutable
    data class Bool(val value: Boolean) : ArgValue

    /**
     * A `room_id` or `event_id`. These are objects rather than strings because they carry routing
     * information: [via] lists servers the room can be joined through.
     */
    @Immutable
    data class RoomRef(val type: PrimitiveType, val id: String, val via: List<String> = emptyList(), val eventId: String? = null) : ArgValue

    @Immutable
    data class Arr(val items: List<ArgValue>) : ArgValue
}

/**
 * The canonical JSON value for the `arguments` object of a command envelope.
 *
 * Booleans and integers are real JSON booleans and numbers, never strings — the MSC is explicit that
 * clients may accept loose input but must send canonical types.
 */
fun ArgValue.toWireValue(): Any = when (this) {
    is ArgValue.Str -> value

    is ArgValue.Num -> value

    is ArgValue.Bool -> value

    is ArgValue.Arr -> items.map { it.toWireValue() }

    is ArgValue.RoomRef -> buildMap {
        put("type", type.wireName)
        put("id", id)
        if (via.isNotEmpty()) put("via", via)
        if (eventId != null) put("event_id", eventId)
    }
}

/** A human-readable rendering of a bound value, for the signature strip's chips. */
fun ArgValue.displayText(): String = when (this) {
    is ArgValue.Str -> value
    is ArgValue.Num -> value.toString()
    is ArgValue.Bool -> value.toString()
    is ArgValue.RoomRef -> eventId ?: id
    is ArgValue.Arr -> items.joinToString(", ") { it.displayText() }
}

/**
 * Client-side lookups the coercer may use to accept loose input.
 *
 * Injected rather than reached for directly so this whole file stays pure and testable; the composer
 * supplies implementations backed by `RoomMemberCache` and `RoomListCache`.
 */
class CoercionContext(
    /** A display name typed instead of an MXID, resolved only when it is unambiguous. */
    val resolveDisplayName: (String) -> String? = { null },
    /** A known room alias resolved to its room ID, so `#room:server` satisfies a `room_id`. */
    val resolveRoomAlias: (String) -> String? = { null },
) {
    companion object {
        /** No lookups: strict parsing only. Used by tests and by non-interactive callers. */
        val EMPTY = CoercionContext()
    }
}

/** Where in the argument string a parameter's value was found, for cursor tracking. */
@Immutable
data class ArgSpan(val key: String, val start: Int, val end: Int)

/**
 * The result of binding a typed command line against a command description.
 *
 * [arguments] is always populated for every parameter that was reached, using the schema default
 * when parsing failed, so the argument sheet can prefill from a half-typed line. [errors] and
 * [missingRequired] are what decide whether sending is allowed.
 */
@Immutable
data class ParsedInvocation(
    val command: BotCommand,
    val arguments: Map<String, ArgValue>,
    val errors: Map<String, String>,
    val missingRequired: List<String>,
    val spans: List<ArgSpan>,
    /** The parameter the cursor currently sits in, for highlighting. */
    val activeParamKey: String?,
) {
    val isComplete: Boolean get() = errors.isEmpty() && missingRequired.isEmpty()
}

/**
 * Splits one argument off the front of [input].
 *
 * Returns the parsed value, the remaining input with leading spaces trimmed, and whether the value
 * was quoted. Quoting uses `"` with `\` escapes; an unterminated quote takes the rest of the line.
 * `wasQuoted` is not cosmetic — it is what stops a quoted final argument from swallowing the rest of
 * the line, and what makes a bare `--flag` distinguishable from `--flag ""`.
 */
fun parseQuoted(input: String): Triple<String, String, Boolean> {
    if (input.isEmpty()) return Triple("", "", false)
    if (!input.startsWith("\"")) {
        val spaceIdx = input.indexOf(' ')
        return if (spaceIdx == -1) {
            Triple(input, "", false)
        } else {
            Triple(input.substring(0, spaceIdx), input.substring(spaceIdx + 1).trimStart(' '), false)
        }
    }

    var rest = input.substring(1)
    val buf = StringBuilder()
    // Driven by a flag rather than `break` so the loop keeps exactly one exit besides the early
    // return, which is what the reference implementation's control flow amounts to anyway.
    var closed = false
    while (!closed) {
        val quoteIdx = rest.indexOf('"')
        val untilQuote = if (quoteIdx == -1) rest else rest.substring(0, quoteIdx)
        val escapeIdx = untilQuote.indexOf('\\')
        if (escapeIdx >= 0) {
            buf.append(rest, 0, escapeIdx)
            if (rest.length > escapeIdx + 1) buf.append(rest[escapeIdx + 1])
            rest = rest.substring(minOf(escapeIdx + 2, rest.length))
        } else if (quoteIdx >= 0) {
            buf.append(rest, 0, quoteIdx)
            rest = rest.substring(quoteIdx + 1)
            closed = true
        } else if (buf.isEmpty()) {
            // Unterminated quote with no escapes: the whole remainder is the value.
            return Triple(rest, "", true)
        } else {
            buf.append(rest)
            rest = ""
            closed = true
        }
    }
    return Triple(buf.toString(), rest.trimStart(' '), true)
}

// region Primitive coercion

private val MARKDOWN_LINK_REGEX = Regex("""^\[.+]\(([^)]+)\)$""")
private val SERVER_NAME_REGEX = Regex("""^(\[[0-9A-Fa-f:.]+]|[0-9A-Za-z\-.]+)(:\d{1,5})?$""")

/** Unwraps `[label](target)`, which is what this app's own composer inserts for mentions. */
private fun unwrapMarkdownLink(value: String): String {
    if (!value.startsWith("[") || !value.endsWith(")") || !value.contains("](")) return value
    return MARKDOWN_LINK_REGEX.find(value)?.groupValues?.get(1) ?: value
}

/**
 * A Matrix identifier reference decoded from a permalink.
 *
 * Covers `https://matrix.to/#/…` and `matrix:…` in the shapes clients actually emit; anything else
 * simply fails to parse and the caller reports a type error.
 */
private data class MatrixUri(val first: String, val second: String?, val via: List<String>)

private fun parseMatrixUri(raw: String): MatrixUri? {
    val value = unwrapMarkdownLink(raw.trim())
    val body = when {
        value.startsWith("https://matrix.to/#/") -> value.removePrefix("https://matrix.to/#/")
        value.startsWith("http://matrix.to/#/") -> value.removePrefix("http://matrix.to/#/")
        value.startsWith("matrix:") -> return parseMatrixScheme(value.removePrefix("matrix:"))
        else -> return null
    }
    val queryIdx = body.indexOf('?')
    val path = if (queryIdx >= 0) body.substring(0, queryIdx) else body
    val via = if (queryIdx >= 0) parseViaQuery(body.substring(queryIdx + 1)) else emptyList()
    val parts = path.split('/').map { urlDecode(it) }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return MatrixUri(first = parts[0], second = parts.getOrNull(1), via = via)
}

/** `matrix:u/user:server`, `matrix:r/alias:server`, `matrix:roomid/room:server/e/event`. */
private fun parseMatrixScheme(raw: String): MatrixUri? {
    val queryIdx = raw.indexOf('?')
    val path = if (queryIdx >= 0) raw.substring(0, queryIdx) else raw
    val via = if (queryIdx >= 0) parseViaQuery(raw.substring(queryIdx + 1)) else emptyList()
    val parts = path.split('/').map { urlDecode(it) }.filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val first = when (parts[0]) {
        "u" -> "@${parts[1]}"
        "r" -> "#${parts[1]}"
        "roomid" -> "!${parts[1]}"
        else -> return null
    }
    val second = if (parts.size >= 4 && parts[2] == "e") "$${parts[3]}" else null
    return MatrixUri(first = first, second = second, via = via)
}

private fun parseViaQuery(query: String): List<String> = query.split('&')
    .mapNotNull { it.substringAfter("via=", "").takeIf { v -> v.isNotEmpty() } }
    .map { urlDecode(it) }

private fun urlDecode(value: String): String = try {
    java.net.URLDecoder.decode(value, "UTF-8")
} catch (_: IllegalArgumentException) {
    value
}

private fun isValidServerName(value: String): Boolean = value.isNotEmpty() && SERVER_NAME_REGEX.matches(value)

private fun isValidUserId(value: String): Boolean {
    if (!value.startsWith("@")) return false
    val colon = value.indexOf(':')
    return colon > 1 && isValidServerName(value.substring(colon + 1))
}

private fun isValidRoomAlias(value: String): Boolean {
    if (!value.startsWith("#")) return false
    val colon = value.indexOf(':')
    return colon > 1 && isValidServerName(value.substring(colon + 1))
}

/**
 * Booleans, accepting more spellings than the reference implementation.
 *
 * Go accepts `t/true/y/yes/1` and `f/false/n/no/0`; `on`/`off` are added here because the MSC
 * explicitly invites clients to accept loose input and we always send a real JSON boolean, so a
 * wider client-side vocabulary cannot desynchronise anything.
 */
private fun parseBooleanArg(value: String): Boolean? = when (value.lowercase()) {
    "t", "true", "y", "yes", "1", "on" -> true
    "f", "false", "n", "no", "0", "off" -> false
    else -> null
}

private fun parseRoomOrEventRef(value: String): ArgValue.RoomRef? {
    val uri = parseMatrixUri(value)
    if (uri == null) {
        val plain = unwrapMarkdownLink(value.trim())
        return if (plain.startsWith("!")) ArgValue.RoomRef(PrimitiveType.ROOM_ID, plain) else null
    }
    if (!uri.first.startsWith("!")) return null
    val eventId = uri.second?.takeIf { it.startsWith("$") }
    if (uri.second != null && eventId == null) return null
    return ArgValue.RoomRef(
        type = if (eventId != null) PrimitiveType.EVENT_ID else PrimitiveType.ROOM_ID,
        id = uri.first,
        via = uri.via,
        eventId = eventId,
    )
}

/**
 * Coerces one raw token to this primitive type.
 *
 * Returns null on failure; the caller turns that into a per-parameter error message. Client-side
 * leniency (permalinks, Markdown links, display names, aliases standing in for room IDs) is applied
 * here, but the returned value is always canonical.
 */
fun PrimitiveType.parseString(raw: String, ctx: CoercionContext = CoercionContext.EMPTY): ArgValue? {
    val value = raw.trim()
    return when (this) {
        PrimitiveType.STRING -> ArgValue.Str(raw)

        PrimitiveType.INTEGER -> value.toLongOrNull()?.let { ArgValue.Num(it) }

        PrimitiveType.BOOLEAN -> parseBooleanArg(value)?.let { ArgValue.Bool(it) }

        PrimitiveType.SERVER_NAME -> if (isValidServerName(value)) ArgValue.Str(value) else null

        PrimitiveType.USER_ID -> {
            val plain = unwrapMarkdownLink(value)
            val fromUri = parseMatrixUri(value)?.first
            when {
                isValidUserId(plain) -> ArgValue.Str(plain)

                fromUri != null && isValidUserId(fromUri) -> ArgValue.Str(fromUri)

                // Last resort: a display name that names exactly one joined member.
                else -> ctx.resolveDisplayName(value)?.let { ArgValue.Str(it) }
            }
        }

        PrimitiveType.ROOM_ALIAS -> {
            val plain = unwrapMarkdownLink(value)
            val fromUri = parseMatrixUri(value)?.first
            when {
                isValidRoomAlias(plain) -> ArgValue.Str(plain)
                fromUri != null && isValidRoomAlias(fromUri) -> ArgValue.Str(fromUri)
                else -> null
            }
        }

        PrimitiveType.ROOM_ID, PrimitiveType.EVENT_ID -> {
            val parsed = parseRoomOrEventRef(value)
                // A known alias standing in for a room ID; only meaningful for room_id.
                ?: if (this == PrimitiveType.ROOM_ID) {
                    ctx.resolveRoomAlias(unwrapMarkdownLink(value))?.let { ArgValue.RoomRef(PrimitiveType.ROOM_ID, it) }
                } else {
                    null
                }
            // A room permalink does not satisfy an event_id parameter, and vice versa.
            parsed?.takeIf { it.type == this }
        }
    }
}

/**
 * Coerces one raw token against a full schema.
 *
 * Union variants are tried in declaration order and the first success wins, which makes the outcome
 * deterministic and lets a bot order its variants by preference. Arrays are not handled here — they
 * consume several tokens and are driven by [parseArguments].
 */
fun ParamSchema.parseString(raw: String, ctx: CoercionContext = CoercionContext.EMPTY): ArgValue? = when (this) {
    is ParamSchema.Primitive -> type.parseString(raw, ctx)
    is ParamSchema.Union -> variants.firstNotNullOfOrNull { it.parseString(raw, ctx) }
    is ParamSchema.ArrayOf -> null
    is ParamSchema.Literal -> literalMatches(raw, ctx)
}

/** A literal accepts only the one value it declares, parsed in the literal's own type. */
private fun ParamSchema.Literal.literalMatches(raw: String, ctx: CoercionContext): ArgValue? {
    val parsed = when (value) {
        is ArgValue.Str -> PrimitiveType.STRING.parseString(raw, ctx)
        is ArgValue.Num -> PrimitiveType.INTEGER.parseString(raw, ctx)
        is ArgValue.Bool -> PrimitiveType.BOOLEAN.parseString(raw, ctx)
        is ArgValue.RoomRef -> parseRoomOrEventRef(raw)
        is ArgValue.Arr -> null
    }
    return parsed?.takeIf { it == value }
}

// endregion

// region Prefix matching

/**
 * Matches a command prefix at the start of [input] and returns it, or null.
 *
 * Accepts any of [sigils] followed by the command or one of its aliases, optionally followed
 * immediately by the bot's MXID. That last form — `/ban@bot:example.org …` — is the disambiguation
 * escape hatch: it is how a bot command shadowed by one of this client's built-ins stays reachable.
 * The prefix must be followed by a space or the end of input, so `/bans` never matches `/ban`.
 */
fun BotCommand.parsePrefix(input: String, sigils: List<String> = listOf("/")): String? {
    val sigil = sigils.firstOrNull { input.startsWith(it) } ?: return null
    var rest = input.substring(sigil.length)

    // Longest name first, so "rooms add" wins over a hypothetical "rooms" alias.
    val name = allNames.filter { rest.startsWith(it) }.maxByOrNull { it.length } ?: return null
    rest = rest.substring(name.length)
    rest = rest.removePrefix(sender)

    if (rest.isEmpty()) return input
    if (rest[0] != ' ') return null
    return input.substring(0, input.length - rest.trimStart(' ').length)
}

/**
 * Finds the command [draft] invokes and binds its arguments, or null if nothing matches.
 *
 * When several commands match — a bot advertising both `rooms` and `rooms add` — the longest prefix
 * wins, so the more specific command is chosen.
 */
fun matchBotCommand(
    candidates: List<BotCommand>,
    draft: String,
    cursor: Int,
    ctx: CoercionContext = CoercionContext.EMPTY,
    sigils: List<String> = listOf("/"),
): ParsedInvocation? {
    var best: Pair<BotCommand, String>? = null
    for (candidate in candidates) {
        val prefix = candidate.parsePrefix(draft, sigils) ?: continue
        if (best == null || prefix.length > best.second.length) best = candidate to prefix
    }
    val (command, prefix) = best ?: return null
    return command.parseArguments(
        input = draft.substring(prefix.length),
        cursorInArgs = (cursor - prefix.length).coerceAtLeast(0),
        ctx = ctx,
    )
}

// endregion

/**
 * Binds [input] — everything after the command prefix — against this command's parameters.
 *
 * The traversal is a direct port of `EventContent.ParseArguments`; see the file header for the
 * behaviours it reproduces. Errors are collected per parameter rather than first-wins as in Go,
 * because the UI shows all of them at once.
 */
fun BotCommand.parseArguments(input: String, cursorInArgs: Int = input.length, ctx: CoercionContext = CoercionContext.EMPTY): ParsedInvocation {
    val state = ArgumentParseState(input.trimStart(' '), ctx)
    val skipped = BooleanArray(parameters.size)

    for ((i, param) in parameters.withIndex()) {
        // Named arguments may appear anywhere; consume every one sitting at the front of the input.
        // Only `--key=value` binds a value — the space in `--key value` is left in place on purpose,
        // so parseQuoted reads an empty value (which is what makes a bare boolean flag mean true).
        while (state.remaining.startsWith(NAMED_PREFIX)) {
            val nameEnd = state.remaining.indexOfFirst { it == ' ' || it == '=' }
                .takeIf { it >= 0 } ?: state.remaining.length
            val namedKey = state.remaining.substring(NAMED_PREFIX.length, nameEnd)
            val namedIndex = parameters.indexOfFirst { it.key.equals(namedKey, ignoreCase = true) }
            if (namedIndex < 0) break
            val named = parameters[namedIndex]
            state.remaining = state.remaining.substring(nameEnd).removePrefix("=")
            skipped[namedIndex] = true
            state.bind(named, isLast = false, isTail = false, isNamed = true)
        }

        val isTail = param.key == tailParam
        // Optional parameters are reachable only by name, unless they are the tail.
        if (skipped[i] || (param.optional && !isTail)) continue
        state.bind(param, isLast = i == parameters.size - 1, isTail = isTail, isNamed = false)
    }

    return ParsedInvocation(
        command = this,
        arguments = state.arguments,
        errors = state.errors,
        missingRequired = parameters.filter { !it.optional && it.key !in state.arguments }.map { it.key },
        spans = state.spans,
        activeParamKey = state.spans.activeKeyAt(cursorInArgs),
    )
}

/** The parameter whose consumed span contains the cursor; the last one when the cursor is past it. */
private fun List<ArgSpan>.activeKeyAt(cursor: Int): String? {
    firstOrNull { cursor in it.start..it.end }?.let { return it.key }
    return lastOrNull()?.key
}

/**
 * Mutable traversal state for [BotCommand.parseArguments].
 *
 * A class rather than local vars because the Go original threads a shared `input` through a closure,
 * and reproducing that faithfully is what keeps the array and optional-restore branches correct.
 */
private class ArgumentParseState(input: String, private val ctx: CoercionContext) {
    private val total = input.length
    var remaining: String = input
    val arguments = LinkedHashMap<String, ArgValue>()
    val errors = LinkedHashMap<String, String>()
    val spans = mutableListOf<ArgSpan>()

    private fun consumed(): Int = total - remaining.length

    fun bind(param: BotCommandParameter, isLast: Boolean, isTail: Boolean, isNamed: Boolean) {
        val start = consumed()
        val origInput = remaining
        if (param.schema is ParamSchema.ArrayOf) {
            bindArray(param, param.schema, isLast)
        } else {
            bindSingle(param, origInput, isLast, isTail, isNamed)
        }
        spans.add(ArgSpan(param.key, start, maxOf(start, consumed())))
    }

    private fun bindArray(param: BotCommandParameter, schema: ParamSchema.ArrayOf, isLast: Boolean) {
        val hasOpener = remaining.startsWith(ARRAY_OPENER)
        var closed = false
        if (hasOpener) {
            remaining = remaining.substring(ARRAY_OPENER.length)
            if (remaining.startsWith(ARRAY_CLOSER)) {
                remaining = remaining.substring(ARRAY_CLOSER.length).trimStart(' ')
                closed = true
            }
        }

        val collector = mutableListOf<ArgValue>()
        while (remaining.isNotEmpty() && !closed) {
            val (rawValue, rest, wasQuoted) = parseQuoted(remaining)
            remaining = rest
            var item = rawValue
            if (!wasQuoted && hasOpener && item.endsWith(ARRAY_CLOSER)) {
                item = item.trimEnd(ARRAY_CLOSER[0])
                closed = true
            } else if (hasOpener && remaining.startsWith(ARRAY_CLOSER)) {
                remaining = remaining.substring(ARRAY_CLOSER.length).trimStart(' ')
                closed = true
            } else if (!hasOpener && !isLast) {
                // An undelimited array that is not last takes exactly one item, so the following
                // parameters still have something to bind.
                closed = true
            }

            val parsed = schema.items.parseString(item, ctx)
            if (parsed != null) {
                collector.add(parsed)
            } else if (hasOpener || isLast) {
                errors[param.key] = "Couldn't read item ${collector.size + 1} of ${param.key}: \"$item\""
            }
        }
        arguments[param.key] = ArgValue.Arr(collector)
    }

    private fun bindSingle(param: BotCommandParameter, origInput: String, isLast: Boolean, isTail: Boolean, isNamed: Boolean) {
        val (parsedToken, rest, wasQuoted) = parseQuoted(remaining)
        remaining = rest
        var raw = parsedToken
        if ((isLast || isTail) && !wasQuoted && remaining.isNotEmpty()) {
            // An unquoted final/tail argument takes the rest of the line, escapes and all.
            raw += " $remaining"
            remaining = ""
        }

        if (raw.isEmpty() && !wasQuoted) {
            // A named boolean with no value is the flag itself: `--force` means force=true.
            if (isNamed && param.schema.allowsPrimitive(PrimitiveType.BOOLEAN)) {
                arguments[param.key] = ArgValue.Bool(true)
                return
            }
            if (!isNamed && !param.optional) {
                // Left absent rather than errored: an unsupplied required parameter is what
                // `missingRequired` reports, and that is what opens the argument sheet.
                return
            }
        }

        val parsed = param.schema.parseString(raw, ctx)
        if (parsed != null) {
            arguments[param.key] = parsed
            return
        }

        param.effectiveDefault()?.let { arguments[param.key] = it }
        if (param.optional && !isLast && !isNamed) {
            // Put the input back: an optional parameter that doesn't match is simply absent, and the
            // text belongs to whichever parameter comes next.
            remaining = origInput.trimStart(' ')
        } else if (!param.optional || isNamed) {
            errors[param.key] = "Couldn't read ${param.key}: \"$raw\""
        }
    }
}
