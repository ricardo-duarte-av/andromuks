package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.AvatarImage

@Composable
fun SystemEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel? = null,
    roomId: String,
    onUserClick: (String) -> Unit = {}
) {
    val content = event.content
    val eventType = event.type
    
    // Get read receipts for this event
    val readReceipts = remember(event.eventId, appViewModel?.readReceiptsUpdateCounter) {
        if (appViewModel != null) {
            ReceiptFunctions.getReadReceipts(
                event.eventId,
                appViewModel.getReadReceiptsMap()
            )
        } else {
            emptyList()
        }
    }
    
    // Get member map for user profiles (for read receipt avatars)
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Small avatar for the actor (clickable)
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp,
                userId = event.sender,
                displayName = displayName,
                modifier = Modifier.clickable { onUserClick(event.sender) }
            )
        
        // Narrator text
        when (eventType) {
            "m.room.member" -> {
                val membership = content?.optString("membership", "")
                val targetDisplayName = content?.optString("displayname", "")
                val reason = content?.optString("reason", "")
                
                when (membership) {
                    "join" -> {
                        // Check if this is a profile change vs actual join
                        // Access unsigned.prev_content to check previous state
                        val unsigned = event.unsigned
                        val prevContent = unsigned?.optJSONObject("prev_content")
                        
                        // Check if prev_content exists and has membership "join"
                        // If prev_content.membership == "join", this is a profile change, not a true join
                        val prevMembership = prevContent?.optString("membership", "")
                        val isProfileChange = prevContent != null && prevMembership == "join"
                        
                        if (isProfileChange) {
                            val prevDisplayName = prevContent.optString("displayname", "")
                            val prevAvatar = prevContent.optString("avatar_url", "")
                            val currentDisplayName = content?.optString("displayname", "") ?: ""
                            val currentAvatar = content?.optString("avatar_url", "") ?: ""
                            
                            // Check if content matches prev_content exactly (rare case)
                            val contentMatches = prevDisplayName == currentDisplayName && 
                                                prevAvatar == currentAvatar
                            
                            if (contentMatches) {
                                // Content matches prev_content - no change
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" made no change")
                                    }
                                )
                            } else {
                                // Detect profile changes including removals
                                val displayNameRemoved = !prevDisplayName.isNullOrEmpty() && currentDisplayName.isNullOrEmpty()
                                val displayNameChanged = !prevDisplayName.isNullOrEmpty() && 
                                                         !currentDisplayName.isNullOrEmpty() && 
                                                         prevDisplayName != currentDisplayName
                                val displayNameAdded = prevDisplayName.isNullOrEmpty() && !currentDisplayName.isNullOrEmpty()
                                
                                val avatarRemoved = !prevAvatar.isNullOrEmpty() && currentAvatar.isNullOrEmpty()
                                val avatarChanged = !prevAvatar.isNullOrEmpty() && 
                                                    !currentAvatar.isNullOrEmpty() && 
                                                    prevAvatar != currentAvatar
                                val avatarAdded = prevAvatar.isNullOrEmpty() && !currentAvatar.isNullOrEmpty()
                                
                                when {
                                    displayNameRemoved && avatarRemoved -> {
                                        // Both display name and avatar removed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" removed their display name and avatar")
                                            }
                                        )
                                    }
                                    displayNameRemoved && avatarChanged -> {
                                        // Display name removed, avatar changed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" removed their display name and changed their avatar")
                                            }
                                        )
                                    }
                                    displayNameRemoved && avatarAdded -> {
                                        // Display name removed, avatar added
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" removed their display name and set their avatar")
                                            }
                                        )
                                    }
                                    displayNameRemoved -> {
                                        // Only display name removed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" removed their display name")
                                            }
                                        )
                                    }
                                    displayNameChanged && avatarRemoved -> {
                                        // Display name changed, avatar removed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" changed their display name and removed their avatar")
                                            }
                                        )
                                    }
                                    displayNameChanged && avatarChanged -> {
                                        // Both display name and avatar change
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" changed their profile")
                                            }
                                        )
                                    }
                                    displayNameChanged && avatarAdded -> {
                                        // Display name changed, avatar added
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" changed their display name and set their avatar")
                                            }
                                        )
                                    }
                                    displayNameChanged -> {
                                        // Display name change
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" changed their display name")
                                            }
                                        )
                                    }
                                    displayNameAdded && avatarRemoved -> {
                                        // Display name added, avatar removed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" set their display name and removed their avatar")
                                            }
                                        )
                                    }
                                    displayNameAdded && avatarChanged -> {
                                        // Display name added, avatar changed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" set their display name and changed their avatar")
                                            }
                                        )
                                    }
                                    displayNameAdded && avatarAdded -> {
                                        // Both display name and avatar added
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" set their display name and avatar")
                                            }
                                        )
                                    }
                                    displayNameAdded -> {
                                        // Display name added
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" set their display name")
                                            }
                                        )
                                    }
                                    avatarRemoved -> {
                                        // Avatar removed
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" removed their avatar")
                                            }
                                        )
                                    }
                                    avatarChanged -> {
                                        // Avatar change
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" changed their avatar")
                                            }
                                        )
                                    }
                                    avatarAdded -> {
                                        // Avatar added
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" set their avatar")
                                            }
                                        )
                                    }
                                    else -> {
                                        // No profile changes detected, but membership was already join
                                        // This shouldn't normally happen, but treat as join event
                                        NarratorText(
                                            text = buildAnnotatedString {
                                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(displayName)
                                                }
                                                append(" joined the room")
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            // Previous membership was "leave" or null/absent - this is a true join
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" joined the room")
                                }
                            )
                        }
                    }
                    "leave" -> {
                        // Check if this is a true leave (previous membership was "join")
                        val unsigned = event.unsigned
                        val prevContent = unsigned?.optJSONObject("prev_content")
                        val prevMembership = prevContent?.optString("membership", "")
                        
                        // Only show "left the room" if previous membership was "join"
                        if (prevMembership == "join") {
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" left the room")
                                    // Safe null check for reason string (was causing compilation error)
                                    if (!reason.isNullOrEmpty()) {
                                        append(": $reason")
                                    }
                                }
                            )
                        } else {
                            // Previous membership was not "join" - might be a different scenario
                            // Fallback to generic message or skip
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" left the room")
                                    if (!reason.isNullOrEmpty()) {
                                        append(": $reason")
                                    }
                                }
                            )
                        }
                    }
                    "invite" -> {
                        // state_key is on the event itself, not in content!
                        val invitedUserId = event.stateKey
        // Get invited user's display name and avatar using getUserProfile
        // This checks room cache, current user profile, AND global profile cache
        val invitedProfile = invitedUserId?.let { userId ->
            appViewModel?.getUserProfile(userId, roomId)
        }
        val invitedDisplayName = invitedProfile?.displayName
        val invitedAvatarUrl = invitedProfile?.avatarUrl
                        
                        // Request profile asynchronously if not found
                        if (invitedDisplayName == null && appViewModel != null && invitedUserId != null) {
                            appViewModel.requestUserProfile(invitedUserId, roomId)
                        }
                        
                        // Use a single Row with inline elements for better text flow
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" invited ")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                            
                            // Invited user avatar (inline)
                            AvatarImage(
                                mxcUrl = invitedAvatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                fallbackText = invitedDisplayName ?: invitedUserId ?: "?",
                                size = 16.dp,
                                userId = invitedUserId,
                                displayName = invitedDisplayName
                            )
                            
                            // Invitee name and reason (inline, no extra wrapping)
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(invitedDisplayName ?: invitedUserId ?: "Unknown")
                                    }
                                    // Safe null check for reason string
                                    if (!reason.isNullOrEmpty()) {
                                        append(" for $reason")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic,
                                maxLines = Int.MAX_VALUE
                            )
                        }
                    }
                    "ban" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" banned ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(targetDisplayName)
                                }
                                // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                    "kick" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" kicked ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(targetDisplayName)
                                }
                                // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                }
            }
            "m.room.name" -> {
                val roomName = content?.optString("name", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room name to ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(roomName)
                        }
                    }
                )
            }
            "m.room.topic" -> {
                val topic = content?.optString("topic", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room topic")
                    }
                )
            }
            "m.room.avatar" -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room avatar")
                    }
                )
            }
            "m.room.pinned_events" -> {
                val pinnedArray = content?.optJSONArray("pinned")
                val unsigned = event.unsigned
                val prevContent = unsigned?.optJSONObject("prev_content")
                val prevPinnedArray = prevContent?.optJSONArray("pinned")
                
                // Convert arrays to sets for easier comparison
                val currentPinned = pinnedArray?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
                } ?: emptySet()
                
                val previousPinned = prevPinnedArray?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
                } ?: emptySet()
                
                // Find newly pinned events (in current but not in previous)
                val newlyPinned = currentPinned - previousPinned
                
                // Find unpinned events (in previous but not in current)
                val unpinned = previousPinned - currentPinned
                
                // Handle the changes
                when {
                    newlyPinned.isNotEmpty() && unpinned.isEmpty() -> {
                        // Only new pins, no unpins
                        if (newlyPinned.size == 1) {
                            // Single event pinned
                            PinnedEventNarration(
                                displayName = displayName,
                                avatarUrl = avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                pinnedEventId = newlyPinned.first(),
                                appViewModel = appViewModel,
                                roomId = roomId,
                                onUserClick = onUserClick
                            )
                        } else {
                            // Multiple events pinned
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" pinned ${newlyPinned.size} events")
                                }
                            )
                        }
                    }
                    unpinned.isNotEmpty() && newlyPinned.isEmpty() -> {
                        // Only unpins, no new pins
                        if (unpinned.size == 1) {
                            // Single event unpinned - show which event
                            UnpinnedEventNarration(
                                displayName = displayName,
                                avatarUrl = avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                unpinnedEventId = unpinned.first(),
                                appViewModel = appViewModel,
                                roomId = roomId,
                                onUserClick = onUserClick
                            )
                        } else {
                            // Multiple events unpinned
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" unpinned ${unpinned.size} events")
                                }
                            )
                        }
                    }
                    newlyPinned.isNotEmpty() && unpinned.isNotEmpty() -> {
                        // Both pins and unpins
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" updated pinned events")
                            }
                        )
                    }
                    else -> {
                        // No changes detected (shouldn't happen, but fallback)
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" updated pinned events")
                            }
                        )
                    }
                }
            }
            "m.room.server_acl" -> {
                val denyArray = content?.optJSONArray("deny")
                val unsigned = event.unsigned
                val prevContent = unsigned?.optJSONObject("prev_content")
                val prevDenyArray = prevContent?.optJSONArray("deny")
                
                // Convert arrays to sets for easier comparison
                val currentDeny = denyArray?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
                } ?: emptySet()
                
                val previousDeny = prevDenyArray?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
                } ?: emptySet()
                
                // Check if this is an initial setup (no prev_content)
                val isInitialSetup = prevContent == null
                
                // Find newly added servers (in current but not in previous)
                val newlyAdded = currentDeny - previousDeny
                
                // Find removed servers (in previous but not in current)
                val removed = previousDeny - currentDeny
                
                // Handle the changes
                when {
                    isInitialSetup -> {
                        // First ACL setup - treat all current servers as "added"
                        if (currentDeny.size == 1 && currentDeny.first() == "*") {
                            // Special case: "*" means all servers banned
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" banned all servers from participating in the room")
                                }
                            )
                        } else if (currentDeny.isEmpty()) {
                            // Empty deny list - all servers allowed (shouldn't normally happen, but handle it)
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" set ACL List (all servers allowed)")
                                }
                            )
                        } else {
                            // Multiple servers in initial setup
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" added ${currentDeny.size} servers to the ACL list")
                                }
                            )
                        }
                    }
                    newlyAdded.isNotEmpty() && removed.isEmpty() -> {
                        // Only additions, no removals
                        if (newlyAdded.size == 1) {
                            val server = newlyAdded.first()
                            // Special case: "*" means all servers banned
                            if (server == "*") {
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" banned all servers from participating in the room")
                                    }
                                )
                            } else {
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" added server ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(server)
                                        }
                                        append(" to the ACL List")
                                    }
                                )
                            }
                        } else {
                            // Multiple servers added
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" added ${newlyAdded.size} servers to the ACL list")
                                }
                            )
                        }
                    }
                    removed.isNotEmpty() && newlyAdded.isEmpty() -> {
                        // Only removals, no additions
                        if (removed.size == 1) {
                            val server = removed.first()
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" removed server ")
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(server)
                                    }
                                    append(" from the ACL List")
                                }
                            )
                        } else {
                            // Multiple servers removed
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" removed ${removed.size} servers from the ACL list")
                                }
                            )
                        }
                    }
                    newlyAdded.isNotEmpty() && removed.isNotEmpty() -> {
                        // Both additions and removals
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" added ${newlyAdded.size} server${if (newlyAdded.size == 1) "" else "s"} to the ACL list")
                                append(", and removed ${removed.size} server${if (removed.size == 1) "" else "s"}")
                            }
                        )
                    }
                    else -> {
                        // No changes detected (shouldn't happen, but fallback)
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" updated ACL List")
                            }
                        )
                    }
                }
            }
            "m.room.power_levels" -> {
                val unsigned = event.unsigned
                val prevContent = unsigned?.optJSONObject("prev_content")
                
                // Extract current and previous users
                val currentUsers = content?.optJSONObject("users") ?: org.json.JSONObject()
                val previousUsers = prevContent?.optJSONObject("users") ?: org.json.JSONObject()
                
                // Get user IDs from both
                val currentUserIds = currentUsers.keys().asSequence().toSet()
                val previousUserIds = previousUsers.keys().asSequence().toSet()
                
                // Find user changes
                val addedUsers = currentUserIds - previousUserIds
                val removedUsers = previousUserIds - currentUserIds
                val changedUsers = currentUserIds.intersect(previousUserIds).filter { userId ->
                    currentUsers.optInt(userId, -1) != previousUsers.optInt(userId, -1)
                }
                
                // Check if room power levels changed (everything except users)
                val roomPowerLevelKeys = setOf(
                    "ban", "kick", "redact", "invite", "historical",
                    "events_default", "state_default", "users_default"
                )
                
                val currentEvents = content?.optJSONObject("events") ?: org.json.JSONObject()
                val previousEvents = prevContent?.optJSONObject("events") ?: org.json.JSONObject()
                
                val currentEventKeys = currentEvents.keys().asSequence().toSet()
                val previousEventKeys = previousEvents.keys().asSequence().toSet()
                
                // Check for changes in room power level settings
                val changedRoomSettings = mutableListOf<String>()
                
                // Check top-level settings
                for (key in roomPowerLevelKeys) {
                    val currentValue = content?.optInt(key, -1)
                    val previousValue = prevContent?.optInt(key, -1)
                    if (currentValue != previousValue && (currentValue != -1 || previousValue != -1)) {
                        changedRoomSettings.add(key)
                    }
                }
                
                // Check event-specific power levels
                val changedEventKeys = (currentEventKeys + previousEventKeys).filter { eventKey ->
                    val currentValue = currentEvents.optInt(eventKey, -1)
                    val previousValue = previousEvents.optInt(eventKey, -1)
                    currentValue != previousValue
                }
                
                val hasUserChanges = addedUsers.isNotEmpty() || removedUsers.isNotEmpty() || changedUsers.isNotEmpty()
                val hasRoomChanges = changedRoomSettings.isNotEmpty() || changedEventKeys.isNotEmpty()
                
                // Handle the changes
                when {
                    hasUserChanges && !hasRoomChanges -> {
                        // Only user power level changes
                        when {
                            changedUsers.size == 1 && addedUsers.isEmpty() && removedUsers.isEmpty() -> {
                                // Single user power level changed
                                val userId = changedUsers.first()
                                val newLevel = currentUsers.optInt(userId)
                                
                                // Request profile if not available
                                if (appViewModel != null) {
                                    appViewModel.requestUserProfile(userId, roomId)
                                }
                                
                                val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName
                                    ?: userId
                                
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" set user ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(userDisplayName)
                                        }
                                        append(" power level to $newLevel")
                                    }
                                )
                            }
                            removedUsers.size == 1 && addedUsers.isEmpty() && changedUsers.isEmpty() -> {
                                // Single user removed (set to default)
                                val userId = removedUsers.first()
                                
                                // Request profile if not available
                                if (appViewModel != null) {
                                    appViewModel.requestUserProfile(userId, roomId)
                                }
                                
                                val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName
                                    ?: userId
                                
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" set user ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(userDisplayName)
                                        }
                                        append(" power level to the default")
                                    }
                                )
                            }
                            addedUsers.size == 1 && removedUsers.isEmpty() && changedUsers.isEmpty() -> {
                                // Single user added
                                val userId = addedUsers.first()
                                val newLevel = currentUsers.optInt(userId)
                                
                                // Request profile if not available
                                if (appViewModel != null) {
                                    appViewModel.requestUserProfile(userId, roomId)
                                }
                                
                                val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName
                                    ?: userId
                                
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" set user ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(userDisplayName)
                                        }
                                        append(" power level to $newLevel")
                                    }
                                )
                            }
                            else -> {
                                // Multiple user changes
                                val totalChanges = changedUsers.size + addedUsers.size + removedUsers.size
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" changed $totalChanges user power level${if (totalChanges == 1) "" else "s"}")
                                    }
                                )
                            }
                        }
                    }
                    hasRoomChanges && !hasUserChanges -> {
                        // Only room power level changes
                        when {
                            changedEventKeys.size == 1 && changedRoomSettings.isEmpty() -> {
                                // Single event power level changed
                                val eventKey = changedEventKeys.first()
                                val newLevel = currentEvents.optInt(eventKey)
                                
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" set room ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(eventKey)
                                        }
                                        append(" powerlevel to $newLevel")
                                    }
                                )
                            }
                            changedRoomSettings.size == 1 && changedEventKeys.isEmpty() -> {
                                // Single room setting changed
                                val settingKey = changedRoomSettings.first()
                                val newLevel = content?.optInt(settingKey) ?: 0
                                
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" set room ")
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(settingKey)
                                        }
                                        append(" powerlevel to $newLevel")
                                    }
                                )
                            }
                            else -> {
                                // Multiple room power level changes
                                val totalChanges = changedRoomSettings.size + changedEventKeys.size
                                NarratorText(
                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(displayName)
                                        }
                                        append(" changed room power levels")
                                    }
                                )
                            }
                        }
                    }
                    hasUserChanges && hasRoomChanges -> {
                        // Both user and room changes
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" changed power levels")
                            }
                        )
                    }
                    prevContent == null -> {
                        // Initial setup
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" set room power levels")
                            }
                        )
                    }
                    else -> {
                        // No changes detected (shouldn't happen, but fallback)
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" updated power levels")
                            }
                        )
                    }
                }
            }
            else -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" performed an action")
                    }
                )
            }
        }
        }
        
        // Right side - read receipts (if any) + timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Read receipts (left of timestamp)
            if (readReceipts.isNotEmpty()) {
                InlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = memberMap,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    roomId = roomId,
                    onUserClick = onUserClick
                )
            }
            
            // Timestamp
            Text(
                text = formatSmartTimestamp(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}


/**
 * Simple narrator text composable for displaying system event messages.
 * 
 * This function renders annotated text with italic styling for system events
 * like user joins, leaves, room name changes, etc. Used by the system event
 * narrator to display formatted text about room activities.
 * 
 * @param text The annotated text to display with formatting
 */
@Composable
fun NarratorText(
    text: AnnotatedString
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic
    )
}



