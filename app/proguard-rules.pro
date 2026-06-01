# ProGuard rules for Wireless Mic

# Keep the main activity and Cast options provider
-keep class com.example.wirelessmic.MainActivity { *; }
-keep class com.example.wirelessmic.CastOptionsProvider { *; }

# Keep streaming classes
-keep class com.example.wirelessmic.streaming.** { *; }

# Keep audio classes that might be accessed via reflection
-keep class android.media.** { *; }

# Keep audio effects
-keep class android.media.audiofx.** { *; }

# Google Cast SDK
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-dontwarn com.google.android.gms.cast.**

# MediaRouter
-keep class androidx.mediarouter.** { *; }
-dontwarn androidx.mediarouter.**

# Standard Android optimizations
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Keep annotations
-keepattributes *Annotation*

# Keep source file names for better crash reports
-keepattributes SourceFile,LineNumberTable

# Kotlin specific
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
