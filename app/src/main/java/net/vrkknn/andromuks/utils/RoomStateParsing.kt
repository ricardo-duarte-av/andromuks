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

/** Non-blank strings from a JSON array field, or an empty list when absent. */
fun parseStringList(content: JSONObject?, key: String): List<String> {
    val array = content?.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        array.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
    }
    return out
}

/** All `key: Long` entries of a nested JSON object field, or an empty map when absent. */
private fun parseLongMap(content: JSONObject?, key: String): Map<String, Long> {
    val obj = content?.optJSONObject(key) ?: return emptyMap()
    val out = mutableMapOf<String, Long>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        out[k] = obj.optLong(k, 0)
    }
    return out
}

/**
 * Power levels from an `m.room.power_levels` content object.
 *
 * There were three of these, and they disagreed. The copy in UserInfo omitted `state_default`
 * entirely, so it silently fell back to the class default of 50 — meaning any permission derived
 * through that path (canPin among them) was computed against 50 rather than the room's actual state
 * default. Defaults here are the spec's: `users_default` and `events_default` are 0, everything else
 * is 50.
 *
 * Read as **Long** throughout: these were `optInt`, which truncates rather than clamps, so a
 * server-issued level near the canonical-JSON ceiling (Conduit gives a v12 creator
 * `9007199254740990`) came back as -2 and inverted every comparison made against it.
 */
fun parsePowerLevels(content: JSONObject?): net.vrkknn.andromuks.PowerLevelsInfo? {
    if (content == null) return null
    return net.vrkknn.andromuks.PowerLevelsInfo(
        users = parseLongMap(content, "users"),
        usersDefault = content.optLong("users_default", 0),
        redact = content.optLong("redact", 50),
        kick = content.optLong("kick", 50),
        ban = content.optLong("ban", 50),
        invite = content.optLong("invite", 50),
        events = parseLongMap(content, "events"),
        eventsDefault = content.optLong("events_default", 0),
        stateDefault = content.optLong("state_default", 50),
    )
}

/**
 * The room's members from a `get_room_state` response that asked for them.
 *
 * Deliberately **not** part of [net.vrkknn.andromuks.RoomState]: members are never cached, so this
 * is a transient returned alongside the parse for the one screen that shows them.
 *
 * Includes every membership — joined, invited, left and banned — because the room-info screen lists
 * invited and departed users too. Sorted joined-first, then invited, then alphabetically, which is
 * the order that screen renders.
 */
fun parseRoomMembers(events: org.json.JSONArray): List<RoomMember> {
    val byUser = LinkedHashMap<String, RoomMember>()
    for (i in 0 until events.length()) {
        // Last writer wins: a response can carry more than one member event per user.
        val member = toRoomMember(events.optJSONObject(i)) ?: continue
        byUser[member.userId] = member
    }
    return byUser.values.sortedWith(
        compareBy(
            { it.membership != "join" },
            { it.membership != "invite" },
            { it.displayName?.lowercase() ?: it.userId.lowercase() },
        ),
    )
}

/** Server ACL from an `m.room.server_acl` content object. */
fun parseServerAcl(content: JSONObject?): net.vrkknn.andromuks.ServerAclInfo? {
    if (content == null) return null
    return net.vrkknn.andromuks.ServerAclInfo(
        allow = parseStringList(content, "allow"),
        deny = parseStringList(content, "deny"),
        allowIpLiterals = content.optBoolean("allow_ip_literals", false),
    )
}

/** One member from an `m.room.member` state event, or null if it is neither. */
private fun toRoomMember(event: JSONObject?): RoomMember? {
    if (event == null || event.optString("type") != "m.room.member") return null
    val userId = event.optString("state_key").takeIf { it.isNotBlank() } ?: return null
    val content = event.optJSONObject("content")
    return RoomMember(
        userId = userId,
        displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
            ?: usernameFromMatrixId(userId),
        avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() },
        membership = content?.optString("membership")?.takeIf { it.isNotBlank() } ?: "leave",
    )
}
