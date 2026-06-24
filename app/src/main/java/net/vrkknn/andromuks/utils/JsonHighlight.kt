package net.vrkknn.andromuks.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Colors used to syntax-highlight JSON. Theme-aware so the same highlighter reads well in both
 * light and dark mode. Built from the active [MaterialTheme] via [rememberJsonHighlightColors].
 */
data class JsonHighlightColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val keyword: Color, // true / false / null
    val punctuation: Color,
    val default: Color,
)

@Composable
fun rememberJsonHighlightColors(): JsonHighlightColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        JsonHighlightColors(
            key = scheme.primary,
            string = scheme.tertiary,
            number = scheme.secondary,
            keyword = scheme.error,
            punctuation = scheme.onSurfaceVariant,
            default = scheme.onSurface,
        )
    }
}

/**
 * Tokenize [text] as JSON and return an [AnnotatedString] with syntax highlighting. This is a
 * lenient single-pass scanner — it never throws and degrades gracefully on malformed input, so it
 * is safe to call on every keystroke from an editor's [VisualTransformation]. Because it only adds
 * spans (never changes the text), the character offsets are preserved 1:1.
 */
fun highlightJson(text: String, colors: JsonHighlightColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '"' -> {
                // Read the full (possibly escaped) string literal.
                val start = i
                i++ // opening quote
                while (i < n) {
                    val ch = text[i]
                    if (ch == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (ch == '"') {
                        i++
                        break
                    }
                    i++
                }
                val literal = text.substring(start, i)
                // A string is a key if the next non-whitespace char is a colon.
                var j = i
                while (j < n && text[j].isWhitespace()) j++
                val isKey = j < n && text[j] == ':'
                withStyle(SpanStyle(color = if (isKey) colors.key else colors.string)) {
                    append(literal)
                }
            }

            c == '-' || c.isDigit() -> {
                val start = i
                i++
                while (i < n && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' ||
                        text[i] == 'E' || text[i] == '+' || text[i] == '-')) {
                    i++
                }
                withStyle(SpanStyle(color = colors.number)) { append(text.substring(start, i)) }
            }

            c.isLetter() -> {
                val start = i
                while (i < n && text[i].isLetter()) i++
                val word = text.substring(start, i)
                val color = if (word == "true" || word == "false" || word == "null") {
                    colors.keyword
                } else {
                    colors.default
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            }

            c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' -> {
                withStyle(SpanStyle(color = colors.punctuation)) { append(c) }
                i++
            }

            else -> {
                withStyle(SpanStyle(color = colors.default)) { append(c) }
                i++
            }
        }
    }
}

/**
 * [VisualTransformation] that syntax-highlights JSON in an editable text field. Offsets are
 * unchanged ([OffsetMapping.Identity]) because highlighting only adds spans.
 */
class JsonVisualTransformation(private val colors: JsonHighlightColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(highlightJson(text.text, colors), OffsetMapping.Identity)
    }
}
