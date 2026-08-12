package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ArgValue
import net.vrkknn.andromuks.utils.CoercionContext
import net.vrkknn.andromuks.utils.ParamSchema
import net.vrkknn.andromuks.utils.PrimitiveType
import net.vrkknn.andromuks.utils.parseString
import net.vrkknn.andromuks.utils.toWireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MSC4391 argument coercion.
 *
 * Two properties are being pinned. First, that we accept the loose input the MSC's TIP invites —
 * "yes" for a boolean, a permalink for a room ID, a Markdown link (which this app's own composer
 * inserts for mentions). Second, and more importantly, that whatever we accept, the value that goes
 * on the wire is canonical: a real JSON boolean, a real number, and the `{type, id, via}` object
 * form for room and event references.
 */
class BotCommandCoercionTest {

    @Test
    fun `booleans accept the usual spellings in both directions`() {
        for (yes in listOf("true", "TRUE", "t", "y", "yes", "1", "on")) {
            assertEquals(yes, ArgValue.Bool(true), PrimitiveType.BOOLEAN.parseString(yes))
        }
        for (no in listOf("false", "F", "n", "no", "0", "off")) {
            assertEquals(no, ArgValue.Bool(false), PrimitiveType.BOOLEAN.parseString(no))
        }
        assertNull(PrimitiveType.BOOLEAN.parseString("maybe"))
        assertNull(PrimitiveType.BOOLEAN.parseString(""))
    }

    @Test
    fun `integers reject anything that is not a whole number`() {
        assertEquals(ArgValue.Num(42), PrimitiveType.INTEGER.parseString("42"))
        assertEquals(ArgValue.Num(-7), PrimitiveType.INTEGER.parseString("-7"))
        assertNull(PrimitiveType.INTEGER.parseString("42abc"))
        assertNull(PrimitiveType.INTEGER.parseString("4.5"))
        assertNull(PrimitiveType.INTEGER.parseString(""))
    }

    @Test
    fun `user ids accept plain mxids permalinks and markdown links`() {
        val expected = ArgValue.Str("@alice:example.org")
        assertEquals(expected, PrimitiveType.USER_ID.parseString("@alice:example.org"))
        assertEquals(expected, PrimitiveType.USER_ID.parseString("https://matrix.to/#/@alice:example.org"))
        assertEquals(expected, PrimitiveType.USER_ID.parseString("matrix:u/alice:example.org"))
        // This is exactly what the composer's @-mention autocomplete inserts.
        assertEquals(
            expected,
            PrimitiveType.USER_ID.parseString("[Alice](https://matrix.to/#/@alice:example.org)"),
        )
        assertNull(PrimitiveType.USER_ID.parseString("alice"))
        assertNull(PrimitiveType.USER_ID.parseString("#room:example.org"))
    }

    @Test
    fun `a display name resolves to an mxid only when the context can disambiguate it`() {
        val ctx = CoercionContext(resolveDisplayName = { if (it == "Alice") "@alice:example.org" else null })
        assertEquals(ArgValue.Str("@alice:example.org"), PrimitiveType.USER_ID.parseString("Alice", ctx))
        assertNull(PrimitiveType.USER_ID.parseString("Bob", ctx))
        // Without a resolver, a bare name is simply not a user ID.
        assertNull(PrimitiveType.USER_ID.parseString("Alice"))
    }

    @Test
    fun `room aliases and server names are validated`() {
        assertEquals(ArgValue.Str("#room:example.org"), PrimitiveType.ROOM_ALIAS.parseString("#room:example.org"))
        assertEquals(
            ArgValue.Str("#room:example.org"),
            PrimitiveType.ROOM_ALIAS.parseString("https://matrix.to/#/%23room:example.org"),
        )
        assertNull(PrimitiveType.ROOM_ALIAS.parseString("room:example.org"))

        assertEquals(ArgValue.Str("example.org"), PrimitiveType.SERVER_NAME.parseString("example.org"))
        assertEquals(ArgValue.Str("example.org:8448"), PrimitiveType.SERVER_NAME.parseString("example.org:8448"))
        assertNull(PrimitiveType.SERVER_NAME.parseString("not a server"))
    }

