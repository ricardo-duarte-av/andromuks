package net.vrkknn.andromuks.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * BubbleColors - Color scheme for message bubbles following Material 3 Expressive principles
 * 
 * Inspired by Google Messages' sophisticated use of Material 3 tokens:
 * - Uses Material 3 color tokens directly with minimal manipulation
 * - Better contrast through elevated surfaces for others' messages
 * - Visual indicators (icons) for edited messages instead of color shifts
 * - Accent borders/stripes for mentions instead of full color changes
 * - More refined visual hierarchy
 */
@Immutable
data class BubbleColors(
    val container: Color,
    val content: Color,
    val accent: Color,
    val stripe: Color,
    // NEW: Optional accent border color for mentions (Google Messages style)
    val mentionBorder: Color? = null
)

object BubblePalette {

    /**
     * Generate bubble colors following Google Messages' Material 3 Expressive principles.
     * 
     * Key improvements:
     * - Direct use of Material 3 tokens (minimal lerping)
     * - Elevated surfaces for others' messages (better contrast)
     * - Edited messages keep base color (visual indicator handles distinction)
     * - Mentions use accent border instead of color change
     * - Thread messages use refined tertiary variants
     */
    fun colors(
        colorScheme: ColorScheme,
        isMine: Boolean,
        isEdited: Boolean = false,
        mentionsMe: Boolean = false,
        isThreadMessage: Boolean = false,
        hasSpoiler: Boolean = false,
        isRedacted: Boolean = false
    ): BubbleColors {
        // Redacted messages: Use error colors directly
        if (isRedacted) {
            val accent = colorScheme.error
            return BubbleColors(
                container = colorScheme.errorContainer,
                content = colorScheme.onErrorContainer,
                accent = accent,
                stripe = accent,
                mentionBorder = null
            )
        }

        // Thread messages: Use tertiary with refined blending
        if (isThreadMessage) {
            val accent = colorScheme.tertiary
            // For others' thread messages, use a subtle blend for better contrast
            val container = if (isMine) {
                colorScheme.tertiaryContainer
            } else {
                // Very subtle blend: 15% toward surface (reduced from 25%)
                lerp(colorScheme.tertiaryContainer, colorScheme.surfaceContainerHigh, 0.15f)
            }
            return BubbleColors(
                container = container,
                content = colorScheme.onTertiaryContainer,
                accent = accent,
                stripe = accent.copy(alpha = 0.9f),
                mentionBorder = null
            )
        }

        // Spoiler messages: Muted colors with subtle blending
        if (hasSpoiler) {
            val accent = colorScheme.outlineVariant
            val container = if (isMine) {
                // My spoilers: blend primaryContainer with surfaceContainerHigh (reduced blend)
                lerp(colorScheme.primaryContainer, colorScheme.surfaceContainerHigh, 0.3f)
            } else {
                // Others' spoilers: use elevated surface with subtle secondary hint
                lerp(colorScheme.surfaceContainerHigh, colorScheme.secondaryContainer, 0.2f)
            }
            return BubbleColors(
                container = container,
                content = colorScheme.onSurface,
                accent = accent,
                stripe = accent,
                mentionBorder = null
            )
        }

        // GOOGLE MESSAGES STYLE: Use Material 3 tokens directly with minimal manipulation
        
        // My messages: Use primaryContainer directly (no lerping needed)
        // Edited messages keep the same color (visual indicator handles distinction)
        val myContainer = colorScheme.primaryContainer
        val myContent = colorScheme.onPrimaryContainer
        
        // Others' messages: Use elevated surfaces for better contrast (Google Messages style)
        // In dark mode, we need a lighter color - blend surfaceContainerHighest with secondaryContainer
        // This ensures good visibility in dark mode while maintaining Material 3 principles
        val othersBaseContainer = colorScheme.surfaceContainerHighest
        val othersContainer = lerp(othersBaseContainer, colorScheme.secondaryContainer, 0.25f)
        val othersContent = colorScheme.onSurface
        
        // For mentions: Use a subtle color change instead of border (more Google Messages-like)
        // Blend the base container with secondaryContainer to create a subtle tint
        val othersMentionContainer = if (mentionsMe && !isMine) {
            lerp(othersContainer, colorScheme.secondaryContainer, 0.4f)
        } else {
            othersContainer
        }
        
        // Determine container and content colors
        val container = when {
            isMine -> myContainer
            mentionsMe && !isMine -> othersMentionContainer
            else -> othersContainer
        }
        
        val content = when {
            isMine -> myContent
            else -> othersContent
        }
        
        // Accent colors: primary for mine, secondary for others
        val accent = if (isMine) colorScheme.primary else colorScheme.secondary
        
        // No border for mentions - we use color change instead
        val mentionBorder = null
        
        // Stripe color: accent with appropriate alpha
        val stripe = accent.copy(alpha = 0.85f)

        return BubbleColors(
            container = container,
            content = content,
            accent = accent,
            stripe = stripe,
            mentionBorder = mentionBorder
        )
    }

    /**
     * Generate background color for reply previews.
     * Uses subtle blending between surface and bubble container.
     */
    fun replyPreviewBackground(colorScheme: ColorScheme, bubbleColors: BubbleColors): Color {
        return lerp(colorScheme.surface, bubbleColors.container, 0.5f)
    }
}

