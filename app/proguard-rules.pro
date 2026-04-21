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

# App data classes used in notifications and caches
-keep class net.vrkknn.andromuks.NotificationData { *; }
-keep class net.vrkknn.andromuks.RoomItem { *; }
-keep class net.vrkknn.andromuks.FCMComponents { *; }
-keep class net.vrkknn.andromuks.utils.IntelligentMediaCache { *; }
-keep class net.vrkknn.andromuks.utils.MediaUtils { *; }
-keep class net.vrkknn.andromuks.utils.AvatarUtils { *; }
