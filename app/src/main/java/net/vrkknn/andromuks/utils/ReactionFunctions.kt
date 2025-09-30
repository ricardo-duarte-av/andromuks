package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.MessageReaction

/**
 * Displays reaction badges for a message showing emoji reactions and their counts.
 * 
 * This function renders a horizontal row of reaction badges, where each badge shows
 * an emoji and optionally a count if more than one user reacted with the same emoji.
 * The badges are styled with rounded corners and surface variant background.
 * 
 * @param eventId The ID of the message event these reactions belong to
 * @param reactions List of MessageReaction objects containing emoji and count data
 * @param modifier Modifier to apply to the reaction badges container
 */
@Composable
fun ReactionBadges(
    eventId: String,
    reactions: List<MessageReaction>,
    modifier: Modifier = Modifier
) {
    if (reactions.isNotEmpty()) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { reaction ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = reaction.emoji,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f
                            )
                        )
                        if (reaction.count > 1) {
                            Text(
                                text = reaction.count.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.7f
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
