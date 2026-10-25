package com.autogram.app.domain.usecase

import com.autogram.app.infrastructure.github.GitHubAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val base = "https://github.com/PepeImpulso/autogram-releases/releases/download/v1.26.0/"

    private fun asset(name: String) = GitHubAsset(name = name, browser_download_url = base + name)

    @Test
    fun `release asset URL is trusted`() {
        assertTrue(CheckForUpdatesUseCase.isTrustedDownloadUrl(base + "autogram-1.26.0.apk"))
    }

    @Test
    fun `untrusted download URLs are rejected`() {
        listOf(
            "http://github.com/PepeImpulso/autogram-releases/releases/download/v1/a.apk",
            "https://evil.com/PepeImpulso/autogram-releases/releases/download/v1/a.apk",
            "https://github.com.evil.com/PepeImpulso/autogram-releases/releases/download/v1/a.apk",
            "https://github.com/Someone/autogram-releases/releases/download/v1/a.apk",
            "https://user@github.com/PepeImpulso/autogram-releases/releases/download/v1/a.apk",
            "https://github.com:8443/PepeImpulso/autogram-releases/releases/download/v1/a.apk",
            "https://github.com/PepeImpulso/autogram-releases/releases/download/../../x/a.apk",
            "not a url"
        ).forEach { assertFalse(it, CheckForUpdatesUseCase.isTrustedDownloadUrl(it)) }
    }

    @Test
    fun `picks exact release asset name`() {
        val assets = listOf(asset("other.apk"), asset("autogram-1.26.0-debug.apk"), asset("autogram-1.26.0.apk"))
        assertEquals("autogram-1.26.0.apk", CheckForUpdatesUseCase.pickApkAsset(assets, "1.26.0", debug = false)?.name)
        assertEquals("autogram-1.26.0-debug.apk", CheckForUpdatesUseCase.pickApkAsset(assets, "1.26.0", debug = true)?.name)
    }

    @Test
    fun `no fallback to an arbitrary apk`() {
        assertNull(CheckForUpdatesUseCase.pickApkAsset(listOf(asset("evil.apk")), "1.26.0", debug = false))
    }

    @Test
    fun `apk file name strips unsafe characters`() {
        assertEquals("autogram-1.26.0-rc.1.apk", CheckForUpdatesUseCase.apkFileName("1.26.0-rc.1"))
        assertEquals("autogram-..evil.apk", CheckForUpdatesUseCase.apkFileName("../evil"))
    }

    @Test
    fun `prerelease detection`() {
        assertTrue(CheckForUpdatesUseCase.isPrerelease("1.23.0-rc.5"))
        assertFalse(CheckForUpdatesUseCase.isPrerelease("1.25.9"))
    }
}
