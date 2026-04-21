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
    version = 6,
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutogramDatabase {
        return Room.databaseBuilder(
            context,
            AutogramDatabase::class.java,
            "autogram.db"
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
