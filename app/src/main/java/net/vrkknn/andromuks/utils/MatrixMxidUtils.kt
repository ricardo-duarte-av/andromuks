package net.vrkknn.andromuks.utils

/**
 * Shared helpers for Matrix user IDs (MXIDs): normalization, matrix.to URLs, and flexible user input.
 *
 * Extend this object over time as more input shapes are supported; keep call sites on these APIs
 * to avoid duplicating regex and URL handling.
 */
object MatrixMxidUtils {

    /**
     * Normalizes a Matrix user ID to canonical form with a leading `@`.
     * Trims whitespace. Empty input returns empty string.
     * Accepts `user:server` or `@user:server`.
     */
    fun normalizeMxid(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (t.startsWith("@")) t else "@$t"
    }

    /**
     * Extracts the first `@localpart:domain` user MXID embedded in a string (typically a matrix.to URL).
     * Handles URL-encoded fragments (e.g. `%40user%3Aserver`). Room links (`!room:server`) are not returned.
     */
    fun extractMxidFromMatrixToUrl(url: String): String? {
        val decoded = try {
            java.net.URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }
        val m = Regex("""(@[^:\s]+:[^\s)/?#]+)""").find(decoded)
        return m?.groupValues?.get(1)
    }

    /**
     * Resolves a matrix.to URL (or any string containing one) to a canonical user MXID, or null if none.
     * This is the URL-focused entry point; use [normalizeMxid] for plain MXIDs.
     */
    fun extractUsernameFromUrl(url: String): String? {
        return extractMxidFromMatrixToUrl(url)?.let { normalizeMxid(it) }
    }

    /**
     * Parses flexible user input: plain `@user:server` / `user:server`, a bare matrix.to user URL,
     * or Markdown `[label](https://matrix.to/#/@user:server)`.
     *
     * @return Normalized MXID, or empty string if the text referenced matrix.to but no user MXID
     *         could be found (e.g. room-only link).
     */
    fun parseFlexibleUserMxidInput(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val mdLink = Regex("""\[([^\]]*)\]\(\s*(https?://[^)\s]+)\s*\)""", RegexOption.IGNORE_CASE).find(t)
        if (mdLink != null) {
            val url = mdLink.groupValues[2]
            if (url.contains("matrix.to", ignoreCase = true)) {
                extractMxidFromMatrixToUrl(url)?.let { return normalizeMxid(it) }
                return ""
            }
        }
        if (t.contains("matrix.to", ignoreCase = true)) {
            extractMxidFromMatrixToUrl(t)?.let { return normalizeMxid(it) }
            return ""
        }
        return normalizeMxid(t)
    }
}
