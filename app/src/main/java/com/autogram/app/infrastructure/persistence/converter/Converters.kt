package com.autogram.app.infrastructure.persistence.converter

import androidx.room.TypeConverter
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.PendingReason

/**
 * Conversores de tipos para Room (enums ↔ String, timestamps ↔ Long).
 */
class Converters {
    @TypeConverter
    fun fromMessageState(value: MessageState): String = value.name

    @TypeConverter
    fun toMessageState(value: String): MessageState = MessageState.valueOf(value)

    @TypeConverter
    fun fromMessageDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromPendingReason(value: PendingReason): String = value.name

    @TypeConverter
    fun toPendingReason(value: String): PendingReason = PendingReason.valueOf(value)
}
