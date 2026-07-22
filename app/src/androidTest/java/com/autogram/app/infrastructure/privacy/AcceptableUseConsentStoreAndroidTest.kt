package com.autogram.app.infrastructure.privacy

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcceptableUseConsentStoreAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: AcceptableUseConsentStore

    @Before
    fun setUp() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = AcceptableUseConsentStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun acceptancePersistsForTheCurrentRulesVersion() {
        assertFalse(store.isAccepted())

        store.accept()

        assertTrue(AcceptableUseConsentStore(context).isAccepted())
    }

    private companion object {
        const val FILE_NAME = "acceptable_use_consent"
    }
}
