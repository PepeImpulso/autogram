package com.autogram.app.domain.model

/**
 * Reserved local identities used by the version-143 review demo and screenshots.
 * Their numeric chat IDs are fixtures, never verified Telegram destinations.
 * Keep this protection after demo exit and across upgrades: old outbox entries
 * can survive a session change and must not be replayed against a real account.
 */
object SyntheticReviewIdentity {
    const val PRIMARY = "store-alex"
    const val FAMILY = "store-family"
    const val PROJECT = "store-project"

    fun isSynthetic(conversationId: String?): Boolean =
        conversationId == PRIMARY || conversationId == FAMILY || conversationId == PROJECT
}
