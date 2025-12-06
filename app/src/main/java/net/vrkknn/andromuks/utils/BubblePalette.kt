package net.vrkknn.andromuks.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@Immutable
data class BubbleColors(
    val container: Color,
    val content: Color,
    val accent: Color,
    val stripe: Color
)

object BubblePalette {

    fun colors(
        colorScheme: ColorScheme,
        isMine: Boolean,
        isEdited: Boolean = false,
        mentionsMe: Boolean = false,
        isThreadMessage: Boolean = false,
        hasSpoiler: Boolean = false,
        isRedacted: Boolean = false
    ): BubbleColors {
        if (isRedacted) {
            val accent = colorScheme.error
            return BubbleColors(
                container = colorScheme.errorContainer,
                content = colorScheme.onErrorContainer,
                accent = accent,
                stripe = accent
            )
        }

        if (isThreadMessage) {
            val accent = colorScheme.tertiary
            val container = if (isMine) {
                colorScheme.tertiaryContainer
            } else {
                lerp(colorScheme.tertiaryContainer, colorScheme.surface, 0.25f)
            }
            return BubbleColors(
                container = container,
                content = colorScheme.onTertiaryContainer,
                accent = accent,
                stripe = accent.copy(alpha = 0.9f)
            )
        }

        if (hasSpoiler) {
            val accent = colorScheme.outlineVariant
            val spoilerBase = if (isMine) {
                lerp(colorScheme.primaryContainer, colorScheme.surfaceContainerHigh, 0.4f)
            } else {
                lerp(colorScheme.surfaceContainerHigh, colorScheme.secondaryContainer, 0.35f)
            }
            return BubbleColors(
                container = spoilerBase,
                content = colorScheme.onSurface,
                accent = accent,
                stripe = accent
            )
        }

        val othersBase = lerp(colorScheme.surfaceVariant, colorScheme.secondaryContainer, 0.35f)
        val othersMention = lerp(colorScheme.secondaryContainer, colorScheme.surface, 0.2f)
        val othersEdited = lerp(othersBase, colorScheme.surfaceContainerHighest, 0.45f)

        val myBase = colorScheme.primaryContainer
        val myEdited = lerp(myBase, colorScheme.secondaryContainer, 0.45f)

        val container = when {
            isMine && isEdited -> myEdited
            isMine -> myBase
            !isMine && isEdited -> othersEdited
            !isMine && mentionsMe -> othersMention
            else -> othersBase
        }

        val content = when {
            isMine && isEdited -> colorScheme.onSecondaryContainer
            isMine -> colorScheme.onPrimaryContainer
            !isMine && (isEdited || mentionsMe) -> colorScheme.onSecondaryContainer
            else -> colorScheme.onSurface
        }

        val accent = if (isMine) colorScheme.primary else colorScheme.secondary

        return BubbleColors(
            container = container,
            content = content,
            accent = accent,
            stripe = accent.copy(alpha = 0.85f)
        )
    }

    fun replyPreviewBackground(colorScheme: ColorScheme, bubbleColors: BubbleColors): Color {
        return lerp(colorScheme.surface, bubbleColors.container, 0.5f)
    }
}

