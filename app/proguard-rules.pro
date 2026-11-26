# Orin release rules

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.orin.app.**$$serializer { *; }
-keepclassmembers class com.orin.app.** { *** Companion; }
-keepclasseswithmembers class com.orin.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# OkHttp optional TLS providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (pulled in by androidx.security.crypto) references compile-time-only
# error-prone annotations that are not shipped in the APK.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
