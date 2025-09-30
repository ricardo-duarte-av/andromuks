package net.vrkknn.andromuks.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile

/**
 * Renders rich text content with HTML formatting, Matrix user mentions, and inline images.
 * 
 * This function parses HTML-formatted message content and converts it to an AnnotatedString
 * with proper styling. It handles Matrix user links by converting them to styled mention pills,
 * processes inline images by showing alt text, and maintains proper text formatting.
 * 
 * @param formattedBody HTML-formatted message content to render
 * @param userProfileCache Map of user IDs to MemberProfile objects for display names
 * @param homeserverUrl Base URL of the Matrix homeserver (unused but kept for consistency)
 * @param authToken Authentication token (unused but kept for consistency)
 * @param appViewModel AppViewModel instance for accessing member data and requesting profiles
 * @param roomId The ID of the current room for member lookups
 * @param isEncrypted Whether this is encrypted content (unused but kept for consistency)
 * @param modifier Modifier to apply to the text content
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun RichMessageText(
    formattedBody: String,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    isEncrypted: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Get MaterialTheme colors outside of remember block
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    
    // Parse HTML and convert to AnnotatedString (simplified without inline images)
    val annotatedText = remember(formattedBody, userProfileCache, primaryContainer, onPrimaryContainer) {
        buildAnnotatedString {
            var currentIndex = 0
            val text = formattedBody
            
            // Regex to find Matrix user links: <a href="https://matrix.to/#/@user:server.com">DisplayName</a>
            val matrixUserLinkRegex = Regex("""<a\s+href="https://matrix\.to/#/([^"]+)"[^>]*>([^<]+)</a>""")
            // Regex to find img tags: <img src="..." alt="..." ...>
            val imgTagRegex = Regex("""<img[^>]*src="([^"]*)"[^>]*(?:alt="([^"]*)")?[^>]*>""")
            
            // Combine all matches and sort by position
            val allMatches = mutableListOf<Pair<Int, MatchResult>>()
            
            matrixUserLinkRegex.findAll(text).forEach { match ->
                allMatches.add(Pair(0, match)) // 0 = user link
            }
            
            imgTagRegex.findAll(text).forEach { match ->
                allMatches.add(Pair(1, match)) // 1 = img tag
            }
            
            // Sort by position in text
            allMatches.sortBy { it.second.range.first }
            
            allMatches.forEach { (type: Int, match: MatchResult) ->
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                
                // Add text before the match
                if (startIndex > currentIndex) {
                    append(text.substring(currentIndex, startIndex))
                }
                
                when (type) {
                    0 -> {
                        // Handle Matrix user links
                        val matrixId = match.groupValues[1] // The @user:server.com part
                        val displayName = match.groupValues[2] // The display name
                        
                        // Decode URL-encoded Matrix ID
                        val decodedMatrixId = matrixId.replace("%40", "@").replace("%3A", ":")
                        
                        // Get profile for the mentioned user
                        val profile = userProfileCache[decodedMatrixId] ?: appViewModel?.getMemberMap(roomId)?.get(decodedMatrixId)
                        
                        // Request profile if not found
                        if (profile == null && appViewModel != null) {
                            appViewModel.requestUserProfile(decodedMatrixId)
                        }
                        
                        // Create mention pill with the display name from the HTML
                        pushStyle(
                            SpanStyle(
                                background = primaryContainer,
                                color = onPrimaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        append(displayName)
                        pop()
                    }
                    1 -> {
                        // Handle img tags - just show alt text for now
                        val alt = match.groupValues.getOrNull(2) ?: "image"
                        append("[$alt]")
                    }
                }
                
                currentIndex = endIndex
            }
            
            // Add remaining text
            if (currentIndex < text.length) {
                append(text.substring(currentIndex))
            }
        }
    }
    
    androidx.compose.material3.Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

/**
 * Renders plain text with Matrix user mentions converted to styled mention pills.
 * 
 * This function processes plain text messages and converts any Matrix user IDs (in the format
 * @user:server.com) into styled mention pills with proper background colors and typography.
 * It handles profile lookups, fallback display names, and automatic profile requests for
 * users not found in the cache.
 * 
 * @param text Plain text message content that may contain Matrix user IDs
 * @param userProfileCache Map of user IDs to MemberProfile objects for display names
 * @param homeserverUrl Base URL of the Matrix homeserver (unused but kept for consistency)
 * @param authToken Authentication token (unused but kept for consistency)
 * @param appViewModel AppViewModel instance for accessing member data and requesting profiles
 * @param roomId The ID of the current room for member lookups
 * @param modifier Modifier to apply to the text content
 */
@Composable
fun MessageTextWithMentions(
    text: String,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    modifier: Modifier = Modifier
) {
    // Regex to find Matrix user IDs (@user:server.com)
    val matrixIdRegex = Regex("@([^:]+):([^\\s]+)")
    val matches = matrixIdRegex.findAll(text)
    
    if (matches.none()) {
        // No Matrix IDs found, render as plain text
        androidx.compose.material3.Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
    } else {
        // Build annotated string with mentions
        val annotatedText = buildAnnotatedString {
            var lastIndex = 0
            
            matches.forEach { match ->
                val fullMatch = match.value
                val userId = fullMatch
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                
                // Add text before the mention
                if (startIndex > lastIndex) {
                    append(text.substring(lastIndex, startIndex))
                }
                
                // Get profile for the mentioned user
                val profile = userProfileCache[userId] ?: appViewModel?.getMemberMap(roomId)?.get(userId)
                val displayName = profile?.displayName
                
                // Request profile if not found
                if (profile == null && appViewModel != null) {
                    appViewModel.requestUserProfile(userId)
                }
                
                // Create mention pill with Material3 styling
                pushStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.primaryContainer,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                )
                // Use display name if available, otherwise extract username from Matrix ID
                append(displayName ?: userId.substringAfter("@").substringBefore(":"))
                pop()
                
                lastIndex = endIndex
            }
            
            // Add remaining text after the last mention
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
        
        androidx.compose.material3.Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
    }
}
