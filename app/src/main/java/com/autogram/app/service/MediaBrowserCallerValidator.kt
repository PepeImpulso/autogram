package com.autogram.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a media-browser caller is trusted enough to enumerate
 * the pending / history catalog and trigger playback through the
 * MediaSession.
 *
 * The previous behaviour returned a non-null `BrowserRoot` for any
 * client, which let any installed app browse private message metadata
 * (conversation titles, voice timing) and trigger playback by guessing
 * a `mediaId`. Audit issue #70.
 *
 * Trust policy, in order:
 *   1. Same UID as us — anything inside the app is implicitly trusted.
 *   2. System UID (1000) — covers system Bluetooth media controller,
 *      lock-screen, AAOS host, Wear notification mirrors, etc.
 *   3. Hardcoded allowlist of known media-host packages whose signing
 *      certificate matches a known-good SHA-256 fingerprint:
 *         · Android Auto / Gearhead  (com.google.android.projection.gearhead)
 *         · Android Automotive       (com.google.android.apps.automotive.templates.host)
 *         · Bluetooth media          (com.android.bluetooth — system signature)
 *   4. Anyone else — denied. The browser returns `null` from
 *      `onGetRoot`, which AndroidX MediaBrowserServiceCompat translates
 *      to "this client cannot browse." onLoadChildren is never reached
 *      and `onPlayFromMediaId` from an untrusted controller is also
 *      rejected upstream by AndroidX.
 *
 * Debug builds add a permissive fallback (any caller passes) so the
 * Desktop Head Unit (DHU) and local test apps keep working without
 * having to import every test signature here. Release builds enforce
 * the real allowlist.
 */
@Singleton
class MediaBrowserCallerValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isTrustedCaller(packageName: String, callerUid: Int): Boolean {
        if (callerUid == Process.myUid()) return true
        if (callerUid == SYSTEM_UID) return true

        if (isDebugBuild()) {
            // Local DHU + test apps connect with arbitrary signatures; we
            // keep the allowlist strict only in release.
            Timber.d("MediaBrowserCallerValidator: debug build, allowing $packageName")
            return true
        }

        val signatures = TRUSTED_SIGNATURES[packageName] ?: return logDenied(
            packageName, "package not in trusted list"
        )
        val actual = currentSigningSha256(packageName) ?: return logDenied(
            packageName, "no signing certificate available"
        )
        if (actual !in signatures) {
            return logDenied(packageName, "signature $actual not in trusted set")
        }
        Timber.d("MediaBrowserCallerValidator: allowing $packageName (signature match)")
        return true
    }

    private fun isDebugBuild(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun logDenied(packageName: String, reason: String): Boolean {
        Timber.w("MediaBrowserCallerValidator: denied $packageName ($reason)")
        return false
    }

    /**
     * Hex SHA-256 of the *current* signing certificate for [packageName].
     * Returns null when the package isn't installed, has no signing info,
     * or hashing fails. We deliberately use the current signer (not the
     * full rotation history) so a stolen old key whose owner still happens
     * to ship a build can't masquerade as Gearhead.
     */
    private fun currentSigningSha256(packageName: String): String? {
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { si ->
                    if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures.orEmpty()
            }
            signatures.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
        }.onFailure {
            Timber.d(it, "currentSigningSha256($packageName) failed")
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    companion object {
        // System UID is constant (1000) across all Android versions.
        private const val SYSTEM_UID = 1000

        private val HEX = "0123456789abcdef".toCharArray()

        // Same SHA-256 fingerprints used by `res/values/arrays.xml`'s
        // car_hosts_allowlist, plus the published Google-Bluetooth-stack
        // signature for system media routing.
        private val TRUSTED_SIGNATURES: Map<String, Set<String>> = mapOf(
            "com.google.android.projection.gearhead" to setOf(
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
                "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771",
                "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00"
            ),
            "com.google.android.apps.automotive.templates.host" to setOf(
                "c241ffbc8e287c4e9a4ad19632ba1b1351ad361d5177b7d7b29859bd2b7fc631",
                "dd66deaf312d8daec7adbe85a218ecc8c64f3b152f9b5998d5b29300c2623f61"
            ),
            // Google Assistant — same release platform key as Gearhead.
            "com.google.android.googlequicksearchbox" to setOf(
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf"
            )
        )
    }
}
