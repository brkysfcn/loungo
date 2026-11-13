# WebView JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# NanoHTTPD WebSocket
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Server protocol and data classes
-keep class com.phantom.server.** { *; }
-keep class com.phantom.web.** { *; }
-keep class com.phantom.player.ResumeStore* { *; }
-keep class com.phantom.log.** { *; }

