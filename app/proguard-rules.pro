# Add project specific ProGuard rules here.

# Retrofit
-keepattributes Signature, Exceptions
-keep class com.autogram.app.infrastructure.telegram.** { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
