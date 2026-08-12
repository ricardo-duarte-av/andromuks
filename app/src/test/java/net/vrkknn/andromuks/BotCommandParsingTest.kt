package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ArgValue
import net.vrkknn.andromuks.utils.ParamSchema
import net.vrkknn.andromuks.utils.PrimitiveType
import net.vrkknn.andromuks.utils.allowsPrimitive
import net.vrkknn.andromuks.utils.botCommandStateKey
import net.vrkknn.andromuks.utils.defaultValue
import net.vrkknn.andromuks.utils.flattenExtensibleText
import net.vrkknn.andromuks.utils.isBotCommandStateType
import net.vrkknn.andromuks.utils.parseBotCommandDescription
import net.vrkknn.andromuks.utils.parseParamSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSC4391 command-description parsing.
 *
 * These assert on parsed *values*, not just on non-null, because the unit-test `android.jar` is a
 * stub and `isReturnDefaultValues` would otherwise let a parser that read nothing pass — the same
 * reasoning documented on `ReactionEventParsingTest`.
 *
 * The rejection cases are the important half: the MSC tells clients to hide invalid commands, and
 * this parser is the single place that decision is made.
 */
class BotCommandParsingTest {

    private val sender = "@draupnir:draupnir.space"

    private fun primitive(type: String) = """{"schema_type":"primitive","type":"$type"}"""

    private fun description(
        command: String = "ban",
        parameters: String = "[]",
        extra: String = "",
        senderOverride: String = sender,
        stateKey: String? = null,
    ): net.vrkknn.andromuks.utils.BotCommand? = parseBotCommandDescription(
        roomId = "!room:example.org",
        stateKey = stateKey ?: botCommandStateKey(command, senderOverride),
        sender = senderOverride,
        content = JSONObject(
            """{"command":"$command","parameters":$parameters,
               "description":{"m.text":[{"body":"An example command"}]}$extra}""",
        ),
    )

    @Test
    fun `both stable and unstable state event types are recognised`() {
        assertTrue(isBotCommandStateType("org.matrix.msc4391.command_description"))
        assertTrue(isBotCommandStateType("m.bot.command_description"))
        assertFalse(isBotCommandStateType("m.room.message"))
        assertFalse(isBotCommandStateType(null))
    }

    @Test
    fun `a full description parses every field`() {
        val parsed = description(
            command = "rooms add",
            parameters = """[
                {"key":"target_room","schema":${primitive("room_id")},
                 "description":{"m.text":[{"body":"The room ID"}]}},
                {"key":"reason","schema":${primitive("string")},"optional":true,
                 "description":{"m.text":[{"body":"Why"}]}}
            ]""",
            extra = ""","aliases":["r add"],"fi.mau.tail_parameter":"reason"""",
        )
        assertNotNull(parsed)
        requireNotNull(parsed)

        assertEquals("rooms add", parsed.command)
        assertEquals(listOf("rooms", "add"), parsed.words)
        assertEquals(listOf("r add"), parsed.aliases)
        assertEquals("An example command", parsed.description)
        assertEquals("reason", parsed.tailParam)
        assertEquals(sender, parsed.sender)
        assertEquals(2, parsed.parameters.size)

        val target = parsed.parameters[0]
        assertEquals("target_room", target.key)
        assertEquals(ParamSchema.Primitive(PrimitiveType.ROOM_ID), target.schema)
        assertEquals("The room ID", target.description)
        assertFalse(target.optional)

        assertTrue(parsed.parameters[1].optional)
        assertEquals("{target_room} [reason]", parsed.displaySignature)
    }

    @Test
    fun `all eight primitive types are accepted`() {
        val wireNames = listOf("string", "integer", "boolean", "server_name", "user_id", "room_id", "room_alias", "event_id")
        for (name in wireNames) {
            val schema = parseParamSchema(JSONObject(primitive(name)))
            assertEquals("primitive $name", ParamSchema.Primitive(PrimitiveType.fromWire(name)!!), schema)
        }
        assertNull(parseParamSchema(JSONObject(primitive("float"))))
    }

