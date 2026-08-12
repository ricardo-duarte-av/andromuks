package net.vrkknn.andromuks.utils

import java.security.MessageDigest

/**
 * MSC4391 command-description state keys.
 *
 * The `state_key` of an `org.matrix.msc4391.command_description` event is a deterministic function
 * of the command name and the advertising bot's MXID:
 *
 * ```
 * state_key = base64_standard_padded(sha256(command + sender_mxid))
 * ```
 *
 * This is not decoration. It is the MSC's only anti-squatting mechanism: because the key is derived
 * from the sender, one bot cannot overwrite another bot's description of the same command, and a
 * random room member cannot plant a description that appears to come from the bot. Verifying it on
 * ingest is therefore worth one digest per state event — see [stateKeyMatches].
 *
 * The exact concatenation is taken from the reference implementation, mautrix-go's
 * `event/cmdschema.EventContent.StateKey`, not from the MSC prose:
 * `sha256.Sum256([]byte(ec.Command + owner.String()))` then `base64.StdEncoding`. Standard
 * alphabet, padded, no line breaks.
 *
 * This file is deliberately free of Android dependencies so it can be unit tested directly
 * (`BotCommandStateKeyTest`). That is also why [base64Encode] is hand-rolled — see its doc.
 */

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Bits per base64 character, and the byte/char grouping the encoder walks in. */
private const val BITS_PER_CHAR = 6
private const val BYTES_PER_GROUP = 3
private const val CHARS_PER_GROUP = 4

/**
 * Standard-alphabet, padded Base64 (RFC 4648 §4).
 *
 * Hand-rolled on purpose, for two independent reasons:
 *
 *  - `java.util.Base64` is API 26 and this app's `minSdk` is 24.
 *  - `android.util.Base64` comes from the stubbed unit-test `android.jar`, and this module sets
 *    `testOptions.unitTests.isReturnDefaultValues = true` (see `app/build.gradle.kts`), so it would
 *    return `null` in tests. A state-key test would then pass while hashing nothing — exactly the
 *    silent-success failure mode that the real `org.json` dependency exists to prevent.
 *
 * `java.security.MessageDigest` has no such problem: it is a real JVM class in unit tests.
 */
internal fun base64Encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder((bytes.size + BYTES_PER_GROUP - 1) / BYTES_PER_GROUP * CHARS_PER_GROUP)
    var i = 0
    while (i < bytes.size) {
        val remaining = bytes.size - i
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (remaining > 1) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (remaining > 2) bytes[i + 2].toInt() and 0xFF else 0
        val triple = (b0 shl 16) or (b1 shl 8) or b2

        out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
        out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
        // With one input byte only the first two characters carry data; with two, the first three.
        out.append(if (remaining > 1) BASE64_ALPHABET[(triple ushr BITS_PER_CHAR) and 0x3F] else '=')
        out.append(if (remaining > 2) BASE64_ALPHABET[triple and 0x3F] else '=')

        i += BYTES_PER_GROUP
    }
    return out.toString()
}

/**
 * The `state_key` an MSC4391 command description must use, for [command] advertised by [sender].
 *
 * [command] is the space-separated command name as it appears in the event content ("rooms add"),
 * without any sigil. [sender] is the full MXID including the leading `@`.
 */
fun botCommandStateKey(command: String, sender: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((command + sender).toByteArray(Charsets.UTF_8))
    return base64Encode(digest)
}

/**
 * Whether [stateKey] is the key MSC4391 requires for this command/sender pair.
 *
 * Callers drop the description on `false`. The comparison is exact: the encoding is fully specified,
 * so a mismatch means either a bug in the advertising bot or an attempt to squat on the key.
 */
fun stateKeyMatches(stateKey: String, command: String, sender: String): Boolean =
    stateKey == botCommandStateKey(command, sender)
