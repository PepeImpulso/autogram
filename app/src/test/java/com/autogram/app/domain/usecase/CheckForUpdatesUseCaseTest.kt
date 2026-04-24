package com.autogram.app.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckForUpdatesUseCaseTest {

    @Test
    fun `stable patch release beats previous patch`() {
        assertTrue(CheckForUpdatesUseCase.isNewerVersion("1.20.5", "1.20.4"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(CheckForUpdatesUseCase.isNewerVersion("1.20.4", "1.20.4"))
    }

    @Test
    fun `stable release beats prerelease with same base`() {
        assertTrue(CheckForUpdatesUseCase.isNewerVersion("1.20.5", "1.20.5-beta.2"))
    }

    @Test
    fun `prerelease does not beat stable with same base`() {
        assertFalse(CheckForUpdatesUseCase.isNewerVersion("1.20.5-beta.2", "1.20.5"))
    }

    @Test
    fun `numeric prerelease suffix sorts numerically`() {
        assertTrue(CheckForUpdatesUseCase.isNewerVersion("1.20.5-beta.10", "1.20.5-beta.2"))
    }

    @Test
    fun `leading v is stripped case insensitively`() {
        assertTrue(CheckForUpdatesUseCase.stripLeadingV("v1.20.5") == "1.20.5")
        assertTrue(CheckForUpdatesUseCase.stripLeadingV("V1.20.5") == "1.20.5")
    }
}
