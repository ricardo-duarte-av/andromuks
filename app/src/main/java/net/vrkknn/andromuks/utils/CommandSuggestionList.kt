package net.vrkknn.andromuks.utils

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Command definition for autocomplete suggestions
 */
data class CommandDefinition(
    val command: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val parameters: List<String> = emptyList() // e.g., ["user_id", "reason"]
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
            parameters = listOf("room_reference", "reason?")
        ),
        CommandDefinition(
            command = "/leave",
            aliases = listOf("/part"),
            description = "Leave the current room"
        ),
        CommandDefinition(
            command = "/invite",
            description = "Invite a user to the current room",
            parameters = listOf("user_id", "reason?")
        ),
        CommandDefinition(
            command = "/kick",
            description = "Kick a user from the current room",
            parameters = listOf("user_id", "reason?")
        ),
        CommandDefinition(
            command = "/ban",
            description = "Ban a user from the current room",
            parameters = listOf("user_id", "reason?")
        ),
        CommandDefinition(
            command = "/myroomnick",
            aliases = listOf("/roomnick"),
            description = "Set your display name in the current room",
            parameters = listOf("name")
        ),
        CommandDefinition(
            command = "/myroomavatar",
            description = "Set your avatar in the current room"
        ),
        CommandDefinition(
            command = "/globalnick",
            aliases = listOf("/globalname"),
            description = "Set your global display name across all rooms",
            parameters = listOf("name")
        ),
        CommandDefinition(
            command = "/globalavatar",
            description = "Set your global avatar across all rooms"
        ),
        // Room state commands
        CommandDefinition(
            command = "/roomname",
            description = "Set the current room name",
            parameters = listOf("name")
        ),
        CommandDefinition(
            command = "/roomavatar",
            description = "Set the current room avatar"
        ),
        CommandDefinition(
            command = "/redact",
            description = "Redact (delete) an event",
            parameters = listOf("event_id", "reason?")
        ),
        // Event sending commands
        CommandDefinition(
            command = "/raw",
            description = "Send encrypted raw timeline event",
            parameters = listOf("event_type", "json?")
        ),
        CommandDefinition(
            command = "/unencryptedraw",
            description = "Send unencrypted raw timeline event",
            parameters = listOf("event_type", "json?")
        ),
        CommandDefinition(
            command = "/rawstate",
            description = "Send raw state event",
            parameters = listOf("event_type", "state_key", "json?")
        ),
        // Room alias commands
        CommandDefinition(
            command = "/alias",
            description = "Manage room aliases",
            parameters = listOf("add|del|create|remove|rm|delete", "name")
        )
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
 */
@Composable
fun CommandSuggestionList(
    query: String,
    onCommandSelected: (CommandDefinition) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = remember(query) {
        Commands.getSuggestions(query)
    }

    if (suggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .widthIn(max = 350.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .height(250.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(suggestions) { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommandSelected(command) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = command.command,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (command.parameters.isNotEmpty()) {
                            Text(
                                text = command.parameters.joinToString(" ") { "{${it.removeSuffix("?")}}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

