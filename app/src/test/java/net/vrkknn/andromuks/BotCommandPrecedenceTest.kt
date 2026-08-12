package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.BotCommand
import net.vrkknn.andromuks.utils.CommandDefinition
import net.vrkknn.andromuks.utils.Commands
import net.vrkknn.andromuks.utils.botCommandSuggestions
import net.vrkknn.andromuks.utils.multiWordPrefixesOf
import net.vrkknn.andromuks.utils.resolveBotCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSC4391 command precedence and visibility.
 *
 * The built-in shadowing rule is a security requirement of the MSC: a bot advertising `myroomnick`
 * would otherwise break this client's own command. The joined-sender rule must fail *open* when
 * membership is unknown, because `RoomMemberCache` is populated lazily and treating "absent" as
 * "not in the room" would hide every bot command during a cold start.
 */
class BotCommandPrecedenceTest {

    private fun cmd(name: String, sender: String = "@bot:example.org") =
        BotCommand("!r:x", "key-$name-$sender", sender, name, emptyList(), "", emptyList(), null)

    private val allJoined: (String) -> Boolean = { true }

    @Test
    fun `a bot command colliding with a built-in name is dropped`() {
        val resolved = resolveBotCommands(
            raw = listOf(cmd("ban"), cmd("mute")),
            builtIns = listOf(CommandDefinition("/ban", description = "Ban a user")),
            isJoined = allJoined,
        )
        assertEquals(listOf("mute"), resolved.map { it.command })
    }

    @Test
    fun `a bot command colliding with a built-in alias is dropped`() {
        val resolved = resolveBotCommands(
            raw = listOf(cmd("part"), cmd("mute")),
            builtIns = listOf(CommandDefinition("/leave", aliases = listOf("/part"), description = "Leave")),
            isJoined = allJoined,
        )
        assertEquals(listOf("mute"), resolved.map { it.command })
    }

    @Test
    fun `only the first word of a nested command can collide`() {
        // "ban" is built in, so "ban list" is shadowed too — the composer could never reach it,
        // because "/ban" would already have been claimed.
        val resolved = resolveBotCommands(
            raw = listOf(cmd("ban list"), cmd("rooms add")),
            builtIns = listOf(CommandDefinition("/ban", description = "Ban a user")),
            isJoined = allJoined,
        )
        assertEquals(listOf("rooms add"), resolved.map { it.command })
    }

    @Test
    fun `commands from a sender who is not in the room are hidden`() {
        val resolved = resolveBotCommands(
            raw = listOf(cmd("mute", "@present:example.org"), cmd("kickall", "@departed:example.org")),
            builtIns = emptyList(),
            isJoined = { it == "@present:example.org" },
        )
        assertEquals(listOf("mute"), resolved.map { it.command })
    }

    @Test
    fun `the real built-in list shadows a moderation bot's vocabulary`() {
        // Documenting the consequence rather than guarding against it: these stay reachable only via
        // the qualified `/ban@bot:example.org` form.
        val resolved = resolveBotCommands(
            raw = listOf(cmd("ban"), cmd("kick"), cmd("invite"), cmd("redact"), cmd("mute")),
            isJoined = allJoined,
        )
        assertEquals(listOf("mute"), resolved.map { it.command })
        assertTrue(Commands.allCommands.any { it.command == "/ban" })
    }

    @Test
    fun `ordering is deterministic when two bots advertise the same command`() {
        val first = cmd("mute", "@aaa:example.org")
        val second = cmd("mute", "@zzz:example.org")
        val forward = resolveBotCommands(listOf(second, first), emptyList(), allJoined)
        val reverse = resolveBotCommands(listOf(first, second), emptyList(), allJoined)
        assertEquals(forward, reverse)
        assertEquals(listOf("@aaa:example.org", "@zzz:example.org"), forward.map { it.sender })
    }

    @Test
    fun `suggestions prefix-match the command and its aliases`() {
        val mute = cmd("mute")
        val withAlias = BotCommand("!r:x", "k", "@bot:example.org", "silence", listOf("shh"), "", emptyList(), null)
        val all = listOf(mute, withAlias)

        assertEquals(all, botCommandSuggestions(all, ""))
        assertEquals(listOf(mute), botCommandSuggestions(all, "mu"))
        assertEquals(listOf(withAlias), botCommandSuggestions(all, "shh"))
        assertEquals(emptyList<BotCommand>(), botCommandSuggestions(all, "zzz"))
    }

    @Test
    fun `only multi-word commands contribute autocomplete prefixes`() {
        val prefixes = multiWordPrefixesOf(listOf(cmd("mute"), cmd("rooms add")))
        assertEquals(setOf(listOf("rooms", "add")), prefixes)
    }
}
