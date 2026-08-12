package net.vrkknn.andromuks.utils

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.ui.components.AvatarImage

/**
 * Command definition for autocomplete suggestions
 */
data class CommandDefinition(
    val command: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val parameters: List<String> = emptyList(), // e.g., ["user_id", "reason"]
)

/**
 * All available commands
 */
object Commands {
    val allCommands = listOf(
        // Room membership commands
        CommandDefinition(
            command = "/join",
            description = "Join a room",
            parameters = listOf("room_reference", "reason?"),
        ),
        CommandDefinition(
            command = "/leave",
            aliases = listOf("/part"),
            description = "Leave the current room",
        ),
        CommandDefinition(
            command = "/poll",
            description = "Create a poll",
        ),
        CommandDefinition(
            command = "/invite",
            description = "Invite a user to the current room",
            parameters = listOf("user_id", "reason?"),
        ),
        CommandDefinition(
            command = "/kick",
            description = "Kick a user from the current room",
            parameters = listOf("user_id", "reason?"),
        ),
        CommandDefinition(
            command = "/ban",
            description = "Ban a user from the current room",
            parameters = listOf("user_id", "reason?"),
        ),
        CommandDefinition(
            command = "/myroomnick",
            aliases = listOf("/roomnick"),
            description = "Set your display name in the current room",
            parameters = listOf("name"),
        ),
        CommandDefinition(
            command = "/myroomavatar",
            description = "Set your avatar in the current room",
        ),
        CommandDefinition(
            command = "/globalnick",
            aliases = listOf("/globalname"),
            description = "Set your global display name across all rooms",
            parameters = listOf("name"),
        ),
        CommandDefinition(
            command = "/globalavatar",
            description = "Set your global avatar across all rooms",
        ),
        // Room state commands
        CommandDefinition(
            command = "/roomname",
            description = "Set the current room name",
            parameters = listOf("name"),
        ),
        CommandDefinition(
            command = "/roomavatar",
            description = "Set the current room avatar",
        ),
        CommandDefinition(
            command = "/redact",
            description = "Redact (delete) an event",
            parameters = listOf("event_id", "reason?"),
        ),
        // Event sending commands
        CommandDefinition(
            command = "/raw",
            description = "Send encrypted raw timeline event",
            parameters = listOf("event_type", "json?"),
        ),
        CommandDefinition(
            command = "/unencryptedraw",
            description = "Send unencrypted raw timeline event",
            parameters = listOf("event_type", "json?"),
        ),
        CommandDefinition(
            command = "/rawstate",
            description = "Send raw state event",
            parameters = listOf("event_type", "state_key", "json?"),
        ),
        // Room alias commands
        CommandDefinition(
            command = "/alias",
            description = "Manage room aliases",
            parameters = listOf("add|del|create|remove|rm|delete", "name"),
        ),
        // Direct message (m.direct) account data
        CommandDefinition(
            command = "/converttodm",
            description = "Mark the current room as a DM (m.direct)",
            parameters = listOf("@user:server | [name](https://matrix.to/#/@user:server)?"),
        ),
        CommandDefinition(
            command = "/converttoroom",
            description = "Remove the current room from DMs (m.direct)",
        ),
        CommandDefinition(
            command = "/pmp",
            aliases = listOf("/profile"),
            description = "Pick a per-message profile to send as",
            parameters = listOf("id?", "message?"),
        ),
    )

    /**
     * Get suggestions based on query
     * Filters by command name and aliases only (not description or parameters)
     */
    fun getSuggestions(query: String): List<CommandDefinition> {
        if (query.isBlank()) {
            return allCommands
        }

        val lowerQuery = query.lowercase().trim()
        return allCommands.filter { cmd ->
            // Filter only by command name and aliases
            // Strip leading slash from command name for comparison
            val commandName = cmd.command.removePrefix("/").lowercase()
            val matchesCommand = commandName.startsWith(lowerQuery)
            val matchesAlias = cmd.aliases.any {
                it.removePrefix("/").lowercase().startsWith(lowerQuery)
            }
            matchesCommand || matchesAlias
        }
    }
}

/**
 * Floating suggestion list for `/command` autocomplete.
 *
 * Shows this client's built-in commands first, then any MSC4391 commands the room's bots advertise,
 * separated by a divider. Built-ins come first and shadow colliding bot commands (the filtering
 * happens in [resolveBotCommands]) — the MSC requires that precedence so a bot cannot hijack, say,
 * `/myroomnick`. Bot rows always carry the advertising bot's avatar and MXID, not only when there is
 * a conflict, so it is always visible that the command leaves this client.
 *
 * [botCommands] and its callback default to empty so existing call sites are unaffected.
 */
@Composable
fun CommandSuggestionList(
    query: String,
    onCommandSelected: (CommandDefinition) -> Unit,
    modifier: Modifier = Modifier,
    botCommands: List<BotCommand> = emptyList(),
    onBotCommandSelected: (BotCommand) -> Unit = {},
    botProfileFor: (String) -> Pair<String?, String?> = { null to null },
    homeserverUrl: String = "",
    authToken: String = "",
) {
    val suggestions = remember(query) {
        Commands.getSuggestions(query)
    }
    val botSuggestions = remember(query, botCommands) {
        botCommandSuggestions(botCommands, query)
    }

    if (suggestions.isEmpty() && botSuggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .widthIn(max = 350.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        LazyColumn(
            modifier = Modifier
                .height(250.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(suggestions) { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommandSelected(command) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = command.command,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (command.parameters.isNotEmpty()) {
                            Text(
                                text = command.parameters.joinToString(" ") { "{${it.removeSuffix("?")}}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (suggestions.isNotEmpty() && botSuggestions.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            items(botSuggestions, key = { it.stateKey }) { command ->
                val (avatarUrl, displayName) = botProfileFor(command.sender)
                BotCommandSuggestionRow(
                    command = command,
                    botAvatarUrl = avatarUrl,
                    botDisplayName = displayName,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    onClick = { onBotCommandSelected(command) },
                )
            }
        }
    }
}

/**
 * One bot-advertised command in the suggestion list.
 *
 * The description and signature come from an arbitrary room member, so both are clamped to a single
 * line and rendered as plain text — never as HTML.
 */
@Composable
private fun BotCommandSuggestionRow(
    command: BotCommand,
    botAvatarUrl: String?,
    botDisplayName: String?,
    homeserverUrl: String,
    authToken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarImage(
            mxcUrl = botAvatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (botDisplayName ?: command.sender).take(1),
            size = 24.dp,
            userId = command.sender,
            displayName = botDisplayName,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "/${command.command}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            if (command.parameters.isNotEmpty()) {
                Text(
                    text = command.displaySignature,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = command.description.ifBlank { botDisplayName ?: command.sender },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
