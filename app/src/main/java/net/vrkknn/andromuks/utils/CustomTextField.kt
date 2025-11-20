package net.vrkknn.andromuks.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Custom TextField with controllable padding and bubble-style appearance
 */
@Composable
fun CustomBubbleTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 5,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    bubbleShape: Shape = RoundedCornerShape(16.dp),
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    onHeightChanged: ((Int) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    // Cursor color in BasicTextField is controlled by the text color
    // Using LocalContentColor.current ensures it adapts to theme (white in dark, black in light)
    val contentColor = LocalContentColor.current
    val adaptiveTextStyle = textStyle.copy(color = contentColor)

    Box(
        modifier = modifier
            .heightIn(min = (minLines * 20).dp, max = (maxLines * 20).dp)
            .onGloballyPositioned { coordinates ->
                onHeightChanged?.invoke(coordinates.size.height)
            }
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = adaptiveTextStyle,
            cursorBrush = SolidColor(contentColor), // Cursor color matches text color (theme-aware)
            enabled = enabled,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = interactionSource,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    shape = bubbleShape
                ),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingIcon != null) {
                        leadingIcon()
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (placeholder != null && value.text.isEmpty()) {
                            Box(modifier = Modifier.zIndex(0f)) {
                                placeholder()
                            }
                        }
                        Box(modifier = Modifier.zIndex(1f)) {
                            innerTextField()
                        }
                    }
                    if (trailingIcon != null) {
                        trailingIcon()
                    }
                }
            }
        )
    }
}

