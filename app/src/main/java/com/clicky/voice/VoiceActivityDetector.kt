package com.clicky.voice

import kotlin.math.sqrt

/**
 * RMS helpers + session timing for push-to-talk recording.
 *
 * Recording ends only when:
 * - elapsed ≥ [MAX_RECORD_MS] (safety cap), OR
 * - caller requests manual stop (bubble re-tap).
 *
 * Silence is tracked for optional UI / logging only — it does **not** end the utterance.
 */
object VoiceActivityDetector {
    /** RMS below this (on 16-bit samples) counts as silence (level / logging). */
    const val SILENCE_RMS = 400.0

    /** Retained for logging compatibility; silence no longer ends recording. */
    const val SILENCE_END_MS = 900L

    /** Safety cap on a single recording (tap-to-stop is the primary end). */
    const val MAX_RECORD_MS = 30_000L

    /** Cumulative speech tracking (UI / logs only). */
    const val MIN_SPEECH_MS = 400L

    fun rms(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        val end = (offset + length).coerceAtMost(samples.size)
        val start = offset.coerceAtLeast(0)
        val n = end - start
        if (n <= 0) return 0.0
        for (i in start until end) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }

    /**
     * Mutable tracker for one recording session.
     */
    class Session {
        var heardSpeech: Boolean = false
            private set
        var speechMs: Long = 0L
            private set
        var silenceMs: Long = 0L
            private set
        var elapsedMs: Long = 0L
            private set

        /**
         * @return [EndReason.MaxDuration] when the safety cap is hit; otherwise null.
         * Silence never ends the session (push-to-talk).
         */
        fun onFrame(rms: Double, frameDurationMs: Long): EndReason? {
            if (frameDurationMs <= 0L) return null
            elapsedMs += frameDurationMs
            if (elapsedMs >= MAX_RECORD_MS) return EndReason.MaxDuration

            val speaking = rms >= SILENCE_RMS
            if (speaking) {
                heardSpeech = true
                speechMs += frameDurationMs
                silenceMs = 0L
            } else {
                silenceMs += frameDurationMs
            }
            return null
        }
    }

    enum class EndReason {
        /** @deprecated Silence no longer ends recording; kept for log compatibility. */
        Silence,
        MaxDuration,
        ManualStop,
    }
}
