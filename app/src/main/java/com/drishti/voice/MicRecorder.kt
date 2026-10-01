package com.drishti.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records 16 kHz mono PCM in 100 ms chunks until [stop] — the finger lifting.
 *
 * Uses the VOICE_RECOGNITION source (tuned for speech, no call-style processing) and turns
 * on the platform's noise suppression and gain control where the phone has them: older
 * users often hold the phone at arm's length, in a noisy room.
 */
class MicRecorder(private val sampleRate: Int = 16_000) {
    /** Set by [stop]; cleared by [arm] before a recording starts, so an early stop isn't lost. */
    private val stopRequested = AtomicBoolean(false)

    /** Call on the caller's thread before [record] starts on another. */
    fun arm() {
        stopRequested.set(false)
    }

    /**
     * Blocks, calling [onChunk] for each 100 ms of audio, until [stop] or [maxMs].
     * Returns false if the microphone couldn't be opened.
     */
    @SuppressLint("MissingPermission") // Checked by the caller before recording starts.
    fun record(maxMs: Long = 30_000, onChunk: (ByteArray) -> Unit): Boolean {
        val chunkBytes = sampleRate / 10 * 2
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, chunkBytes * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone unavailable")
            record?.release()
            return false
        }
        val effects = listOfNotNull(
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() else null,
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull() else null,
        )
        effects.forEach { runCatching { it.enabled = true } }

        val maxBytes = sampleRate * 2L * maxMs / 1000
        var total = 0L
        try {
            record.startRecording()
            val buf = ByteArray(chunkBytes)
            while (!stopRequested.get() && total < maxBytes) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) {
                    total += n
                    onChunk(buf.copyOf(n))
                }
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
        }
        return true
    }

    fun stop() {
        stopRequested.set(true)
    }

    companion object {
        private const val TAG = "MicRecorder"
    }
}
