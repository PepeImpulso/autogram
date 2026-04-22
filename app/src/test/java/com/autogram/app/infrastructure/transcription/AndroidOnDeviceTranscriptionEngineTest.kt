package com.autogram.app.infrastructure.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for the pure-Kotlin helpers inside [AndroidOnDeviceTranscriptionEngine]:
 * stereo→mono downmix and linear PCM-16 resampling. These run as JVM unit
 * tests because the helpers have no Android dependency — they're byte
 * arrays in, byte arrays out.
 *
 * Coverage matters: a botched downmix or sample rate conversion silently
 * corrupts the input to the speech recognizer, producing nonsense
 * transcriptions that look like a STT problem rather than a decoder bug.
 */
class AndroidOnDeviceTranscriptionEngineTest {

    private fun pcmFrom(samples: List<Short>): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    private fun samplesFrom(pcm: ByteArray): List<Short> {
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = mutableListOf<Short>()
        while (buf.remaining() > 0) out += buf.get()
        return out
    }

    // --- downmixStereoToMono ----------------------------------------------

    @Test fun `downmix averages L+R for each frame`() {
        // Two stereo frames: (L=100,R=200), (L=-50,R=50)
        val stereo = pcmFrom(listOf(100, 200, -50, 50))
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        // Expected mono: avg of each pair = (150, 0)
        assertEquals(listOf<Short>(150, 0), samplesFrom(mono))
    }

    @Test fun `downmix output is exactly half the byte length`() {
        val stereo = pcmFrom(List(20) { it.toShort() })  // 10 stereo frames
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        assertEquals(stereo.size / 2, mono.size)
    }

    @Test fun `downmix preserves silence`() {
        val stereo = pcmFrom(List(8) { 0 })
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        assertTrue(samplesFrom(mono).all { it == 0.toShort() })
    }

    @Test fun `downmix handles trailing partial frame by ignoring it`() {
        // 5 shorts = 10 bytes; only 2 complete stereo frames (8 bytes).
        // The trailing 2 bytes (1 short) should be dropped.
        val stereo = pcmFrom(listOf(10, 20, 30, 40, 99))
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        assertEquals(listOf<Short>(15, 35), samplesFrom(mono))
    }

    @Test fun `downmix avoids overflow for max + max`() {
        // (32767 + 32767) / 2 = 32767 — must not wrap to negative.
        val stereo = pcmFrom(listOf(Short.MAX_VALUE, Short.MAX_VALUE))
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        assertEquals(listOf<Short>(Short.MAX_VALUE), samplesFrom(mono))
    }

    @Test fun `downmix avoids underflow for min + min`() {
        val stereo = pcmFrom(listOf(Short.MIN_VALUE, Short.MIN_VALUE))
        val mono = AndroidOnDeviceTranscriptionEngine.downmixStereoToMono(stereo)
        assertEquals(listOf<Short>(Short.MIN_VALUE), samplesFrom(mono))
    }

    // --- resampleLinear ---------------------------------------------------

    @Test fun `resample with same rate returns input unchanged`() {
        val pcm = pcmFrom(listOf(1, 2, 3, 4))
        val out = AndroidOnDeviceTranscriptionEngine.resampleLinear(pcm, 16_000, 16_000)
        assertArrayEquals(pcm, out)
    }

    @Test fun `resample 48k to 16k produces one third of the samples`() {
        // 6 source samples at 48 kHz → 2 dest samples at 16 kHz.
        val pcm = pcmFrom(listOf(1000, 1000, 1000, 2000, 2000, 2000))
        val out = AndroidOnDeviceTranscriptionEngine.resampleLinear(pcm, 48_000, 16_000)
        val outSamples = samplesFrom(out)
        assertEquals(2, outSamples.size)
    }

    @Test fun `resample upward to 32k from 16k roughly doubles sample count`() {
        val pcm = pcmFrom(List(10) { (it * 100).toShort() })
        val out = AndroidOnDeviceTranscriptionEngine.resampleLinear(pcm, 16_000, 32_000)
        val outSamples = samplesFrom(out)
        // 10 source × 32000/16000 = 20 dest samples.
        assertEquals(20, outSamples.size)
    }

    @Test fun `resample preserves a constant signal`() {
        // Resampling a flat signal must keep it flat.
        val pcm = pcmFrom(List(48) { 1234.toShort() })
        val out = AndroidOnDeviceTranscriptionEngine.resampleLinear(pcm, 48_000, 16_000)
        val samples = samplesFrom(out)
        assertTrue(samples.all { it == 1234.toShort() })
    }

    @Test fun `resample interpolates between two values`() {
        // Source: [0, 10000] at 16 kHz. Resample to 8 kHz → 1 sample (the
        // first one, index 0). Resample to 24 kHz → 3 samples interpolating
        // 0 → 10000.
        val pcm = pcmFrom(listOf(0, 10_000))
        val up = AndroidOnDeviceTranscriptionEngine.resampleLinear(pcm, 16_000, 24_000)
        val samples = samplesFrom(up)
        assertEquals(3, samples.size)
        // First sample at exact source index 0 → 0.
        assertEquals(0.toShort(), samples[0])
        // Last sample at index 2 maps back to source idx (2 * 16/24) ≈ 1.33;
        // bilinear interpolation between sample[1]=10000 and sample[1]=10000
        // (clamped to last index) → 10000.
        // Middle one at 1 maps back to ~0.67 → ~6667.
        assertTrue("middle sample should interpolate", samples[1].toInt() in 6000..7500)
    }

    @Test fun `resample handles empty input`() {
        val out = AndroidOnDeviceTranscriptionEngine.resampleLinear(ByteArray(0), 48_000, 16_000)
        assertEquals(0, out.size)
    }
}
