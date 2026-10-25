package com.autogram.app.infrastructure.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a downloaded update APK before the system installer is launched
 * (issue #91). Android itself only rejects a different signing key when
 * the package name matches, so without this an APK with another package
 * name, an older versionCode or a tampered file would still be offered to
 * the user from inside Autogram.
 *
 * The expected signer is the *installed* app's own signer rather than a
 * hard-coded fingerprint: for release builds that is the release key, and
 * debug builds keep working against debug-signed assets.
 */
@Singleton
class ApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Package facts read from an APK archive or from the installed app. */
    data class ApkIdentity(
        val packageName: String,
        val versionCode: Long,
        /** Current signers (one entry unless the APK has multiple signers). */
        val currentSigners: Set<String>,
        /** Every cert the APK proves it may use, including v3 rotation lineage. */
        val signerHistory: Set<String>
    )

    sealed class Result {
        object Ok : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(apkFile: File, expectedSha256: String?): Result {
        val installed = readInstalled()
            ?: return Result.Rejected("cannot read installed package info")
        val candidate = readArchive(apkFile)
        val fileSha256 = runCatching { sha256Hex(apkFile) }
            .onFailure { Timber.w(it, "ApkVerifier: hashing failed") }
            .getOrNull()
        return evaluate(candidate, installed, fileSha256, expectedSha256)
    }

    private fun readInstalled(): ApkIdentity? = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .toIdentity()
    }.onFailure { Timber.w(it, "ApkVerifier: reading installed package failed") }.getOrNull()

    @VisibleForTesting
    internal fun readArchive(apkFile: File): ApkIdentity? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
        // Some platform versions leave signingInfo null for archives; the
        // legacy flag still reports the APK's current signer in that case.
        if (info?.signingInfo == null) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNATURES)?.toIdentity()
        } else {
            info.toIdentity()
        }
    }.onFailure { Timber.w(it, "ApkVerifier: reading archive failed") }.getOrNull()

    private fun PackageInfo.toIdentity(): ApkIdentity? {
        val si = signingInfo
        val (current, history) = if (si != null) {
            if (si.hasMultipleSigners()) {
                val all = si.apkContentsSigners.orEmpty().toHexSet()
                all to all
            } else {
                // History is ordered original → current.
                val hist = si.signingCertificateHistory.orEmpty()
                setOfNotNull(hist.lastOrNull()?.let { sha256Hex(it.toByteArray()) }) to hist.toHexSet()
            }
        } else {
            @Suppress("DEPRECATION")
            val legacy = signatures.orEmpty().toHexSet()
            legacy to legacy
        }
        if (current.isEmpty()) return null
        return ApkIdentity(packageName, longVersionCode, current, history)
    }

    private fun Array<out Signature?>.toHexSet(): Set<String> =
        mapNotNullTo(mutableSetOf()) { it?.let { s -> sha256Hex(s.toByteArray()) } }

    companion object {

        /**
         * Pure decision logic, kept free of Android types so it can be unit
         * tested. Every check fails closed: missing data is a rejection.
         */
        internal fun evaluate(
            candidate: ApkIdentity?,
            installed: ApkIdentity,
            fileSha256: String?,
            expectedSha256: String?
        ): Result {
            val expected = expectedSha256?.lowercase()
                ?: return Result.Rejected("release asset has no published SHA-256 digest")
            if (fileSha256 == null) return Result.Rejected("could not hash the downloaded file")
            if (!fileSha256.equals(expected, ignoreCase = true)) {
                return Result.Rejected("SHA-256 mismatch")
            }
            if (candidate == null) return Result.Rejected("downloaded file is not a readable APK")
            if (candidate.packageName != installed.packageName) {
                return Result.Rejected("unexpected package ${candidate.packageName}")
            }
            if (!isTrustedSigner(candidate, installed)) {
                return Result.Rejected("APK is not signed by the installed app's key")
            }
            if (candidate.versionCode <= installed.versionCode) {
                return Result.Rejected(
                    "versionCode ${candidate.versionCode} is not newer than ${installed.versionCode}"
                )
            }
            return Result.Ok
        }

        /**
         * Same signer set as the installed app, or (single signer) the
         * installed key appears in the candidate's v3 rotation lineage —
         * the same rule Android applies when it accepts a key rotation.
         */
        private fun isTrustedSigner(candidate: ApkIdentity, installed: ApkIdentity): Boolean {
            if (candidate.currentSigners == installed.currentSigners) return true
            return installed.currentSigners.size == 1 &&
                candidate.currentSigners.size == 1 &&
                installed.currentSigners.first() in candidate.signerHistory
        }

        /** Parses GitHub's asset `digest` field (`sha256:<hex>`); null for anything else. */
        internal fun parseSha256Digest(digest: String?): String? {
            val hex = digest?.trim()?.removePrefix("sha256:") ?: return null
            if (hex == digest.trim()) return null
            return hex.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }

        internal fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
