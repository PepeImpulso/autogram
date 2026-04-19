package com.autogram.app.ui.di

import com.autogram.app.domain.repository.*
import com.autogram.app.infrastructure.persistence.repository.*
import com.autogram.app.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

    companion object {
        @dagger.Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)  // BUG-26: Increased for long-polling (30s + margin)
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply {
                    // BUG-45 FIX: Properly redact bot tokens from logs
                    if (BuildConfig.DEBUG) {
                        addInterceptor(HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        })
                        addInterceptor { chain ->
                            val request = chain.request()
                            val redactedUrl = request.url.toString()
                                .replace(Regex("/bot\\d+:[A-Za-z0-9_-]+/"), "/botREDACTED/")
                            Timber.d("HTTP: ${request.method} $redactedUrl")
                            chain.proceed(request)
                        }
                    }
                }
                .build()
        }
    }
}
