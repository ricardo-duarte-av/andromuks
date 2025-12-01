package net.vrkknn.andromuks.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import net.vrkknn.andromuks.AppViewModel

/**
 * Data model for an emoji autocomplete suggestion.
 *
 * - For standard Unicode emojis, [emoji] is populated and [customEmoji] is null.
 * - For custom image emojis, [customEmoji] is populated and [emoji] is null.
 */
data class EmojiSuggestion(
    val shortcode: String,
    val emoji: String? = null,
    val customEmoji: AppViewModel.CustomEmoji? = null
)

/**
 * Static mapping of `:shortcode:` → emoji character.
 *
 * NOTE: This is intentionally focused on the most common emoji shortcodes.
 * It can be expanded over time without changing the autocomplete logic.
 */
object EmojiShortcodes {
    private val shortcodeToEmoji: Map<String, String> = mapOf(
        // Smileys
        "grinning" to "😀",
        "smiley" to "😃",
        "smile" to "😄",
        "grin" to "😁",
        "laughing" to "😆",
        "satisfied" to "😆",
        "sweat_smile" to "😅",
        "joy" to "😂",
        "rofl" to "🤣",
        "slight_smile" to "🙂",
        "upside_down" to "🙃",
        "wink" to "😉",
        "blush" to "😊",
        "innocent" to "😇",
        "heart_eyes" to "😍",
        "star_struck" to "🤩",
        "kissing_heart" to "😘",
        "kissing" to "😗",
        "relaxed" to "☺️",
        "kissing_closed_eyes" to "😚",
        "kissing_smiling_eyes" to "😙",
        "yum" to "😋",
        "stuck_out_tongue" to "😛",
        "stuck_out_tongue_winking_eye" to "😜",
        "zany" to "🤪",
        "stuck_out_tongue_closed_eyes" to "😝",
        "money_mouth" to "🤑",
        "hugs" to "🤗",
        "thinking" to "🤔",
        "zipper_mouth" to "🤐",
        "raised_eyebrow" to "🤨",
        "neutral_face" to "😐",
        "expressionless" to "😑",
        "no_mouth" to "😶",
        "smirk" to "😏",
        "unamused" to "😒",
        "roll_eyes" to "🙄",
        "grimacing" to "😬",
        "relieved" to "😌",
        "pensive" to "😔",
        "sleepy" to "😪",
        "drooling_face" to "🤤",
        "sleeping" to "😴",
        "mask" to "😷",
        "face_with_thermometer" to "🤒",
        "face_with_head_bandage" to "🤕",
        "nauseated_face" to "🤢",
        "vomiting" to "🤮",
        "sneezing_face" to "🤧",
        "hot_face" to "🥵",
        "cold_face" to "🥶",
        "woozy" to "🥴",
        "dizzy_face" to "😵",
        "exploding_head" to "🤯",
        "cowboy" to "🤠",
        "party" to "🥳",
        "sunglasses" to "😎",
        "nerd" to "🤓",
        "monocle" to "🧐",
        "confused" to "😕",
        "slightly_frowning" to "🙁",
        "frowning2" to "☹️",
        "open_mouth" to "😮",
        "hushed" to "😯",
        "astonished" to "😲",
        "flushed" to "😳",
        "pleading" to "🥺",
        "frowning" to "😦",
        "anguished" to "😧",
        "fearful" to "😨",
        "cold_sweat" to "😰",
        "disappointed_relieved" to "😥",
        "cry" to "😢",
        "sob" to "😭",
        "scream" to "😱",
        "confounded" to "😖",
        "persevere" to "😣",
        "disappointed" to "😞",
        "sweat" to "😓",
        "weary" to "😩",
        "tired_face" to "😫",
        "yawning" to "🥱",
        "triumph" to "😤",
        "pout" to "😡",
        "rage" to "😡",
        "angry" to "😠",
        "cursing" to "🤬",

        // Hearts & symbols
        "heart" to "❤️",
        "orange_heart" to "🧡",
        "yellow_heart" to "💛",
        "green_heart" to "💚",
        "blue_heart" to "💙",
        "purple_heart" to "💜",
        "black_heart" to "🖤",
        "white_heart" to "🤍",
        "brown_heart" to "🤎",
        "broken_heart" to "💔",
        "two_hearts" to "💕",
        "revolving_hearts" to "💞",
        "sparkling_heart" to "💖",
        "heartpulse" to "💗",
        "heartbeat" to "💓",
        "cupid" to "💘",

        // Hand gestures
        "thumbsup" to "👍",
        "+1" to "👍",
        "thumbsdown" to "👎",
        "-1" to "👎",
        "ok_hand" to "👌",
        "clap" to "👏",
        "wave" to "👋",
        "raised_hand" to "✋",
        "v" to "✌️",
        "fist" to "✊",
        "punch" to "👊",
        "muscle" to "💪",
        "pray" to "🙏",

        // Common objects / misc
        "fire" to "🔥",
        "100" to "💯",
        "star" to "⭐",
        "star2" to "🌟",
        "sparkles" to "✨",
        "tada" to "🎉",
        "gift" to "🎁",
        "balloon" to "🎈",
        "warning" to "⚠️",
        "check" to "✅",
        "x" to "❌",
        "question" to "❓",
        "grey_question" to "❔",
        "grey_exclamation" to "❕",
        "exclamation" to "❗",

        // Faces with hearts / kisses
        "smiling_face_with_3_hearts" to "🥰",

        // Animals (common)
        "dog" to "🐶",
        "cat" to "🐱",
        "mouse" to "🐭",
        "hamster" to "🐹",
        "rabbit" to "🐰",
        "fox" to "🦊",
        "bear" to "🐻",
        "panda" to "🐼",
        "koala" to "🐨",
        "tiger" to "🐯",
        "lion" to "🦁",
        "cow" to "🐮",
        "pig" to "🐷",
        "frog" to "🐸",
        "monkey" to "🐵",

        // Food (common)
        "pizza" to "🍕",
        "hamburger" to "🍔",
        "fries" to "🍟",
        "hotdog" to "🌭",
        "taco" to "🌮",
        "burrito" to "🌯",
        "coffee" to "☕",
        "tea" to "🍵",
        "beer" to "🍺",
        "wine_glass" to "🍷",
        "cake" to "🍰",
        "birthday" to "🎂"
    )