    @Test
    fun `array union and literal schemas parse`() {
        val array = parseParamSchema(
            JSONObject("""{"schema_type":"array","items":${primitive("user_id")}}"""),
        )
        assertEquals(ParamSchema.ArrayOf(ParamSchema.Primitive(PrimitiveType.USER_ID)), array)

        val union = parseParamSchema(
            JSONObject("""{"schema_type":"union","variants":[${primitive("room_id")},${primitive("room_alias")}]}"""),
        )
        assertEquals(
            ParamSchema.Union(
                listOf(
                    ParamSchema.Primitive(PrimitiveType.ROOM_ID),
                    ParamSchema.Primitive(PrimitiveType.ROOM_ALIAS),
                ),
            ),
            union,
        )
    }

    @Test
    fun `a literal takes its type from the JSON value with no literal_type field`() {
        assertEquals(
            ParamSchema.Literal(ArgValue.Str("mute")),
            parseParamSchema(JSONObject("""{"schema_type":"literal","value":"mute"}""")),
        )
        assertEquals(
            ParamSchema.Literal(ArgValue.Num(42)),
            parseParamSchema(JSONObject("""{"schema_type":"literal","value":42}""")),
        )
        assertEquals(
            ParamSchema.Literal(ArgValue.Bool(true)),
            parseParamSchema(JSONObject("""{"schema_type":"literal","value":true}""")),
        )
    }

    @Test
    fun `illegal schema nesting is rejected`() {
        // Arrays cannot nest in anything, including another array.
        assertNull(
            parseParamSchema(
                JSONObject("""{"schema_type":"array","items":{"schema_type":"array","items":${primitive("string")}}}"""),
            ),
        )
        // A union of arrays is forbidden.
        assertNull(
            parseParamSchema(
                JSONObject(
                    """{"schema_type":"union","variants":[{"schema_type":"array","items":${primitive("string")}}]}""",
                ),
            ),
        )
        // Nested unions must be flattened by the sender.
        assertNull(
            parseParamSchema(
                JSONObject(
                    """{"schema_type":"union","variants":[{"schema_type":"union","variants":[${primitive("string")}]}]}""",
                ),
            ),
        )
        // An empty union accepts nothing and is meaningless.
        assertNull(parseParamSchema(JSONObject("""{"schema_type":"union","variants":[]}""")))
        assertNull(parseParamSchema(JSONObject("""{"schema_type":"nonsense"}""")))
        assertNull(parseParamSchema(null))
    }

    @Test
    fun `a schema carrying fields of another schema type is rejected`() {
        // Strictness matters: a lenient client could be talked into a second interpretation.
        assertNull(parseParamSchema(JSONObject("""{"schema_type":"primitive","type":"string","value":"x"}""")))
        assertNull(parseParamSchema(JSONObject("""{"schema_type":"array","type":"string","items":${primitive("string")}}""")))
        assertNull(parseParamSchema(JSONObject("""{"schema_type":"literal","value":"x","type":"string"}""")))
    }

    @Test
    fun `duplicate parameter keys hide the command`() {
        assertNull(
            description(
                parameters = """[
                    {"key":"target","schema":${primitive("string")}},
                    {"key":"target","schema":${primitive("integer")}}
                ]""",
            ),
        )
    }

    @Test
    fun `a required parameter after the tail parameter hides the command`() {
        assertNull(
            description(
                parameters = """[
                    {"key":"reason","schema":${primitive("string")}},
                    {"key":"target","schema":${primitive("string")}}
                ]""",
                extra = ""","fi.mau.tail_parameter":"reason"""",
            ),
        )
        // An optional parameter after the tail is fine — it is only reachable by name anyway.
        assertNotNull(
            description(
                parameters = """[
                    {"key":"reason","schema":${primitive("string")}},
                    {"key":"force","schema":${primitive("boolean")},"optional":true}
                ]""",
                extra = ""","fi.mau.tail_parameter":"reason"""",
            ),
        )
    }

