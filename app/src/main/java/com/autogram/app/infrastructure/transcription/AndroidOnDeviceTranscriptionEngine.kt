package com.autogram.app.infrastructure.transcription

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `SpeechRecognizer` on-device backend — uses the Google voice-services model
 * that ships with modern Android phones.
 *
 * Why API 34+: only `Android 14` (`UPSIDE_DOWN_CAKE`) added `EXTRA_AUDIO_SOURCE`,
 * which is what lets us feed a recorded .ogg instead of a live mic. On older
 * devices the engine exists but can only transcribe live audio, which isn't
 * what we need here — so the use case checks [isSupportedHere] and surfaces
 * NotInstalled to the caller instead of silently failing.
 *
 * Audio pipeline:
 *   1. MediaExtractor + MediaCodec decode OGG/Opus to raw mono PCM 16-bit.
 *   2. We resample to 16 kHz if Opus output is 48 kHz (the common case).
 *   3. The PCM buffer is spilled to a temp file, wrapped in a
 *      ParcelFileDescriptor, and handed to SpeechRecognizer.
 */
@Singleton
class AndroidOnDeviceTranscriptionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TranscriptionEngine {

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val DECODE_TIMEOUT_US = 10_000L

        fun isSupportedHere(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)
        }
    }

    override suspend fun transcribe(audioFile: File, locale: Locale): TranscriptionEngine.Result =
        withContext(Dispatchers.IO) {
            if (!isSupportedHere(context)) {
                return@withContext TranscriptionEngine.Result.NotInstalled
            }
            val pcmFile = runCatching { decodeToMono16k(audioFile) }.getOrElse { e ->
                Timber.w(e, "Opus decode failed for ${audioFile.name}")
                return@withContext TranscriptionEngine.Result.Error("decode: ${e.message}")
            }
            try {
                runRecognition(pcmFile, locale)
            } finally {
                runCatching { pcmFile.delete() }
            }
        }

    // --- Decoding ------------------------------------------------------------

    /** Decode the Opus/OGG voice note to 16kHz mono signed-16 PCM. */
    private fun decodeToMono16k(source: File): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pcmOut: File? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in ${source.name}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            pcmOut = File.createTempFile("autogram-tx-", ".pcm", context.cacheDir)
            FileOutputStream(pcmOut).use { out ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
                    if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        val mono = if (sourceChannels == 2) downmixStereoToMono(chunk) else chunk
                        val resampled = if (sourceSampleRate != TARGET_SAMPLE_RATE)
                            resampleLinear(mono, sourceSampleRate, TARGET_SAMPLE_RATE)
                        else mono
                        out.write(resampled)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return pcmOut
        } catch (t: Throwable) {
            // Don't leak a half-written PCM temp file if we throw mid-decode.
            pcmOut?.let { runCatching { it.delete() } }
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun downmixStereoToMono(interleaved: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(interleaved).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val mono = ByteBuffer.allocate(interleaved.size / 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) {
            val l = buf.short.toInt()
            val r = buf.short.toInt()
            mono.putShort(((l + r) / 2).toShort())
        }
        return mono.array()
    }

    /** Linear resampler — good enough for speech recognition. */
    private fun resampleLinear(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return pcm
        val src = ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val srcLen = src.remaining()
        val dstLen = (srcLen.toLong() * dstRate / srcRate).toInt()
        val out = ByteBuffer.allocate(dstLen * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dstLen) {
            val srcIdx = i.toDouble() * srcRate / dstRate
            val i0 = srcIdx.toInt().coerceAtMost(srcLen - 1)
            val i1 = (i0 + 1).coerceAtMost(srcLen - 1)
            val frac = srcIdx - i0
            val s0 = src.get(i0).toDouble()
            val s1 = src.get(i1).toDouble()
            out.putShort((s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out.array()
    }

    // --- Recognition ---------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun runRecognition(pcmFile: File, locale: Locale): TranscriptionEngine.Result {
        return suspendCancellableCoroutine { cont ->
            // Allocate recognizer + pfd inside a guarded block: if
            // startListening() throws, we must still close the fd, otherwise
            // we'd leak a file descriptor per failed attempt.
            var recognizer: SpeechRecognizer? = null
            var pfd: ParcelFileDescriptor? = null
            // All callbacks + the cancellation handler race to settle first.
            // An @Volatile+CAS guarantees close() runs exactly once even if
            // multiple paths fire concurrently (e.g. cancel racing with result).
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            val cleanup = {
                if (settled.compareAndSet(false, true)) {
                    runCatching { pfd?.close() }
                    runCatching { recognizer?.destroy() }
                }
            }
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                pfd = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                val listener = object : RecognitionListener {
                    private fun settleWith(result: TranscriptionEngine.Result) {
                        cleanup()
                        if (cont.isActive) cont.resume(result)
                    }
                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = list?.firstOrNull().orEmpty()
                        settleWith(
                            if (text.isBlank()) TranscriptionEngine.Result.Error("empty result")
                            else TranscriptionEngine.Result.Success(text.trim())
                        )
                    }
                    override fun onError(errorCode: Int) {
                        val mapped = when (errorCode) {
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                TranscriptionEngine.Result.LanguageUnavailable
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                TranscriptionEngine.Result.Error("no speech detected")
                            else -> TranscriptionEngine.Result.Error("recognizer error $errorCode")
                        }
                        settleWith(mapped)
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
                recognizer.setRecognitionListener(listener)
                recognizer.startListening(intent)
                cont.invokeOnCancellation { cleanup() }
            } catch (t: Throwable) {
                cleanup()
                if (cont.isActive) cont.resume(
                    TranscriptionEngine.Result.Error("recognizer setup: ${t.message}")
                )
            }
        }
    }
}
