package net.vrkknn.andromuks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.utils.PollAnswer
import net.vrkknn.andromuks.utils.PollMessageContent
import net.vrkknn.andromuks.utils.PollResults
import net.vrkknn.andromuks.utils.PollStartInfo

/** MSC3381 caps a poll at 20 answers. */
private const val MAX_POLL_OPTIONS = 20

/**
 * Full-screen poll composer, reached by typing `/poll` in a room.
 *
 * A screen rather than a dialog on purpose: this is a form that *grows* while the IME is open, and a
 * floating window would be fighting the keyboard for space every time an option is added. Follows
 * the [RoomMakerScreen] layout conventions (Scaffold + TopAppBar + `imePadding`).
 *
 * Reached from the room timeline, a thread, or a chat bubble. [threadRootEventId] is non-null when
 * the poll should be posted into a thread; it travels through to the command's `relates_to`.
 *
 * The poll itself is created by gomuks from an MSC4391 command envelope — see
 * [PollCoordinator.sendPollCreate]. That route offers no `kind` argument, so there is deliberately no
 * disclosed/undisclosed control here: gomuks hardcodes disclosed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollMakerScreen(
    roomId: String,
    appViewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier,
    threadRootEventId: String? = null,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var allowMultiple by remember { mutableStateOf(false) }
    var maxSelections by remember { mutableIntStateOf(1) }

    val filledOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
    // A one-option poll offers no choice, so two is the floor even though gomuks accepts one.
    val canSend = question.isBlank().not() && filledOptions.size >= PollCoordinator.MIN_POLL_OPTIONS

    // max_selections must always be in range: gomuks clamps an out-of-range or missing value to
    // "every option selectable" rather than to 1. See PollCoordinator.sendPollCreate.
    val effectiveMaxSelections = if (allowMultiple) {
        maxSelections.coerceIn(1, filledOptions.size.coerceAtLeast(1))
    } else {
        1
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (threadRootEventId != null) "Poll in thread" else "Create poll") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSend,
                        onClick = {
                            appViewModel.pollCoordinator.sendPollCreate(
                                roomId = roomId,
                                question = question,
                                options = filledOptions,
                                maxSelections = effectiveMaxSelections,
                                threadRootEventId = threadRootEventId,
                            )
                            navController.popBackStack()
                        },
                    ) {
                        Text("Send")
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                Text(
                    text = "Options",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            itemsIndexed(options) { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { options[index] = it },
                        label = { Text("Option ${index + 1}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    // Never let the user delete their way below the two-option minimum.
                    IconButton(
                        onClick = { options.removeAt(index) },
                        enabled = options.size > PollCoordinator.MIN_POLL_OPTIONS,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove option ${index + 1}",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            item {
                TextButton(
                    onClick = { options.add("") },
                    enabled = options.size < MAX_POLL_OPTIONS,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "  Add option")
                }
            }

            item {
                Text(
                    text = "Answers per person",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                // A mode toggle rather than a raw number field: "max_selections" is how many answers
                // one person may pick, and exposing it as a bare integer invites 0 and 99.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !allowMultiple,
                        onClick = {
                            allowMultiple = false
                            maxSelections = 1
                        },
                        label = { Text("Single answer") },
                    )
                    FilterChip(
                        selected = allowMultiple,
                        onClick = {
                            allowMultiple = true
                            maxSelections = maxSelections.coerceAtLeast(2)
                        },
                        label = { Text("Multiple answers") },
                    )
                }
            }

            if (allowMultiple) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Up to $effectiveMaxSelections of ${filledOptions.size.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { maxSelections = (maxSelections - 1).coerceAtLeast(1) },
                            enabled = effectiveMaxSelections > 1,
                        ) {
                            Text(text = "−", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(
                            onClick = { maxSelections += 1 },
                            enabled = effectiveMaxSelections < filledOptions.size,
                        ) {
                            Text(text = "+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }

            if (canSend) {
                item {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    PollPreview(
                        question = question.trim(),
                        options = filledOptions,
                        maxSelections = effectiveMaxSelections,
                    )
                }
            }
        }
    }
}

/**
 * Live preview of the poll as it will render in the timeline, using the real
 * [PollMessageContent] composable rather than a mock so the two can never drift.
 */
@Composable
private fun PollPreview(question: String, options: List<String>, maxSelections: Int, modifier: Modifier = Modifier) {
    val results = remember(question, options, maxSelections) {
        PollResults(
            start = PollStartInfo(
                eventId = "",
                sender = "",
                question = question,
                answers = options.mapIndexed { index, text -> PollAnswer(id = "preview-$index", text = text) },
                maxSelections = maxSelections,
                isUndisclosed = false,
                isStablePrefix = false,
            ),
            votesByAnswer = emptyMap(),
            totalVoters = 0,
            myAnswerIds = emptySet(),
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box {
            PollMessageContent(
                results = results,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
                // Preview only — voting and voter lists are meaningless before the poll exists.
                onToggleAnswer = null,
                onShowVoters = null,
            )
        }
    }
}
