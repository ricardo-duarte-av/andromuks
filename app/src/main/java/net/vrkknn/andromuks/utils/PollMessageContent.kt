package net.vrkknn.andromuks.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.scaledTween

/**
 * Renders a poll (MSC3381) inside a message bubble.
 *
 * Follows the same contract as [LocationMessageContent]: this composable owns no bubble chrome and
 * takes the bubble's [contentColor] so it contrasts correctly whichever bubble variant (mine,
 * theirs, redacted, mention) it lands in. Everything else derives from [MaterialTheme.colorScheme],
 * since the app defaults to Material You dynamic colour.
 *
 * The result bars use the played/unplayed alpha pairing established by the audio waveform
 * (`WaveformSeekBar` in MediaFunctions.kt) rather than a `LinearProgressIndicator`, so filled
 * fractions read the same way everywhere in the app.
 *
 * @param results the aggregated poll, from `AppViewModel.pollResults`.
 * @param onToggleAnswer invoked with an answer id when the user taps an option. Null makes the poll
 *   read-only (e.g. in the single-event/search renderer, where there is no room context to vote in).
 * @param onShowVoters invoked with an answer id when the user taps a vote count.
 */
@Composable
fun PollMessageContent(
    results: PollResults,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onToggleAnswer: ((String) -> Unit)? = null,
    onShowVoters: ((String) -> Unit)? = null,
) {
    val start = results.start
    val canVote = onToggleAnswer != null && !results.isEnded

    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (results.isEnded) Icons.Filled.Lock else Icons.Filled.BarChart,
                contentDescription = if (results.isEnded) "Closed poll" else "Poll",
                tint = contentColor.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = start.question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }

        Text(
            text = pollSubtitle(results),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
        )

        for (answer in start.answers) {
            PollAnswerRow(
                answer = answer,
                results = results,
                contentColor = contentColor,
                canVote = canVote,
                onToggleAnswer = onToggleAnswer,
                onShowVoters = onShowVoters,
            )
        }

        Text(
            text = pollFooter(results),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
        )
    }
}

/**
 * One selectable answer: a track, a proportional fill, and the label/count overlay.
 *
 * The fill is scaled against the *leading* option rather than the total, so the front-runner always
 * reads as a full bar. That makes relative standing easier to compare at a glance in a 300dp bubble;
 * the exact share is still spelled out as a percentage in the overlay.
 */
@Composable
private fun PollAnswerRow(
    answer: PollAnswer,
    results: PollResults,
    contentColor: Color,
    canVote: Boolean,
    onToggleAnswer: ((String) -> Unit)?,
    onShowVoters: ((String) -> Unit)?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isPicked = answer.id in results.myAnswerIds
    val count = results.countFor(answer.id)
    val showResults = !results.resultsHidden

    val targetFraction = when {
        !showResults -> 0f
        results.topCount <= 0 -> 0f
        else -> count.toFloat() / results.topCount.toFloat()
    }
    val fillFraction by animateFloatAsState(
        targetValue = targetFraction.coerceIn(0f, 1f),
        animationSpec = scaledTween(300),
        label = "pollBarFill",
    )

    val percentage = if (results.totalVoters > 0) {
        (count * 100f / results.totalVoters).toInt()
    } else {
        0
    }

    val rowDescription = buildString {
        append(answer.text)
        if (showResults) append(", $count ${if (count == 1) "vote" else "votes"}, $percentage%")
        if (isPicked) append(", selected")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .then(
                if (canVote && onToggleAnswer != null) {
                    Modifier.clickable { onToggleAnswer(answer.id) }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = rowDescription },
    ) {
        if (showResults && fillFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(
                        if (isPicked) {
                            colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            contentColor.copy(alpha = 0.28f)
                        },
                    ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isPicked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isPicked) colorScheme.primary else contentColor.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = answer.text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            if (showResults) {
                Text(
                    text = "$count · $percentage%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isPicked) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor.copy(alpha = 0.85f),
                    modifier = if (count > 0 && onShowVoters != null) {
                        Modifier.clickable { onShowVoters(answer.id) }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/** "Select up to 2" / "Results are hidden until the poll ends" / "Final results". */
private fun pollSubtitle(results: PollResults): String = when {
    results.isEnded -> "Final results"
    results.resultsHidden -> "Results are hidden until the poll ends"
    results.start.maxSelections > 1 -> "Select up to ${results.start.maxSelections}"
    else -> "Select one option"
}

private fun pollFooter(results: PollResults): String {
    val voters = results.totalVoters
    val votersText = if (voters == 1) "1 vote" else "$voters votes"
    return when {
        results.resultsHidden && voters == 0 -> "No votes yet"
        results.resultsHidden -> "$votersText cast"
        voters == 0 -> "No votes yet"
        else -> votersText
    }
}

/**
 * Lists everyone who voted for one poll answer.
 *
 * Same shape and dismissal behaviour as [ReactionDetailsDialog], including its opportunistic profile
 * fetch so avatars and display names fill in for voters we have not seen before.
 */
@Composable
fun PollVotersDialog(
    results: PollResults,
    answerId: String,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit = {},
    appViewModel: AppViewModel? = null,
    roomId: String? = null,
) {
    val answer = results.start.answers.find { it.id == answerId }
    val voters = results.votesByAnswer[answerId].orEmpty()

    // OPPORTUNISTIC PROFILE LOADING: fetch any voter profile we're missing when the dialog opens.
    LaunchedEffect(voters.map { it.voter }, roomId, appViewModel?.memberUpdateCounter) {
        if (appViewModel != null && roomId != null) {
            voters.forEach { vote ->
                if (appViewModel.getUserProfile(vote.voter, roomId) == null) {
                    appViewModel.requestUserProfileOnDemand(vote.voter, roomId)
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isVisible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val enterDuration = 220
    val exitDuration = 160

    LaunchedEffect(Unit) {
        isDismissing = false
        isVisible = true
    }

    fun dismissWithAnimation(afterDismiss: () -> Unit = {}) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            isVisible = false
            delay(exitDuration.toLong())
            onDismiss()
            afterDismiss()
        }
    }

    Dialog(
        onDismissRequest = { dismissWithAnimation() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { dismissWithAnimation() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = scaledTween(enterDuration)) +
                    scaleIn(initialScale = 0.85f, animationSpec = scaledTween(enterDuration)),
                exit = fadeOut(animationSpec = scaledTween(exitDuration)) +
                    scaleOut(targetScale = 0.85f, animationSpec = scaledTween(exitDuration)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { }, // Consume clicks on content to prevent dismissal
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = answer?.text ?: "Votes",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (voters.size == 1) "1 vote" else "${voters.size} votes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                        ) {
                            items(items = voters, key = { it.eventId }) { vote ->
                                val profile = appViewModel?.getUserProfile(vote.voter, roomId ?: "")
                                PollVoterListItem(
                                    userId = vote.voter,
                                    displayName = profile?.displayName,
                                    avatarUrl = profile?.avatarUrl,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    onUserClick = { userId ->
                                        dismissWithAnimation { onUserClick(userId) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PollVoterListItem(
    userId: String,
    displayName: String?,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(userId) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            mxcUrl = avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (displayName ?: userId).take(1),
            size = 40.dp,
            userId = userId,
            displayName = displayName,
        )
        Text(
            text = displayName ?: userId,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )
    }
}
