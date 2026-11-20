# Reflection-heavy libraries used by MMTube.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}

# Firestore maps model classes reflectively, and kotlinx.serialization
# generates companion serializers that R8 must not strip.
-keep class com.mmtube.app.data.** { *; }
-keep class com.mmtube.app.download.DownloadRequest { *; }
-keep class com.mmtube.app.download.**$$serializer { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep,allowobfuscation,allowshrinking class * implements kotlinx.serialization.KSerializer

# WorkManager is instantiated by the system.
-keep class com.mmtube.app.data.PaymentStatusWorker { *; }
-keep class androidx.work.** { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-dontwarn org.slf4j.**
-dontwarn java.awt.**
