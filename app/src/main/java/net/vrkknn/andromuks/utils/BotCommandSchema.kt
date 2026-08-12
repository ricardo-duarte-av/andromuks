package net.vrkknn.andromuks.utils

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/*
 * MSC4391 "Simplified in-room bot commands" — the type schema and the command-description model.
 *
 * A bot advertises each of its commands as a state event whose content declares the command name,
 * its parameters and the type of each parameter. Clients use those declarations to offer
 * autocomplete and typed argument entry instead of making users guess a shell-like syntax.
 *
 * **This is a port of mautrix-go's `event/cmdschema`, not of the MSC prose.** That package is the
 * de-facto reference implementation — gomuks declares its own built-in commands as
 * `cmdschema.EventContent` values in `pkg/hicli/cmdspec/commands.go` — so matching it is what makes
 * us interoperable with the bots and clients that actually exist. It differs from the MSC text in
 * several load-bearing ways:
 *
 *  - **`literal` has no `literal_type` field.** The JSON type of `value` *is* the type. The MSC
 *    prose describes a `literal_type` property; nothing emits or reads one.
 *  - **`aliases`** (a string array on the command) is an extension the MSC does not mention.
 *  - **`fi.mau.tail_parameter`** names one parameter that swallows the rest of the input line.
 *    No required parameter may follow it.
 *  - **`fi.mau.default_value`** supplies a per-parameter default.
 *
 * Validation is deliberately total and happens once, here: [parseBotCommandDescription] returns
 * null for anything invalid, so a malformed or hostile description is dropped at the boundary and
 * nothing downstream has to re-check. That also implements the MSC's "clients SHOULD hide the
 * command" rule for duplicate parameter keys and the like.
 *
 * This file has no Android dependencies (`@Immutable` is a plain annotation) so it can be unit
 * tested directly — see `BotCommandParsingTest`. Text parsing lives in [BotCommandParse],
 * fallback-body generation in [BotCommandStringify], precedence in [BotCommandResolution].
 */

/** The MSC4391 command envelope key embedded in an outgoing message's content. Unstable prefix. */
const val MSC4391_COMMAND_KEY = "org.matrix.msc4391.command"

/** Command-description state event type, unstable prefix — what gomuks and webmuks exchange today. */
const val BOT_COMMAND_STATE_TYPE = "org.matrix.msc4391.command_description"

/** Stable command-description state event type, accepted for forward compatibility. */
const val BOT_COMMAND_STATE_TYPE_STABLE = "m.bot.command_description"

private const val KEY_TAIL_PARAMETER = "fi.mau.tail_parameter"
private const val KEY_DEFAULT_VALUE = "fi.mau.default_value"

/** Whether [type] is a command-description state event in either prefix. */
fun isBotCommandStateType(type: String?): Boolean = type == BOT_COMMAND_STATE_TYPE || type == BOT_COMMAND_STATE_TYPE_STABLE

/**
 * The predefined argument types.
 *
 * `string`/`integer`/`boolean` are the essential ones; `user_id`/`server_name`/`room_alias` are
 * constrained strings; `room_id`/`event_id` are objects carrying routing information (see
 * [ArgValue.RoomRef]). Note that `room_id` and `event_id` are `primitive` schema types on the wire
 * despite not being scalars — that is how the reference implementation models them.
 */
enum class PrimitiveType(val wireName: String) {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    SERVER_NAME("server_name"),
    USER_ID("user_id"),
    ROOM_ID("room_id"),
    ROOM_ALIAS("room_alias"),
    EVENT_ID("event_id"),
    ;

