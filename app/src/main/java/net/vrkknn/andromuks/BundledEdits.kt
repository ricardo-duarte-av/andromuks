package net.vrkknn.andromuks

/*
 * Helpers for **bundled edits**: `m.replace` events that reach the client only through a response's
 * `related_events`, never as a timeline row.
 *
 * Since gomuks 07b3e23 (2026-09-20) the backend stores the latest edit the homeserver bundles into
 * an event's `unsigned.m.relations`, and every paginate / context / thread / search / mentions
 * response appends that edit to `related_events` when it is not already in the response window.
 * `sync_complete` does not deliver it at all. So for an edit made inside a sync gap, the
 * `related_events` copy is the only one the client will ever see. See docs/TIMELINE_EVENTS.md.
 */

/** The event an `m.replace` edit targets, or null when [event] is not an edit. */
internal fun editTargetOf(event: TimelineEvent): String? {
    val relatesTo = when {
        event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")

        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" ->
            event.decrypted?.optJSONObject("m.relates_to")

        else -> null
    }
    if (relatesTo?.optString("rel_type") == "m.replace") {
        relatesTo.optString("event_id").takeIf { it.isNotBlank() }?.let { return it }
    }
    if (event.relationType == "m.replace") return event.relatesTo?.takeIf { it.isNotBlank() }
    return null
}

/** The newest edit per target event among [events]; non-edits are ignored. */
internal fun latestEditsByTarget(events: Iterable<TimelineEvent>): Map<String, TimelineEvent> {
    val latest = mutableMapOf<String, TimelineEvent>()
    for (event in events) {
        val target = editTargetOf(event) ?: continue
        val existing = latest[target]
        if (existing == null || event.timestamp > existing.timestamp) latest[target] = event
    }
    return latest
}
