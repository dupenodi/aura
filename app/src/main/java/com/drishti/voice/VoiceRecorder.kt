package com.drishti.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.drishti.debug.RingBufferLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records 16 kHz mono PCM 16-bit via [AudioRecord].
 * Stops on manual [requestStop] (tap-to-send) or [VoiceActivityDetector.MAX_RECORD_MS] safety cap.
 * Writes a WAV file. Mic RMS is forwarded for orb UI only — silence does not end recording.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var lastEndReason: VoiceActivityDetector.EndReason = VoiceActivityDetector.EndReason.ManualStop
        private set

    @Volatile
    private var stopRequested: Boolean = false

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Blocks on IO until max duration / [requestStop], then returns a WAV [File].
     *
     * [onAudioLevel] receives a 0..1 mic level on the calling dispatcher thread
     * (~every 20ms PCM frame). Callers should hop to Main if needed.
     */
    suspend fun recordToWav(
        onAudioLevel: ((Float) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            return@withContext Result.failure(
                SecurityException("RECORD_AUDIO permission not granted"),
            )
        }
        if (isRecording) {
            return@withContext Result.failure(IllegalStateException("Already recording"))
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            return@withContext Result.failure(IllegalStateException("AudioRecord unsupported"))
        }
        val bufferSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        }.getOrElse {
            return@withContext Result.failure(it)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Result.failure(IllegalStateException("AudioRecord not initialized"))
        }

        val pcm = ByteArrayOutputStream()
        // Fixed ~20ms frames for stable level metering.
        val frameSamples = (SAMPLE_RATE / 50).coerceAtLeast(320)
        val shortBuf = ShortArray(frameSamples)
        val byteBuf = ByteArray(shortBuf.size * BYTES_PER_SAMPLE)
        val session = VoiceActivityDetector.Session()
        var endReason = VoiceActivityDetector.EndReason.ManualStop
        var lastRmsLogAt = 0L

        stopRequested = false
        isRecording = true
        RingBufferLogger.log(
            "voice",
            "record start rate=$SAMPLE_RATE frameSamples=$frameSamples " +
                "mode=tap-to-stop max=${VoiceActivityDetector.MAX_RECORD_MS}ms",
        )

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) {
                    if (read < 0) {
                        RingBufferLogger.log("voice", "AudioRecord.read error=$read")
                        break
                    }
                    continue
                }

                val frameMs = (read * 1000L) / SAMPLE_RATE
                val rms = VoiceActivityDetector.rms(shortBuf, 0, read)
                onAudioLevel?.invoke(normalizeRms(rms))
                if (session.elapsedMs - lastRmsLogAt >= 500L) {
                    lastRmsLogAt = session.elapsedMs
                    RingBufferLogger.log(
                        "voice",
                        "level rms=${"%.0f".format(rms)} speechMs=${session.speechMs} " +
                            "elapsed=${session.elapsedMs}",
                    )
                }
                // Little-endian PCM into stream.
                var bi = 0
                for (i in 0 until read) {
                    val s = shortBuf[i].toInt()
                    byteBuf[bi++] = (s and 0xFF).toByte()
                    byteBuf[bi++] = ((s shr 8) and 0xFF).toByte()
                }
                pcm.write(byteBuf, 0, bi)

                val reason = session.onFrame(rms, frameMs)
                if (reason != null) {
                    endReason = reason
                    break
                }
            }
            if (stopRequested) {
                endReason = VoiceActivityDetector.EndReason.ManualStop
            }
            lastEndReason = endReason

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No audio captured"))
            }
            val out = File(context.cacheDir, "drishti_utterance_${System.currentTimeMillis()}.wav")
            writeWav(out, pcmBytes, SAMPLE_RATE, CHANNEL_COUNT, BITS_PER_SAMPLE)
            RingBufferLogger.log(
                "voice",
                "record stop reason=$endReason bytes=${pcmBytes.size} file=${out.name}",
            )
            Result.success(out)
        } catch (t: Throwable) {
            RingBufferLogger.log("voice", "record error=${t.message}")
            Result.failure(t)
        } finally {
            isRecording = false
            stopRequested = false
            onAudioLevel?.invoke(0f)
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            runCatching { recorder.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_COUNT = 1
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2

        /** Soft ceiling for "loud speech" RMS on 16-bit PCM (above silence floor). */
        private const val LOUD_RMS = 6_000.0

        fun normalizeRms(rms: Double): Float {
            val min = VoiceActivityDetector.SILENCE_RMS
            val t = ((rms - min) / (LOUD_RMS - min)).coerceIn(0.0, 1.0)
            // Slight gamma so quiet speech still moves the orb.
            return kotlin.math.sqrt(t).toFloat()
        }

        fun writeWav(
            file: File,
            pcm: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
        ) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM chunk size
            header.putShort(1) // audio format PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            file.outputStream().use { out ->
                out.write(header.array())
                out.write(pcm)
            }
        }
    }
}