    companion object {
        fun fromWire(name: String?): PrimitiveType? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * The declared type of one parameter.
 *
 * The nesting rules are narrow and enforced by [parseParamSchema]: arrays may only appear at the top
 * level, unions may only appear at the top level or as an array's item type, and a union's variants
 * may only be primitives or literals. Anything else is rejected rather than approximated.
 */
@Immutable
sealed interface ParamSchema {
    @Immutable
    data class Primitive(val type: PrimitiveType) : ParamSchema

    /** A fixed value. The JSON type of [value] is the type — there is no separate `literal_type`. */
    @Immutable
    data class Literal(val value: ArgValue) : ParamSchema

    @Immutable
    data class Union(val variants: List<ParamSchema>) : ParamSchema

    @Immutable
    data class ArrayOf(val items: ParamSchema) : ParamSchema
}

/**
 * Whether this schema can accept a value of primitive type [prim].
 *
 * Port of `ParameterSchema.AllowsPrimitive`. Used for the "bare `--flag` means true" rule in
 * [BotCommandParse] and to pick the right widget in the argument sheet.
 */
fun ParamSchema.allowsPrimitive(prim: PrimitiveType): Boolean = when (this) {
    is ParamSchema.Primitive -> type == prim
    is ParamSchema.Union -> variants.any { it.allowsPrimitive(prim) }
    is ParamSchema.ArrayOf -> items.allowsPrimitive(prim)
    is ParamSchema.Literal -> false
}

/**
 * The value to use when a required parameter was not supplied.
 *
 * Port of `ParameterSchema.GetDefaultValue`: empty string for the string-ish primitives, 0 for
 * integers, false for booleans, an empty array, the first variant's default for a union, and the
 * value itself for a literal.
 */
fun ParamSchema.defaultValue(): ArgValue? = when (this) {
    is ParamSchema.Primitive -> when (type) {
        PrimitiveType.INTEGER -> ArgValue.Num(0)
        PrimitiveType.BOOLEAN -> ArgValue.Bool(false)
        else -> ArgValue.Str("")
    }

    is ParamSchema.ArrayOf -> ArgValue.Arr(emptyList())

    is ParamSchema.Union -> variants.firstOrNull()?.defaultValue()

    is ParamSchema.Literal -> value
}

/** One declared parameter of a command. */
@Immutable
data class BotCommandParameter(
    val key: String,
    val schema: ParamSchema,
    val optional: Boolean,
    val description: String,
    /** `fi.mau.default_value`, already coerced against [schema]; null when absent or invalid. */
    val declaredDefault: ArgValue?,
) {
    /** The value to prefill in the argument sheet: the declared default, else the schema's. */
    fun effectiveDefault(): ArgValue? = declaredDefault ?: if (optional) null else schema.defaultValue()
}

/**
 * A validated command description, scoped to the room and the bot that advertised it.
 *
 * [sender] is load-bearing twice over: it is the bot the invocation must mention, and it is half of
 * the [stateKey] hash that stops other users from squatting on the command. It cannot be recovered
 * from the state key, which is why command descriptions need their own index rather than living in
 * [RoomStateStore] (whose RAM tier keeps only `content`).
 */
@Immutable
data class BotCommand(
    val roomId: String,
    val stateKey: String,
    val sender: String,
    /** Space-separated for nested commands, e.g. `"rooms add"`. Never carries a sigil. */
    val command: String,
    val aliases: List<String>,
    val description: String,
    val parameters: List<BotCommandParameter>,
    /** Key of the parameter that swallows the rest of the line, or null. */
    val tailParam: String?,
) {
    /** The command split into words, for multi-word autocomplete matching. */
    val words: List<String> get() = command.split(' ').filter { it.isNotEmpty() }

    /** `{room} [reason]` — required parameters in braces, optional ones in brackets. */
    val displaySignature: String
        get() = parameters.joinToString(" ") { if (it.optional) "[${it.key}]" else "{${it.key}}" }

    /** Every name this command answers to, the canonical one first. */
    val allNames: List<String> get() = listOf(command) + aliases

    fun parameter(key: String): BotCommandParameter? = parameters.firstOrNull { it.key.equals(key, ignoreCase = true) }
}

/**
 * Parses one `command_description` state event into a [BotCommand], or null if it must be hidden.
 *
 * Null is returned for a removed description (empty content, which is how state events are
 * "deleted"), for a missing sender, and for every invariant violation ported from
 * `EventContent.Validate` — duplicate parameter keys, a required parameter after the tail
 * parameter, a tail parameter that names nothing, and any invalid parameter schema.
 *
 * The `state_key` is verified against [botCommandStateKey] unless [verifyStateKey] is false. That
 * check is the MSC's only defence against a room member planting a description that appears to come
 * from the bot, so it defaults on; callers that hit a real bot getting the concatenation wrong can
 * turn it off at one call site rather than editing the parser.
 */
fun parseBotCommandDescription(roomId: String, stateKey: String, sender: String?, content: JSONObject?, verifyStateKey: Boolean = true): BotCommand? {
    if (content == null || content.length() == 0) return null
    if (sender.isNullOrBlank()) return null

    val command = content.optString("command").takeIf { it.isNotBlank() } ?: return null
    if (verifyStateKey && !stateKeyMatches(stateKey, command, sender)) {
        android.util.Log.w(
            "Andromuks",
            "BotCommandSchema: dropping '$command' from $sender in $roomId - state_key mismatch " +
                "(got '$stateKey', expected '${botCommandStateKey(command, sender)}')",
        )
        return null
    }

    val tailParam = content.optString(KEY_TAIL_PARAMETER).takeIf { it.isNotBlank() }
    val parameters = parseParameters(content.optJSONArray("parameters"), tailParam) ?: return null
    if (tailParam != null && parameters.none { it.key == tailParam }) return null

    return BotCommand(
        roomId = roomId,
        stateKey = stateKey,
        sender = sender,
        command = command,
        aliases = parseStringList(content, "aliases"),
        description = flattenExtensibleText(content.optJSONObject("description")),
        parameters = parameters,
        tailParam = tailParam,
    )
}

/**
 * Parses the `parameters` array, or null if any parameter or the ordering is invalid.
 *
 * The ordering rule is `EventContent.Validate`'s: once the tail parameter has been seen, every
 * later parameter must be optional, because the tail has already consumed the rest of the input.
 */
private fun parseParameters(array: JSONArray?, tailParam: String?): List<BotCommandParameter>? {
    if (array == null) return emptyList()
    val result = mutableListOf<BotCommandParameter>()
    val seenKeys = mutableSetOf<String>()
    var tailFound = false
    for (i in 0 until array.length()) {
        val json = array.optJSONObject(i) ?: return null
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
        if (!seenKeys.add(key)) return null
        val schema = parseParamSchema(json.optJSONObject("schema")) ?: return null
        val optional = json.optBoolean("optional", false)

        if (key == tailParam) {
            tailFound = true
        } else if (tailFound && !optional) {
            return null
        }

        result.add(
            BotCommandParameter(
                key = key,
                schema = schema,
                optional = optional,
                description = flattenExtensibleText(json.optJSONObject("description")),
                declaredDefault = if (json.has(KEY_DEFAULT_VALUE)) {
                    argValueFromJson(json.opt(KEY_DEFAULT_VALUE))
                } else {
                    null
                },
            ),
        )
    }
    return result
}

/**
 * Parses one type schema, or null if it is invalid.
 *
 * Port of `ParameterSchema.validate`, including its "extra fields" strictness: a schema that carries
 * properties belonging to a different schema type is rejected rather than partially honoured, so a
 * bot cannot smuggle a second interpretation past a lenient client. [parent] is the enclosing
 * schema type, empty at the top level.
 */
fun parseParamSchema(json: JSONObject?, parent: String = ""): ParamSchema? {
    if (json == null) return null
    return when (json.optString("schema_type")) {
        "primitive" -> {
            if (json.has("items") || json.has("variants") || json.has("value")) return null
            PrimitiveType.fromWire(json.optString("type"))?.let { ParamSchema.Primitive(it) }
        }

        "array" -> {
            // Arrays can't be nested in anything, including other arrays.
            if (parent.isNotEmpty()) return null
            if (json.has("type") || json.has("variants") || json.has("value")) return null
            parseParamSchema(json.optJSONObject("items"), "array")?.let { ParamSchema.ArrayOf(it) }
        }

        "union" -> {
            // Unions may only appear at the top level or as an array's item type.
            if (parent.isNotEmpty() && parent != "array") return null
            if (json.has("type") || json.has("items") || json.has("value")) return null
            val variantsJson = json.optJSONArray("variants") ?: return null
            if (variantsJson.length() == 0) return null
            val variants = (0 until variantsJson.length()).map {
                parseParamSchema(variantsJson.optJSONObject(it), "union") ?: return null
            }
            ParamSchema.Union(variants)
        }

        "literal" -> {
            if (json.has("type") || json.has("items") || json.has("variants")) return null
            argValueFromJson(json.opt("value"))?.let { ParamSchema.Literal(it) }
        }

        else -> null
    }
}

/**
 * Converts a raw JSON value into an [ArgValue], or null if its type is not a legal argument value.
 *
 * Accepts the scalars plus the `room_id`/`event_id` object form; an object without one of those two
 * `type` discriminators is rejected, matching the reference implementation's literal validation.
 * Arrays are accepted so that a `fi.mau.default_value` for an array parameter round-trips.
 */
internal fun argValueFromJson(value: Any?): ArgValue? = when (value) {
    is String -> ArgValue.Str(value)

    is Boolean -> ArgValue.Bool(value)

    is Int -> ArgValue.Num(value.toLong())

    is Long -> ArgValue.Num(value)

    // Canonical JSON has no floats, but a lenient encoder may still emit one; truncate like Go does.
    is Double -> ArgValue.Num(value.toLong())

    is JSONObject -> roomRefFromJson(value)

    is JSONArray -> ArgValue.Arr(
        (0 until value.length()).map { argValueFromJson(value.opt(it)) ?: return null },
    )

    else -> null
}

/** Parses the `{type, id, via?, event_id?}` object form used by `room_id` and `event_id`. */
internal fun roomRefFromJson(json: JSONObject): ArgValue.RoomRef? {
    val type = when (json.optString("type")) {
        PrimitiveType.ROOM_ID.wireName -> PrimitiveType.ROOM_ID
        PrimitiveType.EVENT_ID.wireName -> PrimitiveType.EVENT_ID
        else -> return null
    }
    val id = json.optString("id").takeIf { it.startsWith("!") } ?: return null
    val eventId = json.optString("event_id").takeIf { it.isNotBlank() }
    if (type == PrimitiveType.EVENT_ID && eventId?.startsWith("$") != true) return null
    if (type == PrimitiveType.ROOM_ID && eventId != null) return null
    return ArgValue.RoomRef(type = type, id = id, via = parseStringList(json, "via"), eventId = eventId)
}

/**
 * Flattens an MSC1767 extensible-text container down to one plain string.
 *
 * Accepts all three shapes seen in the wild: the spec's `{"m.text": [{"body": "…"}]}` array of
 * representations, a shorthand `{"m.text": "…"}`, and a bare `{"body": "…"}`. When several
 * representations are offered the plain-text one wins — this text is rendered as a label, never as
 * HTML, because it comes from an arbitrary room member.
 */
fun flattenExtensibleText(json: JSONObject?): String {
    if (json == null) return ""
    val text = json.opt("m.text")
    if (text is String) return text
    if (text is JSONArray) {
        val representations = (0 until text.length())
            .mapNotNull { text.optJSONObject(it) }
            .mapNotNull { entry ->
                val body = entry.optString("body").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                body to entry.optString("mimetype").takeIf { it.isNotBlank() }
            }
        // The plain-text representation wins; otherwise take whatever came first.
        val plain = representations.firstOrNull { (_, mimeType) -> mimeType == null || mimeType == "text/plain" }
        val chosen = plain ?: representations.firstOrNull()
        if (chosen != null) return chosen.first
    }
    return json.optString("body")
}
