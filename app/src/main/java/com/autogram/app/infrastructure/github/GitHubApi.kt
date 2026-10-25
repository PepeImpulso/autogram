package com.autogram.app.infrastructure.github

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Minimal slice of the GitHub REST API. Unauthenticated — 60 requests/hour/IP
 * is plenty for a sideloaded update checker.
 *
 * Note: we deliberately do NOT use `/releases/latest` because GitHub excludes
 * prereleases from that endpoint, and prerelease builds must still be able to
 * follow newer prereleases. We list releases and pick the newest applicable
 * one instead (see CheckForUpdatesUseCase for the prerelease rule).
 */
interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases")
    suspend fun listReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 10
    ): List<GitHubRelease>
}

data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val html_url: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    val published_at: String? = null
)

data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0,
    val content_type: String? = null,
    /** `sha256:<hex>` computed by GitHub on upload; verified before install. */
    val digest: String? = null
)
