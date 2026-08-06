# libVLC reaches its Java classes from native code, so nothing under
# org.videolan can be renamed or stripped.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# kotlinx.serialization keeps the generated serializers on the companion.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit reads generic signatures off the service interfaces.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,allowobfuscation interface retrofit2.** { *; }
