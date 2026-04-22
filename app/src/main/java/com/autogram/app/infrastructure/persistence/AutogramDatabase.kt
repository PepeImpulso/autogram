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
        SettingsEntity::class
    ],
    version = 10,
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutogramDatabase {
        return Room.databaseBuilder(
            context,
            AutogramDatabase::class.java,
            "autogram.db"
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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
}
