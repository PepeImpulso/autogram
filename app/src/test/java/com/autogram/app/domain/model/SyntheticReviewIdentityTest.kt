package com.autogram.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticReviewIdentityTest {
    @Test fun `all legacy fixture destinations are synthetic`() {
        listOf("store-alex", "store-family", "store-project").forEach {
            assertTrue(SyntheticReviewIdentity.isSynthetic(it))
        }
    }

    @Test fun `real and missing identities are not classified by chat number or prefix`() {
        listOf(null, "", "10001", "store-other", "a3cfb364-b65a-42f5-a4ab-2b243a912ade").forEach {
            assertFalse(SyntheticReviewIdentity.isSynthetic(it))
        }
    }
}
