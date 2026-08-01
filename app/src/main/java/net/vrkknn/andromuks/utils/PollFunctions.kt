package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.PowerLevelsInfo
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject

/**
 * Matrix polls (MSC3381) — wire parsing and vote aggregation.
 *
 * This file is deliberately free of Android and Compose dependencies so [computePollResults] can be
 * unit tested directly. JSON parsing lives in the `parsePoll*` functions; the aggregator operates on
 * the already-parsed data classes below (`org.json` is not on the unit-test classpath).
 *
 * Orchestration lives in [net.vrkknn.andromuks.PollCoordinator]; the UI lives in
 * [net.vrkknn.andromuks.utils.PollMessageContent]. This mirrors how reactions are split between
 * [ReactionFunctions] and [net.vrkknn.andromuks.ReactionCoordinator].
 */

// Both the stable (`m.poll.*`) and unstable (`org.matrix.msc3381.poll.*`) prefixes are in the wild.
// They are accepted for the event `type` and for the content key, and a response echoes whichever
// flavour the poll start used (see PollCoordinator.sendPollResponse).
const val POLL_START_UNSTABLE = "org.matrix.msc3381.poll.start"
const val POLL_START_STABLE = "m.poll.start"
const val POLL_RESPONSE_UNSTABLE = "org.matrix.msc3381.poll.response"
const val POLL_RESPONSE_STABLE = "m.poll.response"
const val POLL_END_UNSTABLE = "org.matrix.msc3381.poll.end"
const val POLL_END_STABLE = "m.poll.end"

private const val POLL_KIND_UNDISCLOSED_UNSTABLE = "org.matrix.msc3381.poll.undisclosed"
private const val POLL_KIND_UNDISCLOSED_STABLE = "m.poll.undisclosed"

private const val ORG_MSC1767_TEXT = "org.matrix.msc1767.text"

/** MSC3381 caps a poll at 20 answers; anything beyond is ignored. */
private const val MAX_POLL_ANSWERS = 20

/** MSC3381: `max_selections` defaults to 1 when absent or invalid. */
private const val DEFAULT_MAX_SELECTIONS = 1

val POLL_START_TYPES = setOf(POLL_START_UNSTABLE, POLL_START_STABLE)
val POLL_RESPONSE_TYPES = setOf(POLL_RESPONSE_UNSTABLE, POLL_RESPONSE_STABLE)
val POLL_END_TYPES = setOf(POLL_END_UNSTABLE, POLL_END_STABLE)

fun isPollStartType(type: String?): Boolean = type in POLL_START_TYPES

fun isPollResponseType(type: String?): Boolean = type in POLL_RESPONSE_TYPES

fun isPollEndType(type: String?): Boolean = type in POLL_END_TYPES

/** True for any poll event — start, response or end. Used by the cache/ingest routing. */
fun isPollEventType(type: String?): Boolean = isPollStartType(type) || isPollResponseType(type) || isPollEndType(type)

/**
 * True for poll responses and ends: the satellite events that mutate a poll bubble's rendering but
 * never appear as timeline rows themselves. Poll *starts* are ordinary timeline rows.
 */
fun isPollSatelliteEvent(event: TimelineEvent): Boolean = pollEventType(event)
    ?.let { isPollResponseType(it) || isPollEndType(it) } == true

/** One selectable answer in a poll. */
data class PollAnswer(val id: String, val text: String)

/** The parsed `poll.start` event — the immutable definition of the poll. */
data class PollStartInfo(
    val eventId: String,
    val sender: String,
    val question: String,
    val answers: List<PollAnswer>,
    val maxSelections: Int,
    val isUndisclosed: Boolean,
    /** Which prefix flavour this poll uses, so responses can echo it. */
    val isStablePrefix: Boolean,
)

/** One user's `poll.response`. [answerIds] is raw and unvalidated until [computePollResults]. */
data class PollVote(val eventId: String, val voter: String, val answerIds: List<String>, val timestamp: Long)

/** A `poll.end` event. Authorisation is checked during aggregation, not at parse time. */
data class PollEndInfo(val eventId: String, val sender: String, val timestamp: Long)

/**
 * Fully aggregated poll state, ready to render.
 *
 * @param votesByAnswer answer id → the effective votes counted for it (used for the voter list).
 * @param totalVoters number of distinct users whose vote counted for at least one answer.
 * @param myAnswerIds the local user's current selection, including any optimistic local vote.
 * @param resultsHidden true for an undisclosed poll that has not ended — counts must not be shown.
 * @param hasLocalVote true when [myAnswerIds] comes from an unconfirmed optimistic vote.
 */
