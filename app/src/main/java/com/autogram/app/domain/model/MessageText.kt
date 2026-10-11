package com.autogram.app.domain.model

/**
 * True for a Hermes exec-approval request: [Message.spokenText] holds the short
 * "review it in Telegram" notice and [Message.textContent] keeps the command.
 * Such messages must never expose the command on a spoken or car/phone surface,
 * and must never be answered by voice or dictation (issue #87).
 */
val Message.isApprovalNotice: Boolean
    get() = !spokenText.isNullOrBlank()

/**
 * Text to show or read for [this] on display/read-aloud surfaces. The approval
 * notice wins over everything so the command is never exposed; the original
 * stays in [Message.textContent] (History export, DB) untouched.
 */
fun Message.displayText(): String? =
    spokenText?.takeIf { it.isNotBlank() }
        ?: summary?.takeIf { it.isNotBlank() }
        ?: transcription?.takeIf { it.isNotBlank() }
        ?: textContent?.takeIf { it.isNotBlank() }

/**
 * The incoming message quick-reply suggestions are computed from, or null when
 * there is none. When the latest readable incoming message is an approval
 * request this returns null: the command must not be sent to the suggestion
 * LLM, and a suggested "Yes"/"OK" chip could be taken by Hermes as /approve.
 */
fun latestSuggestionSource(messages: Sequence<Message>, conversationId: String): Message? {
    val latest = messages
        .filter { it.conversationId == conversationId }
        .filter { it.direction == MessageDirection.INCOMING }
        .sortedByDescending { it.createdAt }
        .firstOrNull { !it.textContent.isNullOrBlank() || !it.transcription.isNullOrBlank() }
    return latest?.takeUnless { it.isApprovalNotice }
}

/** True when the newest incoming message is an approval notice (no dictated replies). */
fun List<Message>.latestIncomingIsApprovalNotice(): Boolean =
    filter { it.direction == MessageDirection.INCOMING }
        .maxByOrNull { it.createdAt }
        ?.isApprovalNotice == true

/**
 * Car-side guard: true when [conversationId]'s newest incoming message is an
 * approval notice. Android Auto must not offer or accept any reply (dictation,
 * quick reply, voice recording) in that state. A null id never matches.
 */
fun List<Message>.approvalPendingIn(conversationId: String?): Boolean =
    conversationId != null && filter { it.conversationId == conversationId }.latestIncomingIsApprovalNotice()
