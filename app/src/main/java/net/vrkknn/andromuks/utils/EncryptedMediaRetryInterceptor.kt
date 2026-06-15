package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Corrects a wrong `?encrypted=` flag on gomuks media requests.
 *
 * The app derives `?encrypted=true|false` from whether the *room* is E2EE, but that is only a
 * heuristic: an unencrypted message can be posted to an encrypted room (and vice versa), so the
 * flag can disagree with how the individual media was actually uploaded. When it does, the backend
 * returns one of two distinctive `M_NOT_FOUND` errors:
 *
 *  - "Tried to download encrypted media without encrypted flag"  → media IS encrypted, retry with `encrypted=true`
 *  - "Media encryption keys not found in cache"                  → media is NOT encrypted, retry with `encrypted=false`
 *
 * This interceptor detects either case on a failed response and retries once with the corrected
 * flag. It only ever issues a single extra request per call (there is no loop), and it skips the
 * retry when the flag is already the value it would set — so a genuinely missing/broken media item
 * can never cause an eternal flip-flop between `true` and `false`.
 *
 * Only requests to `/_gomuks/media/` are considered; everything else passes through untouched.
 */
class EncryptedMediaRetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only our media endpoint, and only when something went wrong.
        if (response.isSuccessful || !request.url.encodedPath.contains("/_gomuks/media/")) {
            return response
        }

        // Error bodies are tiny JSON; peek (don't consume) so the original body survives if we
        // decide not to retry.
        val body = try {
            response.peekBody(MAX_ERROR_BODY_BYTES).string()
        } catch (e: Exception) {
            return response
        }

        val desiredFlag = when {
            body.contains(ERR_NEEDS_ENCRYPTED) -> "true"
            body.contains(ERR_NEEDS_UNENCRYPTED) -> "false"
            else -> return response // not one of the flag-mismatch errors
        }

        // If the flag is already what we'd set, the media is genuinely unavailable — don't retry,
        // and crucially don't flip back (that would be the eternal flip-flop we must avoid).
        if (request.url.queryParameter("encrypted") == desiredFlag) {
            return response
        }

        val correctedUrl = request.url.newBuilder()
            .setQueryParameter("encrypted", desiredFlag)
            .build()
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "EncryptedMediaRetry: flipping encrypted->$desiredFlag for $correctedUrl")
        }

        // Free the failed response's connection before re-issuing.
        response.close()
        return chain.proceed(request.newBuilder().url(correctedUrl).build())
    }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        const val ERR_NEEDS_ENCRYPTED = "Tried to download encrypted media without encrypted flag"
        const val ERR_NEEDS_UNENCRYPTED = "Media encryption keys not found in cache"
    }
}
