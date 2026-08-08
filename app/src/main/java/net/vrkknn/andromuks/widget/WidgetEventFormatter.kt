package net.vrkknn.andromuks.widget

import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import net.vrkknn.andromuks.utils.formatEventForReplyPreview
import org.json.JSONObject

/**
 * Turns a [TimelineEvent] into the single line the widget paints.
 *
 * This is a **thin wrapper** over [formatEventForReplyPreview], deliberately. That function already
 * knows every event shape this app renders — media ("📷 Sent a photo"), stickers, galleries, polls,
 * locations, membership changes, redactions — and keeping one vocabulary means the widget can never
 * drift from what a reply preview says about the same event. The wrapper exists so widget-only
 * concerns (edit resolution, reply-quote stripping, length capping) can be added without perturbing
 * reply previews.
 *
 * Everything here is pure — no Android, no network, no caches — which is what makes it unit-testable
 * (see `WidgetEventFormatterTest`, and the `PollFunctions` precedent in CLAUDE.md).
 */
object WidgetEventFormatter {
    /** Hard cap on a rendered line. Well past what two ellipsized widget rows can show. */
    private const val MAX_TEXT_LENGTH = 300

    /**
     * Build the display text for [event], applying [edits] if a newer version of it exists.
     *
     * @param edits `eventId -> m.new_content` map from [collectEdits].
     */
    fun format(event: TimelineEvent, edits: Map<String, JSONObject> = emptyMap()): String {
        // Redaction is checked here, not delegated: formatEventForReplyPreview deliberately leaves
        // it to its callers (the ReplyPreview composable resolves the redaction chain to name who
        // deleted what). A redacted event arrives with its content emptied, so without this the
        // widget would render a stale body or "Empty message" for a deleted message.
        if (event.redactedBy != null) return "Message deleted"

        val effective = edits[event.eventId]?.let { applyEdit(event, it) } ?: event
        val raw = formatEventForReplyPreview(effective, appViewModel = null, roomId = effective.roomId)
        return SpaceRoomParser.stripMatrixReplyQuote(raw).trim().let { text ->
            when {
                text.isEmpty() -> "Message"
                text.length > MAX_TEXT_LENGTH -> text.take(MAX_TEXT_LENGTH).trimEnd() + "…"
                else -> text
            }
        }
    }

    /**
     * Index the newest `m.replace` edit for each edited event id.
     *
     * The widget has no [net.vrkknn.andromuks.EditVersionCoordinator], so it resolves edits itself
     * from whatever the same paginate response contained. That is a deliberate limit, not an
     * oversight: an edit whose original is outside the fetched window simply doesn't apply, and an
     * edit arriving later over sync marks the snapshot stale and forces a full refetch (see
     * [RoomWidgetUpdater.onSyncEvents]). Within one response, "newest wins" is decided by
     * `timelineRowid` so that two edits of the same message resolve deterministically.
     */
    fun collectEdits(events: List<TimelineEvent>): Map<String, JSONObject> {
        val best = mutableMapOf<String, Pair<Long, JSONObject>>()
        events.asSequence()
            .filter { it.relationType == "m.replace" }
            .mapNotNull { event ->
                val target = event.relatesTo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val newContent = event.getMessagePayload()?.optJSONObject("m.new_content") ?: return@mapNotNull null
                Triple(target, event.timelineRowid, newContent)
            }
            .forEach { (target, rowid, newContent) ->
                val existing = best[target]
                if (existing == null || rowid > existing.first) best[target] = rowid to newContent
            }
        return best.mapValues { it.value.second }
    }

    /**
     * Rebuild [event] with [newContent] substituted for its body/msgtype.
     * Redacted events never reach here — [format] short-circuits them first.
     */
    private fun applyEdit(event: TimelineEvent, newContent: JSONObject): TimelineEvent {
        // Copy so the cached JSONObject the rest of the app shares is never mutated.
        val merged = JSONObject(newContent.toString())
        return if (event.type == "m.room.encrypted") {
            event.copy(decrypted = merged, decryptedType = event.decryptedType ?: "m.room.message")
        } else {
            event.copy(content = merged)
        }
    }
}
