package com.autogram.app.ui.di

import com.autogram.app.domain.repository.*
import com.autogram.app.infrastructure.persistence.repository.*
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import com.autogram.app.infrastructure.transcription.AndroidOnDeviceTranscriptionEngine
import com.autogram.app.infrastructure.transcription.TranscriptionEngine
import com.autogram.app.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindQuickReplyRepository(impl: QuickReplyRepositoryImpl): QuickReplyRepository

    @Binds @Singleton
    abstract fun bindCircleFolderRepository(impl: CircleFolderRepositoryImpl): CircleFolderRepository

    @Binds @Singleton
    abstract fun bindTdLibSender(impl: TdLibClient): TdLibSender

    @Binds @Singleton
    abstract fun bindTranscriptionEngine(impl: AndroidOnDeviceTranscriptionEngine): TranscriptionEngine

    companion object {
        @dagger.Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)  // BUG-26: Increased for long-polling (30s + margin)
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply {
                    // Debug-only HTTP tracing with aggressive redaction so
                    // tokens, API keys and bearer credentials never make it to
                    // logcat / Sentry breadcrumbs / support sessions. Replaces
                    // OkHttp's HttpLoggingInterceptor which would have logged
                    // the unredacted URL before any custom redactor could see
                    // it (issue #69). Release builds add no logging at all.
                    if (BuildConfig.DEBUG) {
                        addInterceptor { chain ->
                            val req = chain.request()
                            val redactedUrl = redactSensitive(req.url.toString())
                            Timber.d("HTTP -> ${req.method} $redactedUrl")
                            val started = System.nanoTime()
                            val resp = try {
                                chain.proceed(req)
                            } catch (t: Throwable) {
                                Timber.w(t, "HTTP !! ${req.method} $redactedUrl")
                                throw t
                            }
                            val tookMs = (System.nanoTime() - started) / 1_000_000
                            Timber.d("HTTP <- ${resp.code} $redactedUrl (${tookMs}ms)")
                            resp
                        }
                    }
                }
                .build()
        }

        private val BOT_TOKEN_RE = Regex("/bot\\d+:[A-Za-z0-9_-]+/")
        private val SENSITIVE_QUERY_RE = Regex(
            "([?&])(api[_-]?key|access[_-]?token|auth(?:orization)?|key|token|secret|sig|signature|password)=[^&]*",
            RegexOption.IGNORE_CASE
        )

        private fun redactSensitive(url: String): String =
            url.replace(BOT_TOKEN_RE, "/botREDACTED/")
                .replace(SENSITIVE_QUERY_RE, "$1$2=REDACTED")
    }
}
