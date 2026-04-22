package com.autogram.app.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.autogram.app.infrastructure.persistence.converter.Converters
import com.autogram.app.infrastructure.persistence.dao.*
import com.autogram.app.infrastructure.persistence.entity.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        DeliveryAttemptEntity::class,
        PendingPlaybackEntity::class,
        QuickReplyEntity::class,
        SettingsEntity::class,
        CircleFolderEntity::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AutogramDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun quickReplyDao(): QuickReplyDao
    abstract fun deliveryAttemptDao(): DeliveryAttemptDao
    abstract fun pendingPlaybackDao(): PendingPlaybackDao
    abstract fun circleFolderDao(): CircleFolderDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // Preserves user data (pinned circles, primary target, photos) across upgrades.
    // v3 → v4 adds settings.playbackSpeed.
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.25"
            )
        }
    }

    // v4 → v5 adds settings.recordingCuesVolume (0..100, 0 = cues off).
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN recordingCuesVolume INTEGER NOT NULL DEFAULT 50"
            )
        }
    }

    // v5 → v6 adds settings.languageTag (BCP-47, empty = follow system).
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN languageTag TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    // v6 → v7 adds settings.autoDeletePlayed (bool, default true — saves space
    // by dropping received .ogg files after the user has heard them).
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN autoDeletePlayed INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    // v7 → v8: M2 UX toggles — haptic cues, preview-before-send, biometric lock.
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN hapticEnabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE settings ADD COLUMN previewBeforeSend INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN biometricLockEnabled INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v8 → v9: onboardingCompleted flag for first-run wizard. Default 1 on
    // upgrade (users who already use the app skip the wizard), 0 on fresh
    // install so the wizard runs once.
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN onboardingCompleted INTEGER NOT NULL DEFAULT 1")
        }
    }

    // v9 → v10: transcription of incoming voice notes. Two columns on
    // messages (transcription text + state machine) and one toggle on
    // settings. All default to "off" / NONE so existing rows stay untouched.
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN transcription TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE messages ADD COLUMN transcriptionState TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE settings ADD COLUMN transcribeIncomingVoice INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v10 → v11: showPendingBadges toggle. Default 0 — the badges were
    // noisy for users who don't feel obligated to answer every incoming.
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN showPendingBadges INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v12 → v13: circle_folders table + conversations.folderId. Folders are
    // a pure-UX grouping (tabs on Main). Null folderId keeps existing circles
    // in the "All" tab without any visible change until the user creates
    // folders manually.
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN folderId TEXT DEFAULT NULL")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS circle_folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL DEFAULT 0,
                    sortOrder INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    // v11 → v12: summary fields on messages + summary engine config in settings.
    // Feature is off by default (summaryEngine=NONE); turning it on requires
    // the user to pick an engine in Settings → Summary.
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN summary TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE messages ADD COLUMN summaryState TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE settings ADD COLUMN summarizeLongMessages INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN summaryEngine TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE settings ADD COLUMN summaryLlmEndpoint TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE settings ADD COLUMN summaryLlmApiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE settings ADD COLUMN summaryLlmModel TEXT NOT NULL DEFAULT 'gpt-4o-mini'")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutogramDatabase {
        return Room.databaseBuilder(
            context,
            AutogramDatabase::class.java,
            "autogram.db"
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            // Fallback for any earlier schema (v1 / v2) that has no proper path
            // forward — those versions predate the stable 1.2 line and already
            // wiped once, so destroying from them is acceptable.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideMessageDao(db: AutogramDatabase) = db.messageDao()
    @Provides fun provideConversationDao(db: AutogramDatabase) = db.conversationDao()
    @Provides fun provideSettingsDao(db: AutogramDatabase) = db.settingsDao()
    @Provides fun provideQuickReplyDao(db: AutogramDatabase) = db.quickReplyDao()
    @Provides fun provideDeliveryAttemptDao(db: AutogramDatabase) = db.deliveryAttemptDao()
    @Provides fun providePendingPlaybackDao(db: AutogramDatabase) = db.pendingPlaybackDao()
    @Provides fun provideCircleFolderDao(db: AutogramDatabase) = db.circleFolderDao()
}
