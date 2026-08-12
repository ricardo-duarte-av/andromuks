package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BotCommandCache

/**
 * MSC4391 bot commands in the composer — everything the three timeline screens share.
 *
 * RoomTimelineScreen, BubbleTimelineScreen and ThreadViewerScreen each carry their own copy of the
 * composer, so anything screen-specific here would have to be written three times and kept in sync.
 * The whole feature is therefore reduced to one state object and one overlay composable, leaving
 * each screen a five-line diff.
 */

/** What the send button should do after offering the draft to the bot-command machinery. */
enum class BotCommandSendOutcome {
    /** Not a bot command; carry on with the normal send path. */
    NOT_A_BOT_COMMAND,

    /** Sent. Clear the composer. */
    SENT,

    /** Arguments were missing or invalid; the sheet is now open. Clear nothing, send nothing. */
    OPENED_SHEET,
}

/**
 * Live bot-command state for one room's composer.
 *
 * Holds the current invocation parsed from the draft (which drives the signature strip) and whether
 * the argument sheet is open. Construct it with [rememberComposerCommandState].
 */
@Stable
class ComposerCommandState internal constructor(
    private val appViewModel: AppViewModel,
    private val roomId: String,
) {
    /**
     * Resolved, filtered commands for this room, refreshed by [rememberComposerCommandState].
     *
     * Snapshot-backed so this class honours its `@Stable` contract: the overlay reads it, and a
     * plain `var` would let a bot's newly registered command go unrendered until something else
     * happened to recompose.
     */
    internal var botCommands: List<BotCommand> by mutableStateOf(emptyList())

    /** Word sequences of multi-word commands, for [detectCommandQuery]. */
    val multiWordPrefixes: Set<List<String>> get() = multiWordPrefixesOf(botCommands)

    /** The command the draft currently invokes, with its arguments bound. Null when it invokes none. */
    var invocation: ParsedInvocation? by mutableStateOf(null)
        private set

    /** The command whose argument sheet is open, or null. */
    var sheetCommand: BotCommand? by mutableStateOf(null)
        private set

    private var sheetInitial: Map<String, ArgValue> = emptyMap()
    private var pendingThreadRootEventId: String? = null
    private var pendingReplyToEventId: String? = null

    internal fun coercionContext(): CoercionContext = appViewModel.botCommandCoordinator.coercionContext(roomId)

    /** The avatar URL and display name to show for a bot, from whichever profile cache has it. */
    fun botProfile(userId: String): Pair<String?, String?> {
        val profile = appViewModel.getMemberProfile(roomId, userId)
        return profile?.avatarUrl to profile?.displayName
    }

    /** Call from `onValueChange`, right after the draft is updated. */
    fun onDraftChanged(text: String, cursor: Int) {
        invocation = if (botCommands.isEmpty()) {
            null
        } else {
            matchBotCommand(botCommands, text, cursor, coercionContext())
        }
    }

    /**
     * Builds the composer contents for a command picked from the suggestion list.
     *
     * A command with parameters gets a trailing space so the user can type straight into the first
     * one; a command with none is complete as it stands.
     */
    fun onBotCommandSelected(command: BotCommand): TextFieldValue {
        val text = if (command.parameters.isEmpty()) "/${command.command}" else "/${command.command} "
        onDraftChanged(text, text.length)
        return TextFieldValue(text = text, selection = TextRange(text.length))
    }

    /** Opens the argument sheet for the command currently being typed, prefilled from the draft. */
    fun openSheet(threadRootEventId: String?, replyToEventId: String?) {
        val current = invocation ?: return
        sheetInitial = current.arguments
        pendingThreadRootEventId = threadRootEventId
        pendingReplyToEventId = replyToEventId
        sheetCommand = current.command
    }

    fun dismissSheet() {
        sheetCommand = null
    }

    internal fun sheetInitialArguments(): Map<String, ArgValue> = sheetInitial

    /**
     * Offers the draft to the bot-command machinery on send.
     *
     * A complete invocation is sent as-is. An incomplete one opens the sheet rather than failing:
     * the point of the MSC is that the user finds out what is missing before the bot has to tell
     * them. Anything that is not a bot command is handed back untouched.
     */
    fun consumeSend(draft: String, threadRootEventId: String?, replyToEventId: String?): BotCommandSendOutcome {
        if (botCommands.isEmpty()) return BotCommandSendOutcome.NOT_A_BOT_COMMAND
        val parsed = matchBotCommand(botCommands, draft, draft.length, coercionContext())
            ?: return BotCommandSendOutcome.NOT_A_BOT_COMMAND

        if (!parsed.isComplete) {
            invocation = parsed
            openSheet(threadRootEventId, replyToEventId)
            return BotCommandSendOutcome.OPENED_SHEET
        }

        send(parsed.command, parsed.arguments, threadRootEventId, replyToEventId)
        return BotCommandSendOutcome.SENT
    }

    internal fun send(
        command: BotCommand,
        arguments: Map<String, ArgValue>,
        threadRootEventId: String?,
        replyToEventId: String?,
    ) {
        appViewModel.botCommandCoordinator.sendBotCommand(
            roomId = roomId,
            command = command,
            arguments = arguments,
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId,
        )
        invocation = null
        sheetCommand = null
    }

    internal fun submitSheet(arguments: Map<String, ArgValue>) {
        val command = sheetCommand ?: return
        send(command, arguments, pendingThreadRootEventId, pendingReplyToEventId)
    }
}

