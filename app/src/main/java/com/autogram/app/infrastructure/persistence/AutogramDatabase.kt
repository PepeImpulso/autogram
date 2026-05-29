package com.autogram.app.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
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
    version = 22,
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

    // v13 → v14: quick_replies gains kind/audioFilePath/audioDurationMs so
    // each shortcut can be either TEXT (original) or VOICE (pre-recorded
    // .ogg sent as a voice note). Existing rows keep kind='TEXT'. Also
    // adds settings.quickReplyFilter so the user can hide one kind from
    // the home-screen chip row.
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE quick_replies ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL("ALTER TABLE quick_replies ADD COLUMN audioFilePath TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE quick_replies ADD COLUMN audioDurationMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN quickReplyFilter TEXT NOT NULL DEFAULT 'ALL'")
        }
    }

    // v14 → v15: settings.keepScreenOn lets the user opt into FLAG_KEEP_SCREEN_ON
    // on the main screen so the device's screen-timeout doesn't dim the app
    // while listening to a queued reply or watching for an incoming one.
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN keepScreenOn INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v15 → v16: silentMode collapsed into autoPlayReplies. The column
    // stays in the schema for backward-compat (the entity still maps it,
    // but the domain model + PlayReplyUseCase ignore it from now on).
    // Existing users who had silentMode=ON or autoPlayReplies=OFF get
    // converted to autoPlayReplies=OFF — the new "queue for manual drain"
    // semantic. Users who had both off-by-default keep autoPlayReplies=ON.
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE settings SET autoPlayReplies = 0 " +
                    "WHERE silentMode = 1 OR autoPlayReplies = 0"
            )
        }
    }

    // v16 → v17: developerMode flag for the on-screen screenshot button
    // affordance (off by default — the chrome is opt-in).
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN developerMode INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v17 → v18: drop legacy ConversationEntity.botToken column. Bot tokens
    // were the auth model before the MTProto/TDLib migration; new rows have
    // been writing "" since that switch, but pre-MTProto rows could still
    // hold a real token in plaintext. SQLite < 3.35 doesn't support DROP
    // COLUMN directly, so we use the standard recreate-dance: copy data into
    // a new table without botToken, drop the old, rename. See issue #69.
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE conversations_new (
                    id TEXT NOT NULL,
                    targetChatId TEXT NOT NULL,
                    targetTitle TEXT,
                    isActive INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastMessageAt INTEGER,
                    topicId INTEGER,
                    topicName TEXT,
                    isPinned INTEGER NOT NULL,
                    photoLocalPath TEXT,
                    sortOrder INTEGER NOT NULL,
                    lastRepliedAt INTEGER,
                    folderId TEXT,
                    PRIMARY KEY(id)
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO conversations_new (
                    id, targetChatId, targetTitle, isActive, createdAt,
                    lastMessageAt, topicId, topicName, isPinned, photoLocalPath,
                    sortOrder, lastRepliedAt, folderId
                )
                SELECT
                    id, targetChatId, targetTitle, isActive, createdAt,
                    lastMessageAt, topicId, topicName, isPinned, photoLocalPath,
                    sortOrder, lastRepliedAt, folderId
                FROM conversations
            """.trimIndent())
            db.execSQL("DROP TABLE conversations")
            db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
        }
    }

    // v18 → v19: per-shortcut targeting. quick_replies gains an optional
    // targetConversationId so a shortcut can be pinned to a specific contact
    // — tapping it then sends there regardless of which circle is primary.
    // Null (the default) keeps the legacy "send to primary" behaviour.
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE quick_replies ADD COLUMN targetConversationId TEXT DEFAULT NULL"
            )
        }
    }

    // v19 → v20: AA audio routing diagnostics (issue #71). Two new settings
    // columns let the user cycle through audio-focus modes in the car if the
    // default doesn't reach the speakers. ttsAsNavigationPrompt re-routes
    // TTS through USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (the Google Maps
    // directions path) as a fallback for cars that mute USAGE_MEDIA when
    // another media app holds focus.
    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN audioFocusMode TEXT NOT NULL DEFAULT 'TRANSIENT_MAY_DUCK'"
            )
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN ttsAsNavigationPrompt INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    // v20 → v21: home shortcut visibility. Lets users keep their shortcut
    // definitions but hide the filter chips and/or shortcut buttons above
    // the primary contact for a cleaner Home screen.
    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN showQuickReplyFilters INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE settings ADD COLUMN showQuickReplyShortcuts INTEGER NOT NULL DEFAULT 1")
        }
    }

    // v21 → v22: secondary circle Home visibility. Defaults visible so
    // upgrades preserve the user's current Home screen exactly.
    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN showOnHome INTEGER NOT NULL DEFAULT 1")
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
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            // Fallback for any earlier schema (v1 / v2) that has no proper path
            // forward — those versions predate the stable 1.2 line and already
            // wiped once, so destroying from them is acceptable.
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * Hilt binding for the transaction seam. Wraps Room's
     * `withTransaction` so ExportImportUseCase (and future multi-row
     * writers) can run their block atomically without holding a
     * direct RoomDatabase reference — which would make them
     * untestable without an in-memory DB.
     */
    @Provides
    @Singleton
    fun provideTransactionRunner(db: AutogramDatabase): com.autogram.app.domain.usecase.TransactionRunner =
        object : com.autogram.app.domain.usecase.TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R =
                db.withTransaction { block() }
        }

    @Provides fun provideMessageDao(db: AutogramDatabase) = db.messageDao()
    @Provides fun provideConversationDao(db: AutogramDatabase) = db.conversationDao()
    @Provides fun provideSettingsDao(db: AutogramDatabase) = db.settingsDao()
    @Provides fun provideQuickReplyDao(db: AutogramDatabase) = db.quickReplyDao()
    @Provides fun provideDeliveryAttemptDao(db: AutogramDatabase) = db.deliveryAttemptDao()
    @Provides fun providePendingPlaybackDao(db: AutogramDatabase) = db.pendingPlaybackDao()
    @Provides fun provideCircleFolderDao(db: AutogramDatabase) = db.circleFolderDao()
}
