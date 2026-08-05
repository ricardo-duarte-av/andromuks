package net.vrkknn.andromuks.utils

import org.json.JSONObject

/*
 * Parsing for the gomuks `get_profile` RPC response.
 *
 * The backend used to return the Matrix profile object flat:
 *
 * ```json
 * { "displayname": "Alice", "avatar_url": "mxc://…", "m.tz": "Europe/Lisbon" }
 * ```
 *
 * It now wraps it and adds a sibling `bio` (gomuks `pkg/hicli/jsoncmd/responses.go`,
 * `GetProfileResponse`):
 *
 * ```json
 * {
 *   "profile": { "displayname": "Alice", "avatar_url": "mxc://…", "m.tz": "…" },
 *   "bio": { "html": "<p>hello</p>", "edit_source": "hello" }
 * }
 * ```
 *
 * `mautrix.RespUserProfile` inlines its extras, so every custom field we read (`m.tz`,
 * `us.cloke.msc4175.tz`, `io.fsky.nyx.pronouns`, `m.status`, `chat.commet.*`, …) still exists
 * — one level deeper. `bio` is gomuks' server-side rendering of MSC4440's biography field
 * (`gay.fomx.biography`, an MSC1767 `m.text` array): `html` is sanitized and linkified, and
 * `edit_source` is the markdown source, populated **only when the requested user is us**.
 *
 * This file is the only place that knows the wire shape — both parse sites
 * ([net.vrkknn.andromuks.MemberProfilesCoordinator.handleProfileResponse] and
 * [net.vrkknn.andromuks.AppViewModel.requestFullUserInfo]) go through
 * [parseGetProfileResponse]. Kept free of Android dependencies so it is unit-testable; see
 * `GetProfileResponseParsingTest`.
 */

/** Profile biography as rendered by the backend. [editSource] is null for other users. */
data class ProfileBioContent(val html: String, val editSource: String?)

/**
 * A parsed `get_profile` response.
 *
 * [displayName] and [avatarUrl] are `""` — never null — when the field is absent or blank.
 * That empty string is load-bearing: it means "fetched, and genuinely blank", which is what
 * `ProfileCache`'s "do we have it?" checks distinguish from "never fetched". See
 * docs/USER_PROFILES.md.
 */
data class ParsedProfile(
    val displayName: String,
    val avatarUrl: String,
    val timezone: String?,
    val pronouns: List<UserPronouns>?,
    val bio: ProfileBioContent?,
    val arbitraryFields: Map<String, Any>,
)

/**
 * Fields we lift into typed properties of [ParsedProfile]. Everything else in the profile
 * object is passed through as [ParsedProfile.arbitraryFields] for `UserInfoScreen` to render.
 *
 * `gay.fomx.biography` is listed here even though we never read it directly: the backend
 * already gives us the rendered form in `bio`, so leaving the raw MSC1767 object in the
 * arbitrary fields would render a second, unreadable copy of the biography.
 */
private val KNOWN_PROFILE_KEYS = setOf(
    "displayname",
    "avatar_url",
    "us.cloke.msc4175.tz",
    "m.tz",
    "io.fsky.nyx.pronouns",
    "gay.fomx.biography",
)

fun parseGetProfileResponse(data: JSONObject): ParsedProfile {
    // Nested when the backend supplies it; fall back to treating the response itself as the
    // profile so we keep working against a gomuks from before the wrapper was introduced.
    val profile = data.optJSONObject("profile") ?: data

    return ParsedProfile(
        displayName = profile.optString("displayname").takeIf { it.isNotBlank() && it != "null" } ?: "",
        avatarUrl = profile.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" } ?: "",
        // Prefer m.tz (standardized) over us.cloke.msc4175.tz (legacy).
        timezone = profile.optString("m.tz").takeIf { it.isNotBlank() }
            ?: profile.optString("us.cloke.msc4175.tz").takeIf { it.isNotBlank() },
        pronouns = parsePronouns(profile),
        // `bio` is a sibling of `profile`, so it is always read off the top level.
        bio = parseBio(data),
        arbitraryFields = parseArbitraryFields(profile),
    )
}

private fun parsePronouns(profile: JSONObject): List<UserPronouns>? {
    val array = profile.optJSONArray("io.fsky.nyx.pronouns") ?: return null
    val pronouns = mutableListOf<UserPronouns>()
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val summary = entry.optString("summary", "")
        if (summary.isNotBlank()) {
            pronouns.add(UserPronouns(language = entry.optString("language", "en"), summary = summary))
        }
    }
    return pronouns.takeIf { it.isNotEmpty() }
}

private fun parseBio(data: JSONObject): ProfileBioContent? {
    val bio = data.optJSONObject("bio") ?: return null
    val html = bio.optString("html").takeIf { it.isNotBlank() } ?: return null
    return ProfileBioContent(html = html, editSource = bio.optString("edit_source").takeIf { it.isNotBlank() })
}

private fun parseArbitraryFields(profile: JSONObject): Map<String, Any> {
    val fields = mutableMapOf<String, Any>()
    val keys = profile.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (KNOWN_PROFILE_KEYS.contains(key)) continue
        when (val value = profile.get(key)) {
            is org.json.JSONArray -> fields[key] = value
            is JSONObject -> fields[key] = value
            is String -> fields[key] = value
            is Number -> fields[key] = value
            is Boolean -> fields[key] = value
            else -> fields[key] = value.toString()
        }
    }
    return fields
}