/**
 * Remembers the composer's bot-command state for [roomId].
 *
 * Also closes the one gap left by not persisting the command index: a cold-started bubble or
 * shortcut Activity may open a room the startup `get_room_state` sweep has not reached, so room
 * state is requested on mount when the room has never been indexed. `requestRoomState` de-duplicates
 * in-flight requests, so this is cheap even when the sweep is already covering the room.
 */
@Composable
fun rememberComposerCommandState(appViewModel: AppViewModel, roomId: String): ComposerCommandState {
    val state = remember(roomId) { ComposerCommandState(appViewModel, roomId) }

    // Reading the cache subscribes this composition to it; memberUpdateCounter covers the joined
    // filter, which is backed by a plain (non-snapshot) cache.
    val rawCommands = BotCommandCache.rawCommandsFor(roomId)
    val memberCounter = appViewModel.memberUpdateCounter
    state.botCommands = remember(roomId, rawCommands, memberCounter) {
        appViewModel.botCommandCoordinator.commandsForComposer(roomId)
    }

    LaunchedEffect(roomId) {
        if (!BotCommandCache.isIndexed(roomId)) appViewModel.requestRoomState(roomId)
    }
    return state
}

/**
 * The signature strip and argument sheet, positioned like the other composer overlays.
 *
 * Both live in the screen's root `Box`, anchored just above the input bar exactly as the
 * per-message-profile picker and the suggestion lists do. Only one is ever shown: while the sheet
 * is open it replaces the strip, so they never stack.
 *
 * @param onSent invoked after a command is sent from the sheet, so the screen can clear its draft.
 */
@Composable
fun BoxScope.ComposerBotCommandOverlays(
    state: ComposerCommandState,
    appViewModel: AppViewModel,
    threadRootEventId: String?,
    replyToEventId: String?,
    onSent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetCommand = state.sheetCommand
    val invocation = state.invocation

    Box(
        modifier = modifier
            .align(Alignment.BottomStart)
            .padding(start = 72.dp, bottom = 60.dp, end = 12.dp)
            .navigationBarsPadding()
            .imePadding()
            .zIndex(9f),
    ) {
        if (sheetCommand != null) {
            BotCommandArgumentSheet(
                command = sheetCommand,
                initial = state.sheetInitialArguments(),
                coercionContext = state.coercionContext(),
                onDismiss = { state.dismissSheet() },
                onSubmit = { arguments ->
                    state.submitSheet(arguments)
                    onSent()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(10f),
            )
        } else if (invocation != null) {
            val (avatarUrl, displayName) = state.botProfile(invocation.command.sender)
            BotCommandSignatureStrip(
                invocation = invocation,
                botAvatarUrl = avatarUrl,
                botDisplayName = displayName,
                homeserverUrl = appViewModel.homeserverUrl,
                authToken = appViewModel.authToken,
                onOpenSheet = { state.openSheet(threadRootEventId, replyToEventId) },
                modifier = Modifier.zIndex(10f),
            )
        }
    }
}
