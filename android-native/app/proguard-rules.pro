# mpv (dev.jdtech.mpv.MPVLib) reaches its Java class from native code, but
# that keep rule ships as a consumer rule inside the AAR itself and is merged
# in automatically — nothing to restate here.

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