    @Test
    fun `room ids parse from plain ids and from permalinks carrying via servers`() {
        assertEquals(
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org"),
            PrimitiveType.ROOM_ID.parseString("!room:example.org"),
        )
        assertEquals(
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org", via = listOf("second.example.org")),
            PrimitiveType.ROOM_ID.parseString("https://matrix.to/#/!room:example.org?via=second.example.org"),
        )
        assertEquals(
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org", via = listOf("a.org", "b.org")),
            PrimitiveType.ROOM_ID.parseString("matrix:roomid/room:example.org?via=a.org&via=b.org"),
        )
    }

    @Test
    fun `event ids and room ids do not satisfy each other`() {
        val permalink = "https://matrix.to/#/!room:example.org/\$event?via=a.org"
        assertEquals(
            ArgValue.RoomRef(PrimitiveType.EVENT_ID, "!room:example.org", listOf("a.org"), "\$event"),
            PrimitiveType.EVENT_ID.parseString(permalink),
        )
        // A room permalink cannot fill an event_id parameter, and vice versa.
        assertNull(PrimitiveType.ROOM_ID.parseString(permalink))
        assertNull(PrimitiveType.EVENT_ID.parseString("!room:example.org"))
    }

    @Test
    fun `a known alias can stand in for a room id when the context resolves it`() {
        val ctx = CoercionContext(resolveRoomAlias = { if (it == "#room:example.org") "!room:example.org" else null })
        assertEquals(
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org"),
            PrimitiveType.ROOM_ID.parseString("#room:example.org", ctx),
        )
        assertNull(PrimitiveType.ROOM_ID.parseString("#room:example.org"))
    }

    @Test
    fun `union variants are tried in declaration order and the first match wins`() {
        val schema = ParamSchema.Union(
            listOf(
                ParamSchema.Primitive(PrimitiveType.INTEGER),
                ParamSchema.Primitive(PrimitiveType.STRING),
            ),
        )
        // "42" is a valid integer and a valid string; declaration order makes the outcome stable.
        assertEquals(ArgValue.Num(42), schema.parseString("42"))
        assertEquals(ArgValue.Str("words"), schema.parseString("words"))
    }

    @Test
    fun `a literal accepts only its own value`() {
        val schema = ParamSchema.Literal(ArgValue.Str("mute"))
        assertEquals(ArgValue.Str("mute"), schema.parseString("mute"))
        assertNull(schema.parseString("unmute"))

        // A boolean literal is compared after boolean parsing, so "yes" matches `true`.
        val boolLiteral = ParamSchema.Literal(ArgValue.Bool(true))
        assertEquals(ArgValue.Bool(true), boolLiteral.parseString("yes"))
        assertNull(boolLiteral.parseString("no"))
    }

    @Test
    fun `wire values are canonical JSON types`() {
        assertEquals("hello", ArgValue.Str("hello").toWireValue())
        assertEquals(42L, ArgValue.Num(42).toWireValue())
        assertEquals(true, ArgValue.Bool(true).toWireValue())
        assertEquals(listOf("a", "b"), ArgValue.Arr(listOf(ArgValue.Str("a"), ArgValue.Str("b"))).toWireValue())

        assertEquals(
            mapOf("type" to "room_id", "id" to "!room:example.org", "via" to listOf("a.org")),
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org", listOf("a.org")).toWireValue(),
        )
        // `via` is omitted entirely when empty rather than sent as an empty array.
        assertEquals(
            mapOf("type" to "room_id", "id" to "!room:example.org"),
            ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org").toWireValue(),
        )
        assertEquals(
            mapOf("type" to "event_id", "id" to "!room:example.org", "event_id" to "\$evt"),
            ArgValue.RoomRef(PrimitiveType.EVENT_ID, "!room:example.org", eventId = "\$evt").toWireValue(),
        )
    }
}
