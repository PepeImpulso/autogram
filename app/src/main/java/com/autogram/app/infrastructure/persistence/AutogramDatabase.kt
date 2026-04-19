package com.autogram.app.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutogramDatabase {
        return Room.databaseBuilder(
            context,
            AutogramDatabase::class.java,
            "autogram.db"
        ).fallbackToDestructiveMigration()  // BUG-31: Prevent crash on schema changes during dev
            .build()
    }

    @Provides fun provideMessageDao(db: AutogramDatabase) = db.messageDao()
    @Provides fun provideConversationDao(db: AutogramDatabase) = db.conversationDao()
    @Provides fun provideSettingsDao(db: AutogramDatabase) = db.settingsDao()
    @Provides fun provideQuickReplyDao(db: AutogramDatabase) = db.quickReplyDao()
    @Provides fun provideDeliveryAttemptDao(db: AutogramDatabase) = db.deliveryAttemptDao()
    @Provides fun providePendingPlaybackDao(db: AutogramDatabase) = db.pendingPlaybackDao()
}
