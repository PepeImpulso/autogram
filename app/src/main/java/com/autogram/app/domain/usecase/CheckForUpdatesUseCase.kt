package com.autogram.app.domain.usecase

import com.autogram.app.BuildConfig
import com.autogram.app.infrastructure.github.GitHubApi
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
    val publishedAt: String?
)

class CheckForUpdatesUseCase @Inject constructor(
    private val gitHubApi: GitHubApi
) {
    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val release = gitHubApi.getLatestRelease(OWNER, REPO)
            val latest = stripLeadingV(release.tag_name)
            val current = BuildConfig.VERSION_NAME
            val isNewer = isNewerVersion(latest, current)

            // Pick the APK matching the currently installed variant.
            // Debug builds can only be replaced by debug-signed APKs (different cert),
            // so we pick -debug.apk for them; release builds grab the non-debug one.
            val apkAsset = pickApkAsset(release.assets.map { it.name to it.browser_download_url to it.size })

            Timber.d("CheckForUpdates: current=$current latest=$latest newer=$isNewer apk=${apkAsset?.first}")

            Result.success(
                UpdateInfo(
                    isUpdateAvailable = isNewer,
                    latestVersion = latest,
                    latestVersionName = release.name ?: release.tag_name,
                    releaseUrl = release.html_url,
                    releaseNotes = release.body,
                    apkUrl = apkAsset?.second,
                    apkSizeBytes = apkAsset?.third ?: 0,
                    publishedAt = release.published_at
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "CheckForUpdates failed")
            Result.failure(e)
        }
    }

    companion object {
        private const val OWNER = "PepeImpulso"
        private const val REPO = "autogram"

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

        private fun pickApkAsset(candidates: List<Triple<String, String, Long>>): Triple<String, String, Long>? {
            val debug = BuildConfig.DEBUG
            val apks = candidates.filter { it.first.endsWith(".apk") }
            val matching = apks.firstOrNull {
                val isDebugAsset = it.first.contains("-debug")
                isDebugAsset == debug
            }
            return matching ?: apks.firstOrNull()
        }

        // Infix helper so we can write `a to b to c` and read it as Triple.
        @Suppress("NOTHING_TO_INLINE")
        private inline infix fun <A, B, C> Pair<A, B>.to(third: C): Triple<A, B, C> =
            Triple(first, second, third)
    }
}
