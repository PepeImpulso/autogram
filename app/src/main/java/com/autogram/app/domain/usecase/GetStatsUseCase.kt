package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class Stats(
    val totalSent: Int,
    val totalReceived: Int,
    val totalFailed: Int,
    val successRate: Float,           // 0..1; 1 == 100%
    val avgDurationMs: Long,
    val last7Days: List<DailyCount>   // ordered oldest → newest
)

data class DailyCount(val label: String, val sent: Int, val received: Int)

class GetStatsUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    fun observe(): Flow<Stats> =
        messageRepository.observeAllAcrossConversations().map { computeStats(it) }

    fun computeStats(messages: List<Message>): Stats {
        val sent = messages.count { it.direction == MessageDirection.OUTGOING && it.state == MessageState.SENT }
        val received = messages.count { it.direction == MessageDirection.INCOMING }
        val failed = messages.count {
            it.direction == MessageDirection.OUTGOING &&
                (it.state == MessageState.FAILED || it.state == MessageState.PERMANENTLY_FAILED)
        }
        val totalAttempts = sent + failed
        val successRate = if (totalAttempts == 0) 0f else sent.toFloat() / totalAttempts
        val durations = messages.mapNotNull { it.audioDurationMs }.filter { it > 0 }
        val avg = if (durations.isEmpty()) 0L else durations.average().toLong()
        return Stats(
            totalSent = sent,
            totalReceived = received,
            totalFailed = failed,
            successRate = successRate,
            avgDurationMs = avg,
            last7Days = computeLast7Days(messages)
        )
    }

    private fun computeLast7Days(messages: List<Message>): List<DailyCount> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val today = cal.timeInMillis
        val labelFmt = SimpleDateFormat("EEE d", Locale.getDefault())
        val dayMs = 24L * 60L * 60L * 1000L
        return (6 downTo 0).map { offset ->
            val start = today - offset * dayMs
            val end = start + dayMs
            val sent = messages.count {
                it.direction == MessageDirection.OUTGOING &&
                    it.state == MessageState.SENT &&
                    it.createdAt in start until end
            }
            val received = messages.count {
                it.direction == MessageDirection.INCOMING &&
                    it.createdAt in start until end
            }
            DailyCount(label = labelFmt.format(Date(start)), sent = sent, received = received)
        }
    }
}
