# Preserve source file names and line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin coroutines — R8 can break suspend lambdas without these
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    public final java.lang.Object resumeWith(java.lang.Object);
}
-dontwarn kotlinx.coroutines.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    static **$$serializer INSTANCE;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.vrkknn.andromuks.**$$serializer { *; }
-keepclassmembers class net.vrkknn.andromuks.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JLaTeXMath (MSC2191 maths rendering). The library loads fonts and TeX symbol/glyph
# definitions reflectively from bundled resources; keep its classes and members so R8
# doesn't strip the font loaders or the resource-backed symbol tables.
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.scilab.forge.jlatexmath.**

# OkHttp
#
# NOTE: `-keep class okhttp3.** { *; }` and the matching `-keep interface` were removed here.
# OkHttp ships its own consumer ProGuard rules, so the blanket keeps were redundant — and
# actively harmful: keeping every member of a library this size prevents R8 from shrinking it,
# and blocks inlining and class merging across it. The PublicSuffixDatabase keepnames below IS
# still required (OkHttp looks it up by name to load its bundled resource).
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Firebase / GMS
#
# Same reasoning: the blanket `-keep class com.google.firebase.**` / `com.google.android.gms.**`
# rules were removed. Both ship consumer rules covering their own reflective entry points
# (Firebase's component discovery, Crashlytics' reporting, the Maps/Location service bindings),
# and this app performs no reflection of its own — verified: there are zero Class.forName /
# getMethod / newInstance call sites in app/src/main/java. GMS in particular is very large, so
# keeping every member had a real APK-size cost.
#
# The `-dontwarn` lines stay: they suppress build-time noise about optional transitive APIs and
# are unrelated to shrinking.
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# NOTE: `-keep class org.json.** { *; }` was removed. org.json ships in the Android framework
# (android.jar), not in the APK, so R8 never had those classes to shrink — the rule was a no-op.

# Strip android.util.Log debug/verbose calls in release builds.
#
# `-assumenosideeffects` tells R8 that these methods have no observable side effects,
# so it can eliminate the calls AND the argument-construction code feeding them
# (string templates, joinToString, etc.) as dead code. This is the standard,
# documented way to remove debug logging from release APKs.
#
# Log.i is kept — info-level messages (e.g. "WebSocket connected", "FCM token ready")
# are intentionally release-visible and useful in user-supplied logcat dumps.
# Log.w and Log.e are obviously kept — warnings and errors must always log.
#
# Note: arguments with real side effects (e.g. `Log.d("t", computeAndMutate())`) will
# still have the side-effecting call preserved by R8; only pure argument construction
# is eliminated. None of our current Log.d call sites embed side effects in arguments.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static int isLoggable(java.lang.String, int);
}

# App data classes used in notifications and caches.
#
# Deliberately NOT narrowed in the same pass that removed the library-wide keeps above. These are
# small, and narrowing them needs case-by-case checking of what actually crosses a reflective or
# Parcelable boundary — a separate change with a separate blast radius. Keeping them means a
# release-only failure after this commit almost certainly points at a library rule, not at these.
-keep class net.vrkknn.andromuks.NotificationData { *; }
-keep class net.vrkknn.andromuks.RoomItem { *; }
-keep class net.vrkknn.andromuks.FCMComponents { *; }
-keep class net.vrkknn.andromuks.utils.IntelligentMediaCache { *; }
-keep class net.vrkknn.andromuks.utils.MediaUtils { *; }
-keep class net.vrkknn.andromuks.utils.AvatarUtils { *; }
