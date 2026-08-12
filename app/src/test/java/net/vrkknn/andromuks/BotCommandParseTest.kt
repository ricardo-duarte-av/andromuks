package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ArgValue
import net.vrkknn.andromuks.utils.BotCommand
import net.vrkknn.andromuks.utils.BotCommandParameter
import net.vrkknn.andromuks.utils.ParamSchema
import net.vrkknn.andromuks.utils.PrimitiveType
import net.vrkknn.andromuks.utils.matchBotCommand
import net.vrkknn.andromuks.utils.parseArguments
import net.vrkknn.andromuks.utils.parsePrefix
import net.vrkknn.andromuks.utils.parseQuoted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSC4391 command-line binding.
 *
 * Every case here mirrors a branch of mautrix-go's `EventContent.ParseArguments`. They are pinned
 * because the same text must mean the same thing to us, to gomuks and to a bot re-parsing the
 * `body` fallback — a "nicer" grammar would silently disagree with the rest of the ecosystem.
 */
class BotCommandParseTest {

    private val bot = "@bot:example.org"

    private fun param(
        key: String,
        type: PrimitiveType = PrimitiveType.STRING,
        optional: Boolean = false,
    ) = BotCommandParameter(key, ParamSchema.Primitive(type), optional, "", null)

    private fun arrayParam(key: String, type: PrimitiveType = PrimitiveType.STRING, optional: Boolean = false) =
        BotCommandParameter(key, ParamSchema.ArrayOf(ParamSchema.Primitive(type)), optional, "", null)

    private fun command(
        name: String = "ban",
        params: List<BotCommandParameter> = emptyList(),
        aliases: List<String> = emptyList(),
        tail: String? = null,
    ) = BotCommand("!r:x", "key", bot, name, aliases, "", params, tail)

    private fun str(command: BotCommand, input: String, key: String): String? =
        (command.parseArguments(input).arguments[key] as? ArgValue.Str)?.value

    // region parseQuoted

    @Test
    fun `unquoted tokens split on the first space`() {
        assertEquals(Triple("one", "two three", false), parseQuoted("one two three"))
        assertEquals(Triple("only", "", false), parseQuoted("only"))
        assertEquals(Triple("", "", false), parseQuoted(""))
        // Runs of spaces collapse, so a double space does not produce an empty argument.
        assertEquals(Triple("one", "two", false), parseQuoted("one   two"))
    }

    @Test
    fun `quoted tokens keep their spaces and report being quoted`() {
        assertEquals(Triple("one two", "three", true), parseQuoted("\"one two\" three"))
        // An empty quoted string is a real, present, empty argument — not a missing one.
        assertEquals(Triple("", "next", true), parseQuoted("\"\" next"))
    }

    @Test
    fun `escapes inside quotes are unescaped`() {
        assertEquals("say \"hi\"", parseQuoted("\"say \\\"hi\\\"\" rest").first)
        assertEquals("back\\slash", parseQuoted("\"back\\\\slash\"").first)
    }

    @Test
    fun `an unterminated quote takes the rest of the line`() {
        assertEquals(Triple("no closing quote", "", true), parseQuoted("\"no closing quote"))
        // With escapes already consumed the buffer is non-empty, so the remainder is appended.
        assertEquals("a\"bc", parseQuoted("\"a\\\"bc").first)
    }

    // endregion

    // region prefix matching

    @Test
    fun `parsePrefix requires a sigil and a word boundary`() {
        val cmd = command("ban")
        assertEquals("/ban ", cmd.parsePrefix("/ban @alice:example.org"))
        assertEquals("/ban", cmd.parsePrefix("/ban"))
        // A longer word that merely starts with the command must not match.
        assertNull(cmd.parsePrefix("/bans @alice:example.org"))
        assertNull(cmd.parsePrefix("ban @alice:example.org"))
        assertNull(cmd.parsePrefix("hello /ban"))
    }

    @Test
    fun `parsePrefix accepts aliases and the qualified bot form`() {
        val cmd = command("ban", aliases = listOf("b"))
        assertEquals("/b ", cmd.parsePrefix("/b @alice:example.org"))
        // The escape hatch for a command shadowed by a built-in.
        assertEquals("/ban@bot:example.org ", cmd.parsePrefix("/ban@bot:example.org @alice:example.org"))
        // A different bot's MXID is not a valid qualification for this command.
        assertNull(cmd.parsePrefix("/ban@other:example.org @alice:example.org"))
    }

    @Test
    fun `matchBotCommand prefers the longest matching command`() {
        val rooms = command("rooms", listOf(param("action")))
        val roomsAdd = command("rooms add", listOf(param("room")))
        val matched = matchBotCommand(listOf(rooms, roomsAdd), "/rooms add !r:x", cursor = 15)
        assertEquals("rooms add", matched?.command?.command)
        assertEquals(null, matchBotCommand(listOf(rooms, roomsAdd), "/other thing", cursor = 12))
    }

    // endregion

    // region positional binding

    @Test
    fun `required parameters bind positionally in declaration order`() {
        val cmd = command("ban", listOf(param("user"), param("reason")))
        val parsed = cmd.parseArguments("alice spamming")
        assertEquals(ArgValue.Str("alice"), parsed.arguments["user"])
        assertEquals(ArgValue.Str("spamming"), parsed.arguments["reason"])
        assertTrue(parsed.isComplete)
    }

