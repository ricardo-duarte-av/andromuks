package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ArgValue
import net.vrkknn.andromuks.utils.BotCommand
import net.vrkknn.andromuks.utils.CoercionContext
import net.vrkknn.andromuks.utils.MSC4391_COMMAND_KEY
import net.vrkknn.andromuks.utils.commandFallbackBody
import net.vrkknn.andromuks.utils.isBotCommandStateType
import net.vrkknn.andromuks.utils.parseBotCommandDescription
import net.vrkknn.andromuks.utils.resolveBotCommands
import net.vrkknn.andromuks.utils.threadRelatesTo
import net.vrkknn.andromuks.utils.toWireValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * BotCommandCoordinator — MSC4391 in-room bot commands.
 *
 * Owns the two halves the feature needs from the ViewModel layer: turning `command_description`
 * state events into [BotCommandCache] entries, and turning a bound invocation into an outgoing
 * message. The schema, text parsing and precedence rules are pure functions in `utils/` — see
 * `BotCommandSchema.kt` and its siblings.
 *
 * The wire format deliberately mirrors [PollCoordinator.sendPollCreate], which has been shipping the
 * same envelope to gomuks' internal bot since polls landed: a normal `send_message` whose
 * `base_content` carries the command object. gomuks merges `base_content` into the outgoing content
 * verbatim, and only intercepts the envelope when the mention list is exactly `[@gomuks]`
 * (`pkg/hicli/send.go`), so a command addressed to a real bot passes straight through and keeps
 * local echo. See docs/BOT_COMMANDS.md.
 */
internal class BotCommandCoordinator(private val vm: AppViewModel) {

    /**
     * Indexes the command descriptions in a full `get_room_state` response.
     *
     * Must be called even when [events] contains none, because the cache replaces rather than
     * merges: a bot that removed a command has to lose it here too.
     */
    fun ingestFullState(roomId: String, events: JSONArray?) {
        val parsed = (0 until (events?.length() ?: 0))
            .mapNotNull { events?.optJSONObject(it) }
            .filter { isBotCommandStateType(it.optString("type")) }
            .mapNotNull { event ->
                parseBotCommandDescription(
                    roomId = roomId,
                    stateKey = event.optString("state_key"),
                    sender = event.optString("sender").takeIf { it.isNotBlank() },
                    content = event.optJSONObject("content"),
                )
            }
        BotCommandCache.setRoomCommands(roomId, parsed)
    }

    /**
     * Applies command descriptions arriving as live timeline events, so a bot registering a command
     * while the room is open shows up without reopening it.
     *
     * A description with empty content is how a state event is removed, and
     * [parseBotCommandDescription] returns null for it — hence the explicit [BotCommandCache.remove]
     * rather than simply skipping.
     */
    fun ingestLiveStateEvents(roomId: String, events: List<TimelineEvent>) {
        events
            .filter { isBotCommandStateType(it.decryptedType ?: it.type) && it.stateKey != null }
            .forEach { event ->
                val stateKey = event.stateKey.orEmpty()
                val parsed = parseBotCommandDescription(
                    roomId = roomId,
                    stateKey = stateKey,
                    sender = event.sender.takeIf { it.isNotBlank() },
                    content = event.decrypted ?: event.content,
                )
                if (parsed != null) BotCommandCache.upsert(parsed) else BotCommandCache.remove(roomId, stateKey)
            }
    }

    /**
     * The bot commands the composer may offer in this room.
     *
     * The joined-sender check fails open when the member list has not been fetched: absence from
     * [RoomMemberCache] is not proof of non-membership, and hiding every bot command until members
     * load would make the feature look broken on a cold start.
     */
    fun commandsForComposer(roomId: String): List<BotCommand> {
        // Computed once per call rather than per command: an empty map is the "we have not loaded
        // members yet" signal, and it must not flip between commands in the same pass.
        val membershipKnown = vm.getMemberMap(roomId).isNotEmpty()
        return resolveBotCommands(
            raw = BotCommandCache.rawCommandsFor(roomId),
            isJoined = { sender -> !membershipKnown || RoomMemberCache.getMember(roomId, sender) != null },
        )
    }

