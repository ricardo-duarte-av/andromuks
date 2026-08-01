package net.vrkknn.andromuks

/**
 * Counts how reply previews resolved their target event, so a future "Reply to unknown event" report
 * can be *read* rather than re-investigated from scratch.
 *
 * ## What the numbers mean
 *
 * The backend supplies a reply's target alongside the reply itself, in the `related_events` array of
 * `paginate` and `sync_complete` responses (with `timeline_rowid = -1` so it never renders as a
 * timeline row). Those land in [RoomTimelineCache]'s reply-context bucket. So in a healthy session
 * essentially every reply should resolve at [Tier.TIMELINE], [Tier.CACHE_TIMELINE] or
 * [Tier.CACHE_REPLY_CONTEXT].
 *
 * **A [Tier.FETCHED] is therefore a defect signal, not a success.** It means the designed path did not
 * cover this reply and the `get_event` failsafe had to run. It renders correctly — that is the point
 * of the failsafe — but it says some ingest path lost the context. Known culprits, all fixed but each
 * worth re-checking if the counter climbs:
 *
 * - reply context dropped on the sync path for a room that was not actively cached,
 * - a trimmed timeline event that a surviving reply still pointed at,
 * - a fetched `m.room.member` target rejected by the sync-ingest rowid filter,
 * - screens whose `timelineEvents` is a narrow slice and whose events never reach the cache
 *   (`EventContextScreen` still does not populate it).
 *
 * [Tier.UNRESOLVED] is the user-visible failure: the preview showed "Reply to unknown event".
 *
 * ## Cost
 *
 * Counters are plain ints behind a lock; each event ID is recorded at most once per tier, so
 * recomposition cannot inflate them. Only the two interesting tiers (fetched, unresolved) write an
 * [Androlog] entry, and a rolling summary is emitted every [SUMMARY_EVERY] resolutions — Androlog
 * caps at 200 entries, so a chatty per-resolution log would evict everything else.
 */
object ReplyResolutionTracker {

    /** How the reply preview got its target event. */
    enum class Tier {
        /** Found among the events the screen was already rendering. */
        TIMELINE,

        /** RoomTimelineCache, real timeline event. */
        CACHE_TIMELINE,

        /** RoomTimelineCache reply-context bucket — the backend's `related_events`. */
        CACHE_REPLY_CONTEXT,

        /** RoomTimelineCache reaction bucket. */
        CACHE_REACTION,

        /** RoomTimelineCache poll bucket (poll responses / ends). */
        CACHE_POLL,

        /** Needed a `get_event` round-trip. Expected to be rare; see the class note. */
        FETCHED,

        /** Not resolved at all — the preview rendered "Reply to unknown event". */
        UNRESOLVED,
    }

    private const val SUMMARY_EVERY = 100

    /** Bound on the de-duplication set, so a long session cannot grow it without limit. */
    private const val MAX_TRACKED_IDS = 2000

    private val lock = Any()
    private val counts = mutableMapOf<Tier, Int>()
    private val seen = LinkedHashSet<String>()
    private var total = 0

    /**
     * Record that [eventId] resolved at [tier]. Idempotent per (event, tier) pair, so it is safe to
     * call from a composable that recomposes freely.
     */
    fun record(tier: Tier, roomId: String, eventId: String) {
        val key = "$tier:$eventId"
        val summary: String?
        synchronized(lock) {
            if (!seen.add(key)) return
            if (seen.size > MAX_TRACKED_IDS) {
                val oldest = seen.iterator()
                oldest.next()
                oldest.remove()
            }
            counts[tier] = (counts[tier] ?: 0) + 1
            total++
            summary = if (total % SUMMARY_EVERY == 0) summaryLine() else null
        }

        when (tier) {
            // The failsafe ran: the backend's reply context did not cover this reply.
            Tier.FETCHED -> Androlog(
                "ReplyResolution",
                "get_event failsafe resolved reply target $eventId in $roomId " +
                    "(related_events did not cover it)",
            )

            // User-visible failure.
            Tier.UNRESOLVED -> Androlog(
                "ReplyResolution",
                "unresolved reply target $eventId in $roomId — preview shows \"Reply to unknown event\"",
            )

            else -> Unit
        }

        if (summary != null) Androlog("ReplyResolution", summary)
    }

    /** Snapshot of the counters, for a diagnostics UI. */
    fun snapshot(): Map<Tier, Int> = synchronized(lock) { counts.toMap() }

    /** One-line rollup, e.g. `resolved 100: timeline=71 cache=22 replyContext=6 fetched=1`. */
    fun summaryLine(): String = synchronized(lock) {
        buildString {
            append("resolved $total: ")
            append("timeline=${counts[Tier.TIMELINE] ?: 0} ")
            append("cache=${counts[Tier.CACHE_TIMELINE] ?: 0} ")
            append("replyContext=${counts[Tier.CACHE_REPLY_CONTEXT] ?: 0} ")
            append("reaction=${counts[Tier.CACHE_REACTION] ?: 0} ")
            append("fetched=${counts[Tier.FETCHED] ?: 0} ")
            append("unresolved=${counts[Tier.UNRESOLVED] ?: 0}")
        }
    }

    /** Test/diagnostics hook. */
    fun reset() {
        synchronized(lock) {
            counts.clear()
            seen.clear()
            total = 0
        }
    }
}
