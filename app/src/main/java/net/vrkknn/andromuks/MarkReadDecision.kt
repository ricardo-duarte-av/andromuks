package net.vrkknn.andromuks

/**
 * A read-receipt position the app has already asked the backend to move to.
 *
 * [timelineRowid] is gomuks' monotonic insertion order — the same ordering authority
 * `TimelineCacheCoordinator.latestRowidEventId` uses to pick a target. [ROWID_UNKNOWN] means the
 * event wasn't in the timeline cache when the target was built (a notification action, or the
 * `RoomListCache.getLatestEventId` fallback), so nothing can be concluded about its position.
 */
data class MarkReadTarget(val eventId: String, val timelineRowid: Long = ROWID_UNKNOWN) {
    companion object {
        /** Real gomuks timeline rowids are strictly positive; 0 stands for "we don't know". */
        const val ROWID_UNKNOWN = 0L
    }
}

/** What [decideMarkRead] concluded about a candidate `mark_read`. */
sealed interface MarkReadDecision {
    /** Send `mark_read` and record [target] as the room's new last-sent position. */
    data class Send(val target: MarkReadTarget) : MarkReadDecision

    /** Don't send. [reason] is a short slug for the Androlog line. */
    data class Suppress(val reason: String) : MarkReadDecision
}

/**
 * Decides whether a `mark_read` for [candidate] should actually go out.
 *
 * Two rules, both learned from a room whose unread badge could not be cleared by re-opening it:
 *
 * 1. **Never rewind.** Targets come from several places and not all of them are rowid-ordered (the
 *    `RoomListCache` fallbacks advance on timestamp, notification actions carry the *notification's*
 *    event). A target that lands behind one we already sent makes the backend recompute the room as
 *    unread, and nothing in the room-open path would ever correct it.
 * 2. **A repeat of the same event is only redundant if the room is actually read.** The old guard
 *    suppressed every repeat unconditionally and was never invalidated, so one bad receipt wedged
 *    the room as unread for the rest of the process: re-opening it cleared the badge in memory and
 *    sent nothing. [roomConfirmedRead] is the escape hatch — while the backend still reports unread
 *    for this room, re-opening it re-sends.
 */
fun decideMarkRead(candidate: MarkReadTarget, lastSent: MarkReadTarget?, roomConfirmedRead: Boolean): MarkReadDecision {
    if (lastSent == null) return MarkReadDecision.Send(candidate)

    val bothKnown = candidate.timelineRowid > MarkReadTarget.ROWID_UNKNOWN &&
        lastSent.timelineRowid > MarkReadTarget.ROWID_UNKNOWN
    if (bothKnown && candidate.timelineRowid < lastSent.timelineRowid) {
        return MarkReadDecision.Suppress("rewind")
    }

    if (candidate.eventId == lastSent.eventId && roomConfirmedRead) {
        return MarkReadDecision.Suppress("duplicate")
    }

    return MarkReadDecision.Send(candidate)
}
