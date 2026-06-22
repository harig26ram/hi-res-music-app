-keepclassmembers class tf.monochrome.music.JsBlobReceiver {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.media.** { *; }
-keep class tf.monochrome.music.AppExitReceiver { *; }
-keep class tf.monochrome.music.MusicService { *; }
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Optimize WebView
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# Optimize network
-keep class java.net.** { *; }
-dontwarn java.net.**

# Remove logging
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