    @Test
    fun `a tail parameter naming nothing hides the command`() {
        assertNull(
            description(
                parameters = """[{"key":"target","schema":${primitive("string")}}]""",
                extra = ""","fi.mau.tail_parameter":"nonexistent"""",
            ),
        )
    }

    @Test
    fun `blank command blank sender and empty content are all rejected`() {
        assertNull(description(command = ""))
        assertNull(
            parseBotCommandDescription("!r:x", "key", null, JSONObject("""{"command":"ban"}""")),
        )
        assertNull(
            parseBotCommandDescription("!r:x", "key", sender, JSONObject("{}")),
        )
        assertNull(parseBotCommandDescription("!r:x", "key", sender, null))
    }

    @Test
    fun `a mismatched state key hides the command unless verification is disabled`() {
        assertNull(description(stateKey = "not-the-hash"))

        // The kill switch exists for a bot that gets the concatenation wrong; it must still parse.
        val unverified = parseBotCommandDescription(
            roomId = "!room:example.org",
            stateKey = "not-the-hash",
            sender = sender,
            content = JSONObject("""{"command":"ban","parameters":[]}"""),
            verifyStateKey = false,
        )
        assertEquals("ban", unverified?.command)
    }

    @Test
    fun `a declared default value is parsed and used`() {
        val parsed = description(
            parameters = """[
                {"key":"timeout","schema":${primitive("integer")},"fi.mau.default_value":30}
            ]""",
        )
        assertEquals(ArgValue.Num(30), parsed?.parameters?.first()?.declaredDefault)
        assertEquals(ArgValue.Num(30), parsed?.parameters?.first()?.effectiveDefault())
    }

    @Test
    fun `schema defaults follow the reference implementation`() {
        assertEquals(ArgValue.Num(0), ParamSchema.Primitive(PrimitiveType.INTEGER).defaultValue())
        assertEquals(ArgValue.Bool(false), ParamSchema.Primitive(PrimitiveType.BOOLEAN).defaultValue())
        assertEquals(ArgValue.Str(""), ParamSchema.Primitive(PrimitiveType.USER_ID).defaultValue())
        assertEquals(ArgValue.Arr(emptyList()), ParamSchema.ArrayOf(ParamSchema.Primitive(PrimitiveType.STRING)).defaultValue())
        assertEquals(
            ArgValue.Num(0),
            ParamSchema.Union(
                listOf(ParamSchema.Primitive(PrimitiveType.INTEGER), ParamSchema.Primitive(PrimitiveType.STRING)),
            ).defaultValue(),
        )
    }

    @Test
    fun `allowsPrimitive sees through unions and arrays`() {
        val union = ParamSchema.Union(
            listOf(ParamSchema.Primitive(PrimitiveType.BOOLEAN), ParamSchema.Primitive(PrimitiveType.STRING)),
        )
        assertTrue(union.allowsPrimitive(PrimitiveType.BOOLEAN))
        assertFalse(union.allowsPrimitive(PrimitiveType.ROOM_ID))
        assertTrue(ParamSchema.ArrayOf(union).allowsPrimitive(PrimitiveType.BOOLEAN))
        assertFalse(ParamSchema.Literal(ArgValue.Bool(true)).allowsPrimitive(PrimitiveType.BOOLEAN))
    }

    @Test
    fun `extensible text is flattened from every shape`() {
        assertEquals("Hello", flattenExtensibleText(JSONObject("""{"m.text":[{"body":"Hello"}]}""")))
        assertEquals("Hello", flattenExtensibleText(JSONObject("""{"m.text":"Hello"}""")))
        assertEquals("Hello", flattenExtensibleText(JSONObject("""{"body":"Hello"}""")))
        assertEquals("", flattenExtensibleText(null))
        assertEquals("", flattenExtensibleText(JSONObject("{}")))
        // The plain-text representation wins over a richer one; this is rendered as a label.
        assertEquals(
            "Plain",
            flattenExtensibleText(
                JSONObject("""{"m.text":[{"body":"<b>Rich</b>","mimetype":"text/html"},{"body":"Plain"}]}"""),
            ),
        )
    }
}
