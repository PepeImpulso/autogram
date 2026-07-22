package com.autogram.app.ui.phone

import com.autogram.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayReviewAccessTest {
    @Test
    fun `reserved phone follows the distribution setting`() {
        assertEquals(
            BuildConfig.ENABLE_PLAY_REVIEW_DEMO,
            isPlayReviewPhone(" ${BuildConfig.PLAY_REVIEW_PHONE} ")
        )
    }

    @Test
    fun `ordinary phone never opens the review demo`() {
        assertFalse(isPlayReviewPhone("+34600000000"))
    }
}