    /** Lookups that let the argument parser accept display names and room aliases. */
    fun coercionContext(roomId: String): CoercionContext = CoercionContext(
        resolveDisplayName = { name ->
            val matches = vm.getMemberMap(roomId).entries.filter { (_, profile) ->
                profile.displayName?.equals(name, ignoreCase = true) == true
            }
            // Only an unambiguous name may stand in for an MXID — banning the wrong Alice is worse
            // than making the user paste an ID.
            matches.singleOrNull()?.key
        },
        resolveRoomAlias = { alias -> vm.getRoomsWithCanonicalAliases().firstOrNull { it.second == alias }?.first?.id },
    )

    /**
     * Sends an invocation as an ordinary `m.room.message` carrying the MSC4391 command envelope.
     *
     * A local echo is inserted, unlike for `/poll`: gomuks swallows its own commands and replaces
     * them with the resulting event, but a third-party bot command is a real message that stays in
     * the timeline and renders through its `body`.
     *
     * @return true when the command reached the socket.
     */
    fun sendBotCommand(
        roomId: String,
        command: BotCommand,
        arguments: Map<String, ArgValue>,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
    ): Boolean {
        val body = command.commandFallbackBody(arguments)
        val baseContent = mapOf(
            "msgtype" to "m.text",
            "body" to body,
            MSC4391_COMMAND_KEY to mapOf(
                "command" to command.command,
                "arguments" to arguments.mapValues { it.value.toWireValue() },
            ),
        )

        val requestId = vm.getAndIncrementRequestId()
        val relatesTo = buildRelatesTo(roomId, threadRootEventId, replyToEventId)

        // The echo renders through the body fallback, exactly as the confirmed event will — we add
        // no special rendering for the command envelope, so there is nothing to gain from copying it
        // into the placeholder. The relation travels in insert()'s own parameters below, not here.
        val echoContent = JSONObject()
            .put("msgtype", "m.text")
            .put("body", body)
        vm.localEchoCoordinator.insert(
            roomId,
            requestId,
            "m.room.message",
            echoContent,
            relationType = if (threadRootEventId != null) "m.thread" else null,
            relatesTo = threadRootEventId ?: replyToEventId,
        )

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to "",
            // MSC4391: mention the bot so no other bot picks the command up, and so bots that guard
            // on mentions do not ignore it.
            "mentions" to mapOf("user_ids" to listOf(command.sender), "room" to false),
            "url_previews" to emptyList<Any>(),
        )
        if (relatesTo != null) commandData["relates_to"] = relatesTo

        val result = vm.sendWebSocketCommand("send_message", requestId, commandData)
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "BotCommandCoordinator: failed to send '${command.command}' to ${command.sender} (result=$result)",
            )
            return false
        }

        vm.trackOutgoingRequest(requestId, roomId)
        vm.messageRequests[requestId] = roomId
        vm.pendingSendCount++
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "BotCommandCoordinator: sent '${command.command}' to ${command.sender} in $roomId " +
                    "with ${arguments.size} argument(s), thread=$threadRootEventId",
            )
        }
        return true
    }

    /**
     * The relation for a command sent from a thread or as a reply.
     *
     * A command posted into a thread is never itself a reply to a message, so `is_falling_back` is
     * true: the `m.in_reply_to` inside is a fallback for non-threaded clients, not a real reply.
     */
    private fun buildRelatesTo(roomId: String, threadRootEventId: String?, replyToEventId: String?): Map<String, Any>? = when {
        threadRootEventId != null -> threadRelatesTo(
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId,
            isFallback = true,
        )

        replyToEventId != null -> mapOf("m.in_reply_to" to mapOf("event_id" to replyToEventId))

        else -> null
    }
}