data class PollResults(
    val start: PollStartInfo,
    val votesByAnswer: Map<String, List<PollVote>>,
    val totalVoters: Int,
    val myAnswerIds: Set<String>,
    val endedAt: Long? = null,
    val endedBy: String? = null,
    val resultsHidden: Boolean = false,
    val hasLocalVote: Boolean = false,
) {
    val isEnded: Boolean get() = endedAt != null

    fun countFor(answerId: String): Int = votesByAnswer[answerId]?.size ?: 0

    /** The highest single-answer count, used to scale the result bars. */
    val topCount: Int get() = votesByAnswer.values.maxOfOrNull { it.size } ?: 0
}

/**
 * Resolves the poll-flavoured event type for [event], looking through E2EE decryption.
 *
 * Gomuks surfaces the decrypted type at the top level, but `m.room.encrypted` events carrying a
 * `decrypted_type` are handled too so we don't depend on that behaviour.
 */
fun pollEventType(event: TimelineEvent): String? = when {
    isPollEventType(event.type) -> event.type
    isPollEventType(event.decryptedType) -> event.decryptedType
    else -> null
}

/** The content object holding poll data, looking through E2EE decryption. */
private fun pollContent(event: TimelineEvent, key: String, stableKey: String): JSONObject? {
    val candidates = listOfNotNull(event.content, event.decrypted)
    for (candidate in candidates) {
        candidate.optJSONObject(key)?.let { return it }
        candidate.optJSONObject(stableKey)?.let { return it }
    }
    return null
}

/** The outer content object (for `m.relates_to` and fallback text), looking through decryption. */
private fun outerContent(event: TimelineEvent): JSONObject? = when {
    event.content?.has("m.relates_to") == true -> event.content
    event.decrypted?.has("m.relates_to") == true -> event.decrypted
    else -> event.content ?: event.decrypted
}

/**
 * Extracts extensible-event text: `org.matrix.msc1767.text` first, then `m.text` (which may be a
 * string or an array of `{mimetype, body}` representations), then a plain `body`.
 */
private fun extractExtensibleText(obj: JSONObject?): String? {
    if (obj == null) return null

    obj.optString(ORG_MSC1767_TEXT).takeIf { it.isNotBlank() }?.let { return it }

    when (val mText = obj.opt("m.text")) {
        is String -> mText.takeIf { it.isNotBlank() }?.let { return it }

        is org.json.JSONArray -> {
            for (i in 0 until mText.length()) {
                val body = mText.optJSONObject(i)?.optString("body")
                if (!body.isNullOrBlank()) return body
            }
        }
    }

    return obj.optString("body").takeIf { it.isNotBlank() }
}

/** Parses a `poll.start` event, or returns null if it is malformed or has no usable answers. */
fun parsePollStart(event: TimelineEvent): PollStartInfo? {
    val type = pollEventType(event)?.takeIf { isPollStartType(it) } ?: return null
    val startContent = pollContent(event, POLL_START_UNSTABLE, POLL_START_STABLE) ?: return null

    val answersArray = startContent.optJSONArray("answers") ?: return null
    val seenIds = mutableSetOf<String>()
    val answers = (0 until answersArray.length())
        .mapNotNull { index ->
            val answerObj = answersArray.optJSONObject(index) ?: return@mapNotNull null
            val id = answerObj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Duplicate ids would make votes ambiguous; keep the first occurrence only.
            if (!seenIds.add(id)) return@mapNotNull null
            PollAnswer(id = id, text = extractExtensibleText(answerObj) ?: id)
        }
        .take(MAX_POLL_ANSWERS)
    if (answers.isEmpty()) return null

    val question = extractExtensibleText(startContent.optJSONObject("question"))
        ?: extractExtensibleText(outerContent(event))
        ?: "Poll"

    val maxSelections = startContent.optInt("max_selections", DEFAULT_MAX_SELECTIONS)
        .coerceAtLeast(DEFAULT_MAX_SELECTIONS)
        .coerceAtMost(answers.size)

    val kind = startContent.optString("kind")
    val isUndisclosed = kind == POLL_KIND_UNDISCLOSED_UNSTABLE || kind == POLL_KIND_UNDISCLOSED_STABLE

    return PollStartInfo(
        eventId = event.eventId,
        sender = event.sender,
        question = question,
        answers = answers,
        maxSelections = maxSelections,
        isUndisclosed = isUndisclosed,
        isStablePrefix = type == POLL_START_STABLE,
    )
}

