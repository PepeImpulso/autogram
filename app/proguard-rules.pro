# Add project specific ProGuard rules here.

# Retrofit / Gson — used by the GitHub auto-update client.
-keepattributes Signature, Exceptions, *Annotation*
-keep interface com.autogram.app.infrastructure.github.GitHubApi { *; }
-keep class com.autogram.app.infrastructure.github.GitHubRelease { *; }
-keep class com.autogram.app.infrastructure.github.GitHubAsset { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class com.google.gson.** { *; }

# Backup payload serialized with Gson — field names must survive R8.
-keep class com.autogram.app.domain.usecase.ExportImportUseCase$BackupPayload { *; }
-keep class com.autogram.app.domain.model.AppSettings { *; }
-keep class com.autogram.app.domain.model.Conversation { *; }
-keep class com.autogram.app.domain.model.QuickReply { *; }

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

# v1.22.3 — MediaSessionCompat.Callback overrides MUST survive R8. Android
# Auto / Bluetooth car / lock-screen media controls dispatch transport
# callbacks (Play / Pause / Stop / Skip / PlayFromMediaId) by name, so any
# rename/strip silently breaks the buttons. Keep the compat callback +
# every subclass and its public methods.
-keep class android.support.v4.media.session.MediaSessionCompat$Callback { *; }
-keep class * extends android.support.v4.media.session.MediaSessionCompat$Callback {
    public *;
}
-keep class androidx.media.session.MediaButtonReceiver { *; }
# MediaBrowserServiceCompat exposes onLoadChildren / onGetRoot via the
# binder; the abstract overrides shouldn't be obfuscated.
-keep class androidx.media.MediaBrowserServiceCompat { *; }
-keep class * extends androidx.media.MediaBrowserServiceCompat {
    public *;
}

# TDLib — JNI uses reflection on TdApi classes, so keep everything in the
# org.drinkless.tdlib package intact (no renaming, no removal of fields).
-keep class org.drinkless.tdlib.** { *; }
-keepnames class org.drinkless.tdlib.**
-dontwarn org.drinkless.tdlib.**
