package com.autogram.app.domain.usecase

import com.autogram.app.BuildConfig
import com.autogram.app.infrastructure.github.GitHubApi
import com.autogram.app.infrastructure.github.GitHubAsset
import com.autogram.app.infrastructure.platform.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val latestVersionName: String,
    val releaseUrl: String,
    val releaseNotes: String?,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val apkSha256: String?,
    val publishedAt: String?
)

class CheckForUpdatesUseCase @Inject constructor(
    private val gitHubApi: GitHubApi
) {
    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val releases = gitHubApi.listReleases(OWNER, REPO)
            val current = BuildConfig.VERSION_NAME
            // Stable builds only follow stable releases; a build that is
            // itself a prerelease keeps following prereleases (issue #93).
            val allowPrerelease = isPrerelease(current)
            // Sort releases by our semver comparator so "1.1.0-alpha.10" beats
            // "1.1.0-alpha.2" (plain string compare would get this wrong).
            val release = releases
                .filter { !it.draft }
                .filter { allowPrerelease || (!it.prerelease && !isPrerelease(stripLeadingV(it.tag_name))) }
                .maxWithOrNull(Comparator { a, b ->
                    val av = stripLeadingV(a.tag_name)
                    val bv = stripLeadingV(b.tag_name)
                    when {
                        av == bv -> 0
                        isNewerVersion(av, bv) -> 1
                        else -> -1
                    }
                })
                ?: return@withContext Result.failure(Exception("No hay releases publicadas"))
            val latest = stripLeadingV(release.tag_name)
            val isNewer = isNewerVersion(latest, current)

            // Pick the APK matching the currently installed variant.
            // Debug builds can only be replaced by debug-signed APKs (different cert),
            // so we pick -debug.apk for them; release builds grab the non-debug one.
            // An asset with an unexpected download URL is treated as missing.
            val apkAsset = pickApkAsset(release.assets, latest, BuildConfig.DEBUG)
                ?.takeIf { isTrustedDownloadUrl(it.browser_download_url) }

            Timber.d("CheckForUpdates: current=$current latest=$latest newer=$isNewer apk=${apkAsset?.name}")

            Result.success(
                UpdateInfo(
                    isUpdateAvailable = isNewer,
                    latestVersion = latest,
                    latestVersionName = release.name ?: release.tag_name,
                    releaseUrl = release.html_url,
                    releaseNotes = release.body,
                    apkUrl = apkAsset?.browser_download_url,
                    apkSizeBytes = apkAsset?.size ?: 0,
                    apkSha256 = ApkVerifier.parseSha256Digest(apkAsset?.digest),
                    publishedAt = release.published_at
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "CheckForUpdates failed")
            Result.failure(e)
        }
    }

    companion object {
        // Public binary-only repo that the in-app updater installs from.
        private const val OWNER = "PepeImpulso"
        private const val REPO = "autogram-releases"
        private const val DOWNLOAD_PATH_PREFIX = "/$OWNER/$REPO/releases/download/"

        internal fun isPrerelease(version: String): Boolean = splitPre(version).second != null

        /**
         * Only https downloads from this repo's release assets are accepted
         * (issue #92). DownloadManager follows redirects, so the starting URL
         * is the one we can pin.
         */
        internal fun isTrustedDownloadUrl(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.port == -1 &&
                uri.userInfo == null &&
                uri.rawPath.orEmpty().startsWith(DOWNLOAD_PATH_PREFIX) &&
                ".." !in uri.rawPath
        }

        /** Local file name for a downloaded update, restricted to `[0-9A-Za-z.-]`. */
        internal fun apkFileName(version: String): String =
            "autogram-${version.replace(Regex("[^0-9A-Za-z.-]"), "")}.apk"

        internal fun stripLeadingV(tag: String): String = tag.removePrefix("v").removePrefix("V")

        /**
         * Semver-ish comparator that understands `major.minor.patch[-preid.N]`.
         * A non-prerelease is considered greater than a prerelease of the same
         * base version (1.0.0 > 1.0.0-beta.5). Prerelease ids are compared
         * lexicographically; the trailing number (beta.1 vs beta.2) numerically.
         */
        internal fun isNewerVersion(latest: String, current: String): Boolean {
            if (latest == current) return false
            val (lBase, lPre) = splitPre(latest)
            val (cBase, cPre) = splitPre(current)

            val lParts = lBase.split('.').mapNotNull { it.toIntOrNull() }
            val cParts = cBase.split('.').mapNotNull { it.toIntOrNull() }
            val max = maxOf(lParts.size, cParts.size)
            for (i in 0 until max) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l != c) return l > c
            }

            // Base versions equal — compare pre-release identifiers.
            return comparePre(lPre, cPre) > 0
        }

        private fun splitPre(v: String): Pair<String, String?> {
            val i = v.indexOf('-')
            return if (i < 0) v to null else v.substring(0, i) to v.substring(i + 1)
        }

        private fun comparePre(l: String?, c: String?): Int {
            // A non-pre (null) is greater than any pre.
            return when {
                l == null && c == null -> 0
                l == null -> 1
                c == null -> -1
                else -> {
                    // Split by '.' and compare component-wise: numeric vs numeric numerically,
                    // else lexicographically.
                    val ls = l.split('.')
                    val cs = c.split('.')
                    val max = maxOf(ls.size, cs.size)
                    for (i in 0 until max) {
                        val lp = ls.getOrNull(i) ?: ""
                        val cp = cs.getOrNull(i) ?: ""
                        val lNum = lp.toIntOrNull()
                        val cNum = cp.toIntOrNull()
                        val cmp = if (lNum != null && cNum != null) lNum.compareTo(cNum)
                        else lp.compareTo(cp)
                        if (cmp != 0) return cmp
                    }
                    0
                }
            }
        }

        /** Exactly `autogram-<version>.apk` (or `-debug.apk` for debug builds); no fallback. */
        internal fun pickApkAsset(candidates: List<GitHubAsset>, version: String, debug: Boolean): GitHubAsset? {
            val expected = if (debug) "autogram-$version-debug.apk" else "autogram-$version.apk"
            return candidates.firstOrNull { it.name == expected }
        }
    }
}