/**
 * Parses a `poll.response` event into (poll start event id, vote).
 *
 * Redacted responses return null — a redacted vote must not count. An empty `answers` array is a
 * deliberate withdrawal and parses successfully with an empty [PollVote.answerIds].
 */
fun parsePollResponse(event: TimelineEvent): Pair<String, PollVote>? {
    if (pollEventType(event)?.let { isPollResponseType(it) } != true) return null
    if (event.redactedBy != null) return null

    val pollStartEventId = pollRelatesToEventId(event) ?: return null
    val responseContent = pollContent(event, POLL_RESPONSE_UNSTABLE, POLL_RESPONSE_STABLE) ?: return null
    val answersArray = responseContent.optJSONArray("answers") ?: return null

    val answerIds = (0 until answersArray.length())
        .mapNotNull { answersArray.optString(it).takeIf { id -> id.isNotBlank() } }

    return pollStartEventId to PollVote(
        eventId = event.eventId,
        voter = event.sender,
        answerIds = answerIds,
        timestamp = event.timestamp,
    )
}

/** Parses a `poll.end` event into (poll start event id, end info). Redacted ends are ignored. */
fun parsePollEnd(event: TimelineEvent): Pair<String, PollEndInfo>? {
    if (pollEventType(event)?.let { isPollEndType(it) } != true) return null
    if (event.redactedBy != null) return null

    val pollStartEventId = pollRelatesToEventId(event) ?: return null

    return pollStartEventId to PollEndInfo(
        eventId = event.eventId,
        sender = event.sender,
        timestamp = event.timestamp,
    )
}

/**
 * The poll start event id a response/end points at.
 *
 * Prefers gomuks' top-level `relates_to` (already resolved during ingest) and falls back to the
 * `m.relates_to` object in content or decrypted content.
 */
fun pollRelatesToEventId(event: TimelineEvent): String? {
    event.relatesTo?.takeIf { it.isNotBlank() && event.relationType == "m.reference" }?.let { return it }

    listOfNotNull(event.content, event.decrypted)
        .mapNotNull { it.optJSONObject("m.relates_to") }
        .filter { it.optString("rel_type") == "m.reference" }
        .firstNotNullOfOrNull { it.optString("event_id").takeIf { id -> id.isNotBlank() } }
        ?.let { return it }

    // Some senders omit rel_type on the nested object but gomuks still resolves relates_to.
    return event.relatesTo?.takeIf { it.isNotBlank() }
}

/**
 * True if [end] may close the poll: MSC3381 only honours an end event from the poll's creator or
 * from a user with at least the room's redact power level. Ends from anyone else are ignored.
 */
fun isAuthorizedPollEnd(end: PollEndInfo, start: PollStartInfo, powerLevels: PowerLevelsInfo?): Boolean {
    if (end.sender == start.sender) return true
    if (powerLevels == null) return false
    val senderLevel = powerLevels.users[end.sender] ?: powerLevels.usersDefault
    return senderLevel >= powerLevels.redact
}

/**
 * Aggregates raw poll events into renderable [PollResults].
 *
 * MSC3381 rules applied here:
 * - One response *event* counts per user: the latest `origin_server_ts` wins, ties broken by event
 *   id so the outcome is deterministic. That single event carries a *set* of answer ids.
 * - Answer ids not defined by the poll start are dropped. If every id in a response is invalid the
 *   vote is spoiled and counts for nothing (as distinct from being a withdrawal, though both end up
 *   contributing no votes).
 * - A voter's selection is truncated to the first `max_selections` valid ids.
 * - An empty `answers` array withdraws the vote.
 * - The earliest *authorized* end event closes the poll; responses timestamped after it are ignored.
 * - An undisclosed poll hides its counts until it has ended.
 *
 * @param votes every response seen for this poll, in any order, including superseded ones.
 * @param localVote an unconfirmed optimistic vote by [myUserId]. Kept out of [votes] so it can never
 *   beat the server's own copy under the latest-wins rule; it is overlaid last instead.
 */
