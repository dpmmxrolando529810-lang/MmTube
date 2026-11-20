-keep class com.google.firebase.** { *; }
-keep class com.mmtube.admin.** { *; }
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName *;
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
