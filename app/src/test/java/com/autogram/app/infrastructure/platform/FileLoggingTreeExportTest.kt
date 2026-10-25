package com.autogram.app.infrastructure.platform

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The export file is what leaves the device (Sentry manual-report
 * attachment and the share sheet), so it must be PII-scrubbed while the
 * raw on-device log stays untouched.
 */
class FileLoggingTreeExportTest {

    private lateinit var cacheDir: File
    private lateinit var tree: FileLoggingTree

    @Before fun setUp() {
        cacheDir = Files.createTempDirectory("autogram-log-test").toFile()
        val context = mockk<Context> {
            every { this@mockk.cacheDir } returns this@FileLoggingTreeExportTest.cacheDir
            every { packageName } returns "com.autogram.app"
        }
        tree = FileLoggingTree(context)
    }

    @After fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test fun `export scrubs PII but keeps timestamps and raw file intact`() {
        val raw = "2026-10-25 12:34:56.789  I/Send: to chat 1234567890 " +
            "phone +34 612 345 678 user @john_smith99 mail jane@example.com"
        val logDir = File(cacheDir, "autogram-logs").apply { mkdirs() }
        val active = File(logDir, "autogram.log").apply { writeText(raw + "\n") }

        val exported = tree.materialiseExportFile().readText()

        assertTrue(
            exported.contains(
                "2026-10-25 12:34:56.789  I/Send: to chat [id] " +
                    "phone [phone] user [username] mail [email]"
            )
        )
        assertFalse(exported.contains("1234567890"))
        assertFalse(exported.contains("612 345"))
        assertFalse(exported.contains("john_smith99"))
        assertFalse(exported.contains("jane@example.com"))
        assertEquals(raw + "\n", active.readText())
    }

    @Test fun `stack trace lines without timestamp are scrubbed too`() {
        assertEquals(
            "java.io.IOException: chat [id] gone",
            FileLoggingTree.scrubExportLine("java.io.IOException: chat -100123456789 gone")
        )
        assertEquals(
            "\tat com.autogram.app.Foo.bar(Foo.kt:42)",
            FileLoggingTree.scrubExportLine("\tat com.autogram.app.Foo.bar(Foo.kt:42)")
        )
    }
}
