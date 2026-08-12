package net.vrkknn.andromuks.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The command signature shown above the composer while a bot command is being typed.
 *
 * One chip per parameter, so the user can see what the command expects, what has been filled in and
 * what is still wrong — the feedback MSC4391 exists to make possible. The chip for the parameter
 * under the cursor is emphasised, which is what turns a shell-like line into something you can
 * follow while typing.
 *
 * Tapping anywhere on the strip opens the argument sheet, so the typed syntax is never a dead end
 * for someone who does not know it.
 */
@Composable
fun BotCommandSignatureStrip(
    invocation: ParsedInvocation,
    botAvatarUrl: String?,
    botDisplayName: String?,
    homeserverUrl: String,
    authToken: String,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val command = invocation.command
    val scrollState = remember(command.stateKey) { ScrollState(0) }

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onOpenSheet),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvatarImage(
                    mxcUrl = botAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = (botDisplayName ?: command.sender).take(1),
                    size = 20.dp,
                    userId = command.sender,
                    displayName = botDisplayName,
                )
                Text(
                    text = "/${command.command}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = botDisplayName ?: command.sender,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (command.parameters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    command.parameters.forEach { parameter ->
                        ParameterChip(
                            parameter = parameter,
                            value = invocation.arguments[parameter.key],
                            hasError = invocation.errors.containsKey(parameter.key),
                            isActive = invocation.activeParamKey == parameter.key,
                        )
                    }
                }
            }

            // The description of the parameter being typed is more useful than the command's own,
            // which the suggestion list already showed.
            val activeDescription = command.parameters
                .firstOrNull { it.key == invocation.activeParamKey }
                ?.description
                ?.takeIf { it.isNotBlank() }
            if (activeDescription != null) {
                Text(
                    text = activeDescription,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One parameter chip.
 *
 * Four states, deliberately distinguishable without relying on colour alone: filled with the bound
 * value, outlined and showing the parameter name while still pending, error-tinted when the typed
 * text does not fit the declared type, and bold when the cursor is inside it.
 */
@Composable
private fun ParameterChip(
    parameter: BotCommandParameter,
    value: ArgValue?,
    hasError: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        hasError -> colors.errorContainer
        value != null -> colors.primaryContainer
        else -> colors.surfaceVariant
    }
    val foreground = when {
        hasError -> colors.onErrorContainer
        value != null -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    val label = when {
        hasError || value == null -> if (parameter.optional) "[${parameter.key}]" else parameter.key
        else -> value.displayText().ifBlank { parameter.key }
    }

    Text(
        text = label,
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        ),
        color = foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
