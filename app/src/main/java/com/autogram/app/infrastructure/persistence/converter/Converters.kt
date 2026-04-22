package com.autogram.app.infrastructure.persistence.converter

import androidx.room.TypeConverter
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.PendingReason
import com.autogram.app.domain.model.SummaryEngineType
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.model.TranscriptionState

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

    @TypeConverter
    fun fromTranscriptionState(value: TranscriptionState): String = value.name

    @TypeConverter
    fun toTranscriptionState(value: String): TranscriptionState = TranscriptionState.valueOf(value)

    @TypeConverter
    fun fromSummaryState(value: SummaryState): String = value.name

    @TypeConverter
    fun toSummaryState(value: String): SummaryState = SummaryState.valueOf(value)

    @TypeConverter
    fun fromSummaryEngineType(value: SummaryEngineType): String = value.name

    @TypeConverter
    fun toSummaryEngineType(value: String): SummaryEngineType = SummaryEngineType.valueOf(value)
}
