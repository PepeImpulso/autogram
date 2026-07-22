package com.autogram.app.ui.phone

import com.autogram.app.BuildConfig

/** Matches the reusable, synthetic account reserved for Google Play review. */
internal fun isPlayReviewPhone(phone: String): Boolean =
    BuildConfig.ENABLE_PLAY_REVIEW_DEMO &&
        phone.trim().replace(" ", "") == BuildConfig.PLAY_REVIEW_PHONE
