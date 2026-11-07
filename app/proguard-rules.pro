# yt-dlp / Jackson / Firebase are reflection heavy. Minification is disabled
# for this build, but these rules keep release builds safe if it is enabled.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn java.awt.**
