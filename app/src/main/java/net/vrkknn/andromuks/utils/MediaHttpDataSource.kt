package net.vrkknn.andromuks.utils

import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

/**
 * ExoPlayer data sources for gomuks media (video/audio).
 *
 * ExoPlayer normally fetches over its own [androidx.media3.datasource.DefaultHttpDataSource]
 * (HttpURLConnection), which bypasses Coil and our OkHttp interceptors. Routing it through an
 * [OkHttpDataSource] backed by [client] makes streaming media flow through the same
 * [EncryptedMediaRetryInterceptor] as images, so a wrong `?encrypted=` flag is auto-corrected here
 * too. The User-Agent matches the rest of the app's media requests.
 */
object MediaHttpDataSource {

    @Volatile
    private var client: OkHttpClient? = null

    private fun client(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", getUserAgent())
                        .build()
                )
            }
            .addInterceptor(EncryptedMediaRetryInterceptor())
            .build()
            .also { client = it }
    }

    /**
     * Build an [OkHttpDataSource.Factory] carrying the gomuks auth cookie. Each player builds its
     * own factory (cheap) with the current [authToken]; the underlying OkHttpClient is shared.
     */
    fun factory(authToken: String): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client())
            .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
}
