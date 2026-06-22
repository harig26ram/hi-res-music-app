# Keep JavaScript Interface for WebView
-keepclassmembers class tf.monochrome.music.JsBlobReceiver {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep app components
-keep class tf.monochrome.music.AppExitReceiver { *; }
-keep class tf.monochrome.music.MusicService { *; }
-keep class tf.monochrome.music.MainActivity { *; }

# AndroidX Media
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep R8 rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# WebView
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# Network
-keep class java.net.** { *; }
-dontwarn java.net.**
-keep class javax.net.ssl.** { *; }
-dontwarn javax.net.ssl.**

# OkHttp (used by some AndroidX)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep data classes
-keep class tf.monochrome.music.Constants { *; }

# Prevent R8 from stripping interface information needed by androidx.media
-keep class android.support.v4.media.** { *; }
-keep class android.support.v4.media.session.** { *; }

# Media3 compatibility
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
