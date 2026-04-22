package com.autogram.app.infrastructure.telegram.mtproto

import org.drinkless.tdlib.TdApi
import timber.log.Timber

/**
 * TDLib's `MessageTopicForum.forumTopicId` is an `int` (32-bit). Forum topic
 * IDs fit in int32 per Telegram's spec, but our domain model stores them as
 * `Long` (which is what the Kotlin-side response wrappers expose). This
 * helper bridges the two with an explicit range check instead of a silent
 * `.toInt()` truncation that'd route the message to the wrong topic when
 * the value overflows.
 *
 * Returns null (signalling "send to chat without topic") when the topicId
 * doesn't fit in int32; an error is logged so we'd see it in Sentry if it
 * ever happened in the wild.
 */
internal fun Long?.toForumTopicOrNull(): TdApi.MessageTopicForum? {
    val tid = this ?: return null
    if (tid < Int.MIN_VALUE.toLong() || tid > Int.MAX_VALUE.toLong()) {
        Timber.e("Forum topic id $tid doesn't fit in int32 — routing to chat without topic")
        return null
    }
    return TdApi.MessageTopicForum(tid.toInt())
}
