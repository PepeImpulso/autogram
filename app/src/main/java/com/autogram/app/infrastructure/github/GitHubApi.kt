package com.autogram.app.infrastructure.github

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Minimal slice of the GitHub REST API. Unauthenticated — 60 requests/hour/IP
 * is plenty for a sideloaded update checker.
 */
interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease
}

data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val html_url: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    val published_at: String? = null
)

data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0,
    val content_type: String? = null
)
