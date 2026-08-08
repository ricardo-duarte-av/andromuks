package net.vrkknn.andromuks.utils

import org.json.JSONObject

// Shared parsing for individual room-state event contents.
//
// These exist so there is exactly one implementation per state event type. Three separate copies of
// the topic fallback below had accumulated — in the room-header parser, the room-info parser and the
// invite parser — which is how they drifted apart in the first place.

/**
 * A room's topic from an `m.room.topic` content object.
 *
 * Prefers the plain `topic` string, falling back to the MSC3765 structured form
 * (`m.topic` → `m.text[0].body`) when it is missing or blank. Returns null when the room has no
 * usable topic — callers must treat that as "not observed", not as "the topic was cleared".
 */
fun parseRoomTopic(content: JSONObject?): String? {
    if (content == null) return null
    val plain = content.optString("topic").takeIf { it.isNotBlank() }
    if (plain != null) return plain
    val structured = content.optJSONObject("m.topic") ?: return null
    val text = structured.optJSONArray("m.text") ?: return null
    if (text.length() == 0) return null
    return text.optJSONObject(0)?.optString("body")?.takeIf { it.isNotBlank() }
}