    @Test
    fun `the last unquoted argument swallows the rest of the line`() {
        val cmd = command("ban", listOf(param("user"), param("reason")))
        assertEquals("spamming the room repeatedly", str(cmd, "alice spamming the room repeatedly", "reason"))
        // Quoting opts out, leaving the trailing text unconsumed.
        assertEquals("spamming", str(cmd, """alice "spamming" and more""", "reason"))
    }

    @Test
    fun `the tail parameter swallows the line even when it is not last`() {
        val cmd = command(
            "ban",
            listOf(param("user"), param("reason"), param("force", PrimitiveType.BOOLEAN, optional = true)),
            tail = "reason",
        )
        assertEquals("spamming the room", str(cmd, "alice spamming the room", "reason"))
    }

    @Test
    fun `an unsupplied required parameter is reported as missing rather than as an error`() {
        val cmd = command("ban", listOf(param("user"), param("reason")))
        val parsed = cmd.parseArguments("alice")
        assertEquals(listOf("reason"), parsed.missingRequired)
        assertTrue(parsed.errors.isEmpty())
        assertTrue(!parsed.isComplete)
    }

    @Test
    fun `a required parameter that fails to parse is an error`() {
        val cmd = command("ban", listOf(param("timeout", PrimitiveType.INTEGER)))
        val parsed = cmd.parseArguments("not-a-number")
        assertTrue(parsed.errors.containsKey("timeout"))
        assertTrue(!parsed.isComplete)
    }

    // endregion

    // region named arguments

    @Test
    fun `optional non-tail parameters bind only by name`() {
        val cmd = command("ban", listOf(param("user"), param("reason", optional = true)))
        // Positionally, the optional parameter is skipped entirely — this is the reference
        // implementation's behaviour, and it contradicts a plain reading of the MSC.
        val positional = cmd.parseArguments("alice spamming")
        assertEquals(ArgValue.Str("alice"), positional.arguments["user"])
        assertNull(positional.arguments["reason"])

        assertEquals("spamming", str(cmd, "--reason=spamming alice", "reason"))
        assertEquals("spamming", str(cmd, "--reason spamming alice", "reason"))
    }

    @Test
    fun `a bare named boolean flag means true`() {
        val cmd = command("ban", listOf(param("user"), param("force", PrimitiveType.BOOLEAN, optional = true)))
        assertEquals(ArgValue.Bool(true), cmd.parseArguments("--force alice").arguments["force"])
        assertEquals(ArgValue.Bool(false), cmd.parseArguments("--force=no alice").arguments["force"])
    }

    @Test
    fun `an unknown named argument is left for positional binding`() {
        val cmd = command("ban", listOf(param("user"), param("reason")))
        val parsed = cmd.parseArguments("--unknown alice spam")
        assertEquals(ArgValue.Str("--unknown"), parsed.arguments["user"])
        assertEquals(ArgValue.Str("alice spam"), parsed.arguments["reason"])
    }

    // endregion

    // region arrays

    @Test
    fun `a trailing array consumes every remaining token`() {
        val cmd = command("ban", listOf(param("reason"), arrayParam("users", PrimitiveType.USER_ID)))
        val parsed = cmd.parseArguments("spam @a:x.org @b:x.org @c:x.org")
        assertEquals(
            ArgValue.Arr(
                listOf(ArgValue.Str("@a:x.org"), ArgValue.Str("@b:x.org"), ArgValue.Str("@c:x.org")),
            ),
            parsed.arguments["users"],
        )
    }

    @Test
    fun `a delimited array can sit in the middle of the parameter list`() {
        val cmd = command("ban", listOf(arrayParam("users", PrimitiveType.USER_ID), param("reason")))
        val parsed = cmd.parseArguments("<@a:x.org @b:x.org> spamming the room")
        assertEquals(
            ArgValue.Arr(listOf(ArgValue.Str("@a:x.org"), ArgValue.Str("@b:x.org"))),
            parsed.arguments["users"],
        )
        assertEquals(ArgValue.Str("spamming the room"), parsed.arguments["reason"])
    }

    @Test
    fun `an undelimited array in the middle takes exactly one item`() {
        val cmd = command("ban", listOf(arrayParam("users", PrimitiveType.USER_ID), param("reason")))
        val parsed = cmd.parseArguments("@a:x.org spamming")
        assertEquals(ArgValue.Arr(listOf(ArgValue.Str("@a:x.org"))), parsed.arguments["users"])
        assertEquals(ArgValue.Str("spamming"), parsed.arguments["reason"])
    }

    @Test
    fun `an empty delimited array is empty and consumes nothing else`() {
        val cmd = command("ban", listOf(arrayParam("users", PrimitiveType.USER_ID), param("reason")))
        val parsed = cmd.parseArguments("<> spamming")
        assertEquals(ArgValue.Arr(emptyList()), parsed.arguments["users"])
        assertEquals(ArgValue.Str("spamming"), parsed.arguments["reason"])
    }

    // endregion

    @Test
    fun `the active parameter follows the cursor`() {
        val cmd = command("ban", listOf(param("user"), param("reason")))
        // "alice spamming" — offsets 0..5 are the user, 6.. is the reason.
        assertEquals("user", cmd.parseArguments("alice spamming", cursorInArgs = 2).activeParamKey)
        assertEquals("reason", cmd.parseArguments("alice spamming", cursorInArgs = 10).activeParamKey)
        // Past the end, the last parameter stays highlighted so the strip does not go blank.
        assertEquals("reason", cmd.parseArguments("alice spamming", cursorInArgs = 99).activeParamKey)
    }
}
