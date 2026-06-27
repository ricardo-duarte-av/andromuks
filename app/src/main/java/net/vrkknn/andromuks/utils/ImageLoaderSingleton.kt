package net.vrkknn.andromuks.utils

import android.content.Context
import android.os.Build
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.getUserAgent
import coil3.ImageLoader
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * Singleton ImageLoader for the entire app
 * Configured with optimized memory and disk cache for avatar performance
 */
object ImageLoaderSingleton {
    @Volatile
    private var instance: ImageLoader? = null

    // The gomuks_auth session cookie received from /_gomuks/auth — same token the web
    // frontend uses for all media requests. Updated by AppViewModel.updateAuthToken and
    // pre-loaded from SharedPreferences by initFromStorage so it is available immediately
    // at startup before any composable renders.
    @Volatile var authToken: String = ""

    // Load the persisted session token so the interceptor has a valid cookie from the
    // very first image request, before the first composable sets authToken directly.
    fun initFromStorage(context: Context) {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
        authToken = net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(prefs)
    }

    // QUALITY IMPROVEMENT: Optimized cache settings for better quality
    // PERFORMANCE: Increased memory cache to keep more images loaded (supports 20 items above/below viewport)
    private const val MEMORY_CACHE_PERCENT = 0.35 // Increased to 35% to keep more avatars in memory
    private const val DISK_CACHE_SIZE_MB = 1024L // Persistent storage — keep reasonable

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: createImageLoader(context).also { instance = it }
        }
    }

    fun clearMemoryCache(context: Context) {
        synchronized(this) {
            instance?.memoryCache?.clear()
        }
    }

    private fun createImageLoader(context: Context): ImageLoader {
        val appContext = context.applicationContext
        // PERFORMANCE: Cap concurrent image loads to avoid runaway parallelism; queue the rest.
        // All MXC URLs map to the same host (backend/_gomuks/media/...), so maxRequestsPerHost
        // would otherwise cap effective concurrency at the per-host limit only.
        val dispatcher = Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 100
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", getUserAgent())
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor { chain ->
                val req = chain.request()
                // For our own media endpoint:
                // 1. Inject the gomuks_auth session cookie (same token the web frontend uses).
                // 2. Append ?encrypted=false when the parameter is absent — the backend requires
                //    it on every /_gomuks/media/* request. Callers handling E2EE media already
                //    append ?encrypted=true themselves; this covers everything else.
                val newReq = if (req.url.encodedPath.contains("/_gomuks/media/")) {
                    // Read live holder first; fall back to SharedPreferences for the narrow
                    // window before updateAuthToken has been called in the current process.
                    val token = authToken.takeIf { it.isNotBlank() }
                        ?: net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(appContext.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE))
                    var builder = req.newBuilder()
                    if (token.isNotBlank()) {
                        builder = builder.header("Cookie", "gomuks_auth=$token")
                    }
                    if (req.url.queryParameter("encrypted") == null) {
                        val newUrl = req.url.newBuilder().addQueryParameter("encrypted", "false").build()
                        builder = builder.url(newUrl)
                    }
                    val builtReq = builder.build()
                    if (BuildConfig.DEBUG) {
                        Log.d("Andromuks", "CoilInterceptor: url=${builtReq.url} cookie=${builtReq.header("Cookie")} tokenLen=${token.length}")
                    }
                    builtReq
                } else req
                chain.proceed(newReq)
            }
            // Added last so it sees the fully-resolved request (cookie + encrypted flag injected
            // above) and can retry with the opposite flag if the backend reports a mismatch.
            .addInterceptor(EncryptedMediaRetryInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(context)
            .components {
                // Coil 3: network loading is opt-in; route it through our shared OkHttpClient so the
                // auth-cookie / ?encrypted= interceptors apply. Replaces the old .okHttpClient(...).
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                // Coil 3: Builder() no longer takes a context; maxSizePercent does.
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                // Coil 3: DiskCache.directory takes an okio.Path, not a java.io.File.
                DiskCache.Builder()
                    .directory(context.filesDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_SIZE_MB * 1024 * 1024)
                    .build()
            }
            // Coil 3 no longer respects network cache headers by default (was respectCacheHeaders(false)).
            .build()
    }
}