fun computePollResults(
    start: PollStartInfo,
    votes: List<PollVote>,
    ends: List<PollEndInfo>,
    myUserId: String?,
    powerLevels: PowerLevelsInfo? = null,
    localVote: PollVote? = null,
): PollResults {
    val validIds = start.answers.map { it.id }.toSet()

    // The earliest authorized end wins — a poll cannot be re-opened by a later end event.
    val effectiveEnd = ends
        .filter { isAuthorizedPollEnd(it, start, powerLevels) }
        .minWithOrNull(compareBy({ it.timestamp }, { it.eventId }))

    val latestByVoter = mutableMapOf<String, PollVote>()
    for (vote in votes) {
        if (effectiveEnd != null && vote.timestamp > effectiveEnd.timestamp) continue
        val existing = latestByVoter[vote.voter]
        if (existing == null || isNewerVote(vote, existing)) {
            latestByVoter[vote.voter] = vote
        }
    }

    val effectiveByVoter = mutableMapOf<String, Pair<PollVote, List<String>>>()
    for ((voter, vote) in latestByVoter) {
        val selected = validSelection(vote, validIds, start.maxSelections)
        if (selected.isNotEmpty()) {
            effectiveByVoter[voter] = vote to selected
        }
    }

    // The optimistic vote replaces whatever the server last told us about this user.
    var hasLocalVote = false
    if (localVote != null && myUserId != null) {
        val serverVote = latestByVoter[myUserId]
        // Once the server echoes a vote at least as new as the optimistic one, the optimism is spent.
        if (serverVote == null || !isNewerVote(serverVote, localVote)) {
            hasLocalVote = true
            val selected = validSelection(localVote, validIds, start.maxSelections)
            if (selected.isEmpty()) {
                effectiveByVoter.remove(myUserId)
            } else {
                effectiveByVoter[myUserId] = localVote to selected
            }
        }
    }

    val votesByAnswer = mutableMapOf<String, MutableList<PollVote>>()
    for ((vote, selected) in effectiveByVoter.values) {
        for (answerId in selected) {
            votesByAnswer.getOrPut(answerId) { mutableListOf() }.add(vote)
        }
    }
    // Stable, meaningful ordering for the voter list.
    val orderedVotesByAnswer = votesByAnswer.mapValues { (_, list) ->
        list.sortedWith(compareBy({ it.timestamp }, { it.voter }))
    }

    val myAnswerIds = myUserId?.let { effectiveByVoter[it]?.second?.toSet() } ?: emptySet()

    return PollResults(
        start = start,
        votesByAnswer = orderedVotesByAnswer,
        totalVoters = effectiveByVoter.size,
        myAnswerIds = myAnswerIds,
        endedAt = effectiveEnd?.timestamp,
        endedBy = effectiveEnd?.sender,
        resultsHidden = start.isUndisclosed && effectiveEnd == null,
        hasLocalVote = hasLocalVote,
    )
}

/** Latest-wins ordering: newer timestamp, then higher event id so ties resolve deterministically. */
private fun isNewerVote(candidate: PollVote, incumbent: PollVote): Boolean = when {
    candidate.timestamp != incumbent.timestamp -> candidate.timestamp > incumbent.timestamp
    else -> candidate.eventId > incumbent.eventId
}

/** Drops unknown answer ids and duplicates, then truncates to `max_selections`. */
private fun validSelection(vote: PollVote, validIds: Set<String>, maxSelections: Int): List<String> = vote.answerIds
    .asSequence()
    .filter { it in validIds }
    .distinct()
    .take(maxSelections)
    .toList()

/**
 * Question text from a poll start's *content* object (plain or decrypted), or null if the object is
 * not a poll start.
 *
 * The JSON-level entry point, for callers like `SpaceRoomParser` that work on raw sync JSON rather
 * than parsed [TimelineEvent]s.
 */
fun pollQuestionFromContent(content: JSONObject?): String? {
    if (content == null) return null
    val startContent = content.optJSONObject(POLL_START_UNSTABLE)
        ?: content.optJSONObject(POLL_START_STABLE)
        ?: return null
    return extractExtensibleText(startContent.optJSONObject("question"))
        ?: extractExtensibleText(content)
        ?: "Poll"
}

/** Room-list / notification preview label for a poll, e.g. "📊 Lunch?". */
fun pollPreviewLabel(question: String?): String = if (question.isNullOrBlank()) "📊 Poll" else "📊 $question"

/**
 * Short room-list/notification preview for a poll start, e.g. "📊 Lunch?".
 *
 * Returns null for non-poll events so callers can fall through to their existing handling.
 */
fun pollPreviewText(event: TimelineEvent): String? {
    val start = parsePollStart(event) ?: return null
    return pollPreviewLabel(start.question)
}
