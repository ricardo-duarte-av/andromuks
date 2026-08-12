package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ArgValue
import net.vrkknn.andromuks.utils.BotCommand
import net.vrkknn.andromuks.utils.BotCommandParameter
import net.vrkknn.andromuks.utils.ParamSchema
import net.vrkknn.andromuks.utils.PrimitiveType
import net.vrkknn.andromuks.utils.commandFallbackBody
import net.vrkknn.andromuks.utils.parseArguments
import net.vrkknn.andromuks.utils.stringifyArgs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MSC4391 fallback-body generation.
 *
 * The body is not authoritative — the JSON envelope is — but bots that have not adopted the MSC
 * still read it and every other client renders it. The round-trip tests are the real point: what we
 * write must re-parse to what we sent, or a bot reading the body would act on different arguments
 * than the ones in the envelope.
 */
class BotCommandStringifyTest {

    private fun param(
        key: String,
        schema: ParamSchema = ParamSchema.Primitive(PrimitiveType.STRING),
        optional: Boolean = false,
    ) = BotCommandParameter(key, schema, optional, "", null)

    private fun command(params: List<BotCommandParameter>) =
        BotCommand("!r:x", "key", "@bot:example.org", "ban", emptyList(), "", params, null)

    @Test
    fun `plain arguments are rendered in declaration order`() {
        val cmd = command(
            listOf(
                param("user", ParamSchema.Primitive(PrimitiveType.USER_ID)),
                param("timeout", ParamSchema.Primitive(PrimitiveType.INTEGER)),
                param("force", ParamSchema.Primitive(PrimitiveType.BOOLEAN)),
            ),
        )
        val args = mapOf(
            "user" to ArgValue.Str("@alice:example.org"),
            "timeout" to ArgValue.Num(42),
            "force" to ArgValue.Bool(true),
        )
        assertEquals("@alice:example.org 42 true", cmd.stringifyArgs(args))
        assertEquals("/ban @alice:example.org 42 true", cmd.commandFallbackBody(args))
    }

    @Test
    fun `values are quoted only when they would otherwise re-parse as several arguments`() {
        val cmd = command(listOf(param("reason")))
        assertEquals("spam", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str("spam"))))
        assertEquals("\"lots of spam\"", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str("lots of spam"))))
        // The array delimiters must be quoted too, or they would open or close a list.
        assertEquals("\"a<b>c\"", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str("a<b>c"))))
        // An empty string has to be quoted or it would vanish.
        assertEquals("\"\"", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str(""))))
    }

    @Test
    fun `quotes and backslashes are escaped exactly once`() {
        val cmd = command(listOf(param("reason")))
        assertEquals("\"say \\\"hi\\\"\"", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str("say \"hi\""))))
        assertEquals("\"back\\\\slash\"", cmd.stringifyArgs(mapOf("reason" to ArgValue.Str("back\\slash"))))
    }

    @Test
    fun `a trailing array is written bare and a mid-list one is delimited`() {
        val users = ParamSchema.ArrayOf(ParamSchema.Primitive(PrimitiveType.USER_ID))
        val trailing = command(listOf(param("reason"), param("users", users)))
        assertEquals(
            "spam @a:x.org @b:x.org",
            trailing.stringifyArgs(
                mapOf(
                    "reason" to ArgValue.Str("spam"),
                    "users" to ArgValue.Arr(listOf(ArgValue.Str("@a:x.org"), ArgValue.Str("@b:x.org"))),
                ),
            ),
        )

        val leading = command(listOf(param("users", users), param("reason")))
        assertEquals(
            "<@a:x.org @b:x.org> spam",
            leading.stringifyArgs(
                mapOf(
                    "users" to ArgValue.Arr(listOf(ArgValue.Str("@a:x.org"), ArgValue.Str("@b:x.org"))),
                    "reason" to ArgValue.Str("spam"),
                ),
            ),
        )
    }

    @Test
    fun `room references are written as matrix URIs preserving via servers`() {
        val cmd = command(listOf(param("room", ParamSchema.Primitive(PrimitiveType.ROOM_ID))))
        assertEquals(
            "matrix:roomid/room:example.org",
            cmd.stringifyArgs(mapOf("room" to ArgValue.RoomRef(PrimitiveType.ROOM_ID, "!room:example.org"))),
        )
        assertEquals(
            "\"matrix:roomid/room:example.org?via=a.org&via=b.org\"",
            cmd.stringifyArgs(
                mapOf(
                    "room" to ArgValue.RoomRef(
                        PrimitiveType.ROOM_ID,
                        "!room:example.org",
                        listOf("a.org", "b.org"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `an optional parameter with no value and no default is skipped`() {
        val cmd = command(listOf(param("user"), param("reason", optional = true)))
        assertEquals("alice", cmd.stringifyArgs(mapOf("user" to ArgValue.Str("alice"))))
    }

    @Test
    fun `the body round-trips back to the same arguments`() {
        val cmd = command(
            listOf(
                param("user", ParamSchema.Primitive(PrimitiveType.USER_ID)),
                param("timeout", ParamSchema.Primitive(PrimitiveType.INTEGER)),
                param("reason"),
            ),
        )
        val args = mapOf(
            "user" to ArgValue.Str("@alice:example.org"),
            "timeout" to ArgValue.Num(42),
            "reason" to ArgValue.Str("lots of \"spam\""),
        )
        val reparsed = cmd.parseArguments(cmd.stringifyArgs(args))
        assertEquals(args, reparsed.arguments)
        assertEquals(emptyMap<String, String>(), reparsed.errors)
    }

    @Test
    fun `an array body round-trips through the delimiters`() {
        val cmd = command(
            listOf(
                param("users", ParamSchema.ArrayOf(ParamSchema.Primitive(PrimitiveType.USER_ID))),
                param("reason"),
            ),
        )
        val args = mapOf(
            "users" to ArgValue.Arr(listOf(ArgValue.Str("@a:x.org"), ArgValue.Str("@b:x.org"))),
            "reason" to ArgValue.Str("spam of all kinds"),
        )
        assertEquals(args, cmd.parseArguments(cmd.stringifyArgs(args)).arguments)
    }
}