private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}

/**
 * Smart time formatting: shows time for today, date and time for other days
 */
private fun formatSmartTimestamp(timestamp: Long): String {
    val eventDate = java.util.Date(timestamp)
    val today = java.util.Date()
    
    val eventCalendar = java.util.Calendar.getInstance()
    val todayCalendar = java.util.Calendar.getInstance()
    
    eventCalendar.time = eventDate
    todayCalendar.time = today
    
    val isToday = eventCalendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR) &&
                  eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    return if (isToday) {
        // Show time for today
        val timeFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        timeFormatter.format(eventDate)
    } else {
        // Show date and time for other days
        val dateTimeFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        dateTimeFormatter.format(eventDate)
    }
}

/**
 * Renders an emote message (m.emote) in narrator style.
 * Format: "[Display name] [action text]"
 * Example: "Alice really likes onions"
 */
@Composable
fun EmoteEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onUserClick: (String) -> Unit = {}
) {
    // For encrypted messages, prioritize decrypted content, otherwise use regular content
    val content = if (event.type == "m.room.encrypted" && event.decrypted != null) {
        event.decrypted
    } else {
        event.content
    }
    val body = content?.optString("body", "") ?: ""
    
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(
                onClick = { showMenu = true },
                onClickLabel = "Show message options"
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Small avatar for the actor (clickable)
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp,
                userId = event.sender,
                displayName = displayName,
                modifier = Modifier.clickable { onUserClick(event.sender) }
            )
            
            // Emote text
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" ")
                    append(body)
                }
            )
        }
        
        // Right side - timestamp
        Text(
            text = formatSmartTimestamp(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
    
    // Context menu for emote messages
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("Message Options") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showMenu = false
                            onReply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reply")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onReact()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("React")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Edit")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    pinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<TimelineEvent?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get member map that observes memberUpdateCounter for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" pinned an event: ")
            }
        )

        Text(
            text = pinnedEventId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                if (event == null && !isLoading && appViewModel != null) {
                    isLoading = true
                    errorMessage = null
                    appViewModel.getEvent(roomId, pinnedEventId) { fetchedEvent ->
                        event = fetchedEvent
                        if (fetchedEvent == null) {
                            errorMessage = "Unable to load event (404)."
                        }
                        isLoading = false
                        showDialog = true
                    }
                } else {
                    showDialog = true
                }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = { Text("Pinned Event") },
            text = {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }
                    event != null -> {
                        TimelineEventItem(
                            event = event!!,
                            timelineEvents = listOf(event!!),
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            userProfileCache = memberMap,
                            isMine = false,
                            myUserId = appViewModel?.currentUserId,
                            onUserClick = onUserClick
                        )
                    }
                    else -> {
                        Text("Event data not available.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun UnpinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    unpinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<TimelineEvent?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get member map that observes memberUpdateCounter for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" unpinned an event: ")
            }
        )

        Text(
            text = unpinnedEventId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                if (event == null && !isLoading && appViewModel != null) {
                    isLoading = true
                    errorMessage = null
                    appViewModel.getEvent(roomId, unpinnedEventId) { fetchedEvent ->
                        event = fetchedEvent
                        if (fetchedEvent == null) {
                            errorMessage = "Unable to load event (404)."
                        }
                        isLoading = false
                        showDialog = true
                    }
                } else {
                    showDialog = true
                }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = { Text("Unpinned Event") },
            text = {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }
                    event != null -> {
                        TimelineEventItem(
                            event = event!!,
                            timelineEvents = listOf(event!!),
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            userProfileCache = memberMap,
                            isMine = false,
                            myUserId = appViewModel?.currentUserId,
                            onUserClick = onUserClick
                        )
                    }
                    else -> {
                        Text("Event data not available.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
