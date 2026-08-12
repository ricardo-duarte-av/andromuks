package net.vrkknn.andromuks.utils

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The guided argument form for an MSC4391 bot command.
 *
 * This is the half of the MSC that text parsing cannot deliver: someone who has never seen a
 * command line gets a typed field per parameter, with the bot's own descriptions and live
 * validation, and cannot send an invocation that will be rejected.
 *
 * It opens either because the typed line is missing or mistyping a required argument, or because
 * the user tapped the signature strip. Whatever was already typed is carried in as [initial], so
 * the form is a continuation of the line rather than a restart.
 *
 * Rendered as an inline overlay inside the screen's root `Box` rather than a `ModalBottomSheet`,
 * matching the existing per-message-profile picker — chat bubbles run a constrained NavHost where a
 * modal sheet is awkward, and all three composers then take an identical diff.
 */
@Composable
fun BotCommandArgumentSheet(
    command: BotCommand,
    initial: Map<String, ArgValue>,
    coercionContext: CoercionContext,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, ArgValue>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every field is edited as text and coerced on each keystroke, so what the form accepts is
    // exactly what the typed syntax accepts — one parser, no second set of rules to drift.
    val texts = remember(command.stateKey) {
        mutableStateMapOf<String, String>().apply {
            command.parameters.forEach { parameter ->
                if (parameter.schema is ParamSchema.ArrayOf) return@forEach
                val seed = initial[parameter.key] ?: parameter.effectiveDefault()
                put(parameter.key, seed?.displayText().orEmpty())
            }
        }
    }
    val arrays = remember(command.stateKey) {
        mutableStateMapOf<String, SnapshotStateList<String>>().apply {
            command.parameters.forEach { parameter ->
                if (parameter.schema !is ParamSchema.ArrayOf) return@forEach
                val seed = (initial[parameter.key] as? ArgValue.Arr)?.items?.map { it.displayText() }
                put(parameter.key, (seed ?: listOf("")).toMutableStateList())
            }
        }
    }

    val bound = command.parameters.associate { it.key to bindField(it, texts, arrays, coercionContext) }
    val canSubmit = command.parameters.none { !it.optional && bound[it.key] == null }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "/${command.command}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = command.description.ifBlank { command.sender },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                command.parameters.forEach { parameter ->
                    ParameterField(
                        parameter = parameter,
                        texts = texts,
                        arrays = arrays,
                        isValid = bound[parameter.key] != null,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSubmit(command.parameters.mapNotNull { p -> bound[p.key]?.let { p.key to it } }.toMap()) },
                    enabled = canSubmit,
                ) {
                    Text("Send")
                }
            }
        }
    }
}

/** Coerces one field's current editor state into a value, or null when it is empty or invalid. */
private fun bindField(
    parameter: BotCommandParameter,
    texts: SnapshotStateMap<String, String>,
    arrays: SnapshotStateMap<String, SnapshotStateList<String>>,
    ctx: CoercionContext,
): ArgValue? {
    val schema = parameter.schema
    if (schema is ParamSchema.ArrayOf) {
        val items = arrays[parameter.key].orEmpty()
            .filter { it.isNotBlank() }
            .map { schema.items.parseString(it, ctx) ?: return null }
        return ArgValue.Arr(items)
    }
    val raw = texts[parameter.key].orEmpty()
    // An untouched optional field is absent, not invalid.
    if (raw.isBlank() && parameter.optional) return null
    return schema.parseString(raw, ctx)
}

/**
 * One parameter's editor, chosen from its declared schema.
 *
 * Booleans get a switch, a union of literals gets chips (the `on|off|auto` case), a bare literal is
 * fixed and shown read-only, arrays get an editable list, and everything else gets a text field
 * with the appropriate keyboard.
 */
@Composable
private fun ParameterField(
    parameter: BotCommandParameter,
    texts: SnapshotStateMap<String, String>,
    arrays: SnapshotStateMap<String, SnapshotStateList<String>>,
    isValid: Boolean,
    modifier: Modifier = Modifier,
) {
    val schema = parameter.schema
    val label = if (parameter.optional) "${parameter.key} (optional)" else parameter.key

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            schema is ParamSchema.Primitive && schema.type == PrimitiveType.BOOLEAN -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        ParameterDescription(parameter)
                    }
                    Switch(
                        checked = texts[parameter.key]?.let { it.equals("true", ignoreCase = true) } == true,
                        onCheckedChange = { texts[parameter.key] = it.toString() },
                    )
                }
            }

            schema is ParamSchema.Literal -> {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    // A literal has exactly one legal value, so there is nothing to choose.
                    text = schema.value.displayText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ParameterDescription(parameter)
            }

            schema is ParamSchema.Union && schema.variants.all { it is ParamSchema.Literal } -> {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                ParameterDescription(parameter)
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    schema.variants.filterIsInstance<ParamSchema.Literal>().forEach { variant ->
                        val text = variant.value.displayText()
                        FilterChip(
                            selected = texts[parameter.key] == text,
                            onClick = { texts[parameter.key] = text },
                            label = { Text(text) },
                        )
                    }
                }
            }

            schema is ParamSchema.ArrayOf -> {
                arrays[parameter.key]?.let { items -> ArrayParameterField(parameter, items, label) }
            }

            else -> {
                OutlinedTextField(
                    value = texts[parameter.key].orEmpty(),
                    onValueChange = { texts[parameter.key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    supportingText = { ParameterDescription(parameter) },
                    isError = !isValid && !parameter.optional && texts[parameter.key].orEmpty().isNotBlank(),
                    singleLine = true,
                    keyboardOptions = keyboardOptionsFor(schema),
                )
            }
        }
    }
}

/** An editable list of items for an array parameter. */
@Composable
private fun ArrayParameterField(
    parameter: BotCommandParameter,
    items: SnapshotStateList<String>,
    label: String,
    modifier: Modifier = Modifier,
) {
    val itemSchema = (parameter.schema as ParamSchema.ArrayOf).items
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        ParameterDescription(parameter)
        items.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { items[index] = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = keyboardOptionsFor(itemSchema),
                )
                IconButton(
                    onClick = { if (items.size > 1) items.removeAt(index) else items[index] = "" },
                ) {
                    Text("×", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = { items.add("") }) { Text("Add") }
    }
}

@Composable
private fun ParameterDescription(parameter: BotCommandParameter, modifier: Modifier = Modifier) {
    if (parameter.description.isBlank()) return
    Text(
        // Untrusted remote text: plain, clamped, never HTML.
        text = parameter.description,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A numeric keypad for integer-only parameters; the default keyboard for everything else. */
private fun keyboardOptionsFor(schema: ParamSchema): KeyboardOptions {
    val isInteger = schema is ParamSchema.Primitive && schema.type == PrimitiveType.INTEGER
    return KeyboardOptions(keyboardType = if (isInteger) KeyboardType.Number else KeyboardType.Text)
}
