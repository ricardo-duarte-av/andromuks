package net.vrkknn.andromuks.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.vrkknn.andromuks.VersionedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog showing the complete edit history of a message.
 * Displays all versions from newest to oldest with timestamps.
 */
@Composable
fun EditHistoryDialog(
    versioned: VersionedMessage,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Message History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close"
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "${versioned.versions.size} version${if (versioned.versions.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Version list (newest first)
                LazyColumn {
                    itemsIndexed(versioned.versions) { index, version ->
                        VersionItem(
                            version = version,
                            versionNumber = versioned.versions.size - index,
                            isLatest = index == 0,
                            isOriginal = version.isOriginal
                        )
                        
                        // Divider between versions (except after last item)
                        if (index < versioned.versions.size - 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

/**
 * Individual version item showing timestamp and content
 */
@Composable
private fun VersionItem(
    version: net.vrkknn.andromuks.MessageVersion,
    versionNumber: Int,
    isLatest: Boolean,
    isOriginal: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Version header with label and timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Version label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        isOriginal && isLatest -> "Original (Current)"
                        isOriginal -> "Original"
                        isLatest -> "Latest Edit"
                        else -> "Edit #${versionNumber - 1}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                
                if (isLatest && !isOriginal) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Current",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            // Timestamp
            Text(
                text = formatFullTimestamp(version.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Message content
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isLatest) 
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = extractBodyFromVersion(version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Extract message body from a version event
 */
private fun extractBodyFromVersion(version: net.vrkknn.andromuks.MessageVersion): String {
    val event = version.event
    
    return when {
        // For edit events, use m.new_content
        version.event.content?.has("m.new_content") == true -> {
            val newContent = version.event.content?.optJSONObject("m.new_content")
            newContent?.optString("body", "[No content]") ?: "[No content]"
        }
        
        // For encrypted messages
        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
            // Check if it's an edit
            if (event.decrypted?.has("m.new_content") == true) {
                val newContent = event.decrypted?.optJSONObject("m.new_content")
                newContent?.optString("body", "[No content]") ?: "[No content]"
            } else {
                event.decrypted?.optString("body", "[No content]") ?: "[No content]"
            }
        }
        
        // For regular messages
        event.type == "m.room.message" -> {
            event.content?.optString("body", "[No content]") ?: "[No content]"
        }
        
        else -> "[Unsupported message type]"
    }
}

/**
 * Format timestamp to full date/time format
 */
private fun formatFullTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    return formatter.format(date)
}

