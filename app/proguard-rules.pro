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
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Firebase / GMS
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# WebSocket / JSON
-keep class org.json.** { *; }

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

# App data classes used in notifications and caches
-keep class net.vrkknn.andromuks.NotificationData { *; }
-keep class net.vrkknn.andromuks.RoomItem { *; }
-keep class net.vrkknn.andromuks.FCMComponents { *; }
-keep class net.vrkknn.andromuks.utils.IntelligentMediaCache { *; }
-keep class net.vrkknn.andromuks.utils.MediaUtils { *; }
-keep class net.vrkknn.andromuks.utils.AvatarUtils { *; }