    /**
     * Return autocomplete suggestions for the given [query].
     *
     * Includes both standard emojis and custom emojis from [customEmojiPacks].
     */
    fun getSuggestions(
        query: String,
        customEmojiPacks: List<AppViewModel.EmojiPack>,
        maxResults: Int = 25
    ): List<EmojiSuggestion> {
        val trimmed = query.trim().lowercase()

        val standardMatches = shortcodeToEmoji
            .asSequence()
            .filter { (name, _) ->
                trimmed.isEmpty() || name.startsWith(trimmed)
            }
            .sortedBy { it.key }
            .map { (name, emoji) ->
                EmojiSuggestion(shortcode = name, emoji = emoji)
            }

        val customMatches = customEmojiPacks
            .asSequence()
            .flatMap { it.emojis.asSequence() }
            .filter { emoji ->
                val name = emoji.name.lowercase()
                trimmed.isEmpty() || name.startsWith(trimmed)
            }
            .sortedBy { it.name }
            .map { emoji ->
                EmojiSuggestion(shortcode = emoji.name, customEmoji = emoji)
            }

        return (standardMatches + customMatches)
            .take(maxResults)
            .toList()
    }

    /**
     * Find a completed shortcode (without the surrounding colons), e.g. "laughing".
     */
    fun findByShortcode(
        shortcodeWithoutColons: String,
        customEmojiPacks: List<AppViewModel.EmojiPack>
    ): EmojiSuggestion? {
        val key = shortcodeWithoutColons.trim().lowercase()
        shortcodeToEmoji[key]?.let { emoji ->
            return EmojiSuggestion(shortcode = key, emoji = emoji)
        }

        customEmojiPacks
            .asSequence()
            .flatMap { it.emojis.asSequence() }
            .firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?.let { custom ->
                return EmojiSuggestion(shortcode = custom.name, customEmoji = custom)
            }

        return null
    }
}

/**
 * Floating suggestion list for `:shortcode:` emoji autocomplete.
 */
@Composable
fun EmojiSuggestionList(
    query: String,
    customEmojiPacks: List<AppViewModel.EmojiPack>,
    homeserverUrl: String,
    authToken: String,
    onSuggestionSelected: (EmojiSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = remember(query, customEmojiPacks) {
        EmojiShortcodes.getSuggestions(query, customEmojiPacks)
    }

    if (suggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .widthIn(max = 260.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp), // Rounder corners to match user list
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp  // Same as user list for consistent appearance
    ) {
        LazyColumn(
            modifier = Modifier
                .height(200.dp), // Same height as user list
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(suggestions) { suggestion ->
                Row(
                    modifier = Modifier
                        .clickable { onSuggestionSelected(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Emoji preview
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            suggestion.emoji != null -> {
                                Text(
                                    text = suggestion.emoji,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            suggestion.customEmoji != null -> {
                                ImageEmoji(
                                    mxcUrl = suggestion.customEmoji.mxcUrl,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = ":${suggestion.shortcode}:",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        suggestion.emoji?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}


