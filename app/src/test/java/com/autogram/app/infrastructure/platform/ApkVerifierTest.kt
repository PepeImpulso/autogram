package com.autogram.app.infrastructure.platform

import com.autogram.app.infrastructure.platform.ApkVerifier.ApkIdentity
import com.autogram.app.infrastructure.platform.ApkVerifier.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkVerifierTest {

    private val releaseKey = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val fileHash = "c".repeat(64)

    private val installed = ApkIdentity(
        packageName = "com.autogram.app",
        versionCode = 145,
        currentSigners = setOf(releaseKey),
        signerHistory = setOf(releaseKey)
    )

    private fun candidate(
        packageName: String = "com.autogram.app",
        versionCode: Long = 146,
        signer: String = releaseKey,
        history: Set<String> = setOf(signer)
    ) = ApkIdentity(packageName, versionCode, setOf(signer), history)

    private fun evaluate(
        candidate: ApkIdentity?,
        fileSha256: String? = fileHash,
        expectedSha256: String? = fileHash
    ) = ApkVerifier.evaluate(candidate, installed, fileSha256, expectedSha256)

    private fun assertRejected(result: Result) =
        assertTrue("expected rejection, got $result", result is Result.Rejected)

    @Test
    fun `legitimate newer release passes`() {
        assertEquals(Result.Ok, evaluate(candidate()))
    }

    @Test
    fun `digest comparison ignores case`() {
        assertEquals(Result.Ok, evaluate(candidate(), expectedSha256 = fileHash.uppercase()))
    }

    @Test
    fun `different package name is rejected`() {
        assertRejected(evaluate(candidate(packageName = "com.evil.app")))
    }

    @Test
    fun `different signing key is rejected`() {
        assertRejected(evaluate(candidate(signer = otherKey)))
    }

    @Test
    fun `rotated key whose lineage contains the installed key passes`() {
        assertEquals(
            Result.Ok,
            evaluate(candidate(signer = otherKey, history = setOf(releaseKey, otherKey)))
        )
    }

    @Test
    fun `same versionCode is rejected`() {
        assertRejected(evaluate(candidate(versionCode = 145)))
    }

    @Test
    fun `older versionCode is rejected`() {
        assertRejected(evaluate(candidate(versionCode = 100)))
    }

    @Test
    fun `hash mismatch is rejected`() {
        assertRejected(evaluate(candidate(), fileSha256 = "d".repeat(64)))
    }

    @Test
    fun `missing published digest is rejected`() {
        assertRejected(evaluate(candidate(), expectedSha256 = null))
    }

    @Test
    fun `unhashable file is rejected`() {
        assertRejected(evaluate(candidate(), fileSha256 = null))
    }

    @Test
    fun `unreadable archive is rejected`() {
        assertRejected(evaluate(null))
    }

    @Test
    fun `parses GitHub sha256 digest`() {
        assertEquals(fileHash, ApkVerifier.parseSha256Digest("sha256:${fileHash.uppercase()}"))
    }

    @Test
    fun `rejects malformed or non-sha256 digests`() {
        assertNull(ApkVerifier.parseSha256Digest(null))
        assertNull(ApkVerifier.parseSha256Digest(fileHash))
        assertNull(ApkVerifier.parseSha256Digest("sha512:$fileHash"))
        assertNull(ApkVerifier.parseSha256Digest("sha256:abc"))
        assertNull(ApkVerifier.parseSha256Digest("sha256:" + "z".repeat(64)))
    }

    @Test
    fun `file hash matches known SHA-256`() {
        val f = File.createTempFile("apk", ".bin").apply {
            deleteOnExit()
            writeText("abc")
        }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkVerifier.sha256Hex(f)
        )
    }
}
