# Add project specific ProGuard rules here.

# Retrofit
-keepattributes Signature, Exceptions
-keep class com.autogram.app.infrastructure.telegram.TelegramApi { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramResponse { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramMessage { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramBotInfo { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramUpdate { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramChat { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramUser { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramVoice { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramFile { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramResponseParameters { *; }
-keep class com.autogram.app.infrastructure.telegram.TelegramWebhookInfo { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-dontwarn dagger.hilt.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Timber
-dontwarn timber.log.Timber
