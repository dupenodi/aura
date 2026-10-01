package com.drishti.core.speech

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides when someone has finished speaking, for hands-free listening (aura summoned as
 * the phone's assistant, where there is no finger to lift).
 *
 * Plain energy against an adaptive noise floor: cheap enough to run on every 100 ms chunk,
 * and good enough for a single short request. The floor follows the room down quickly and
 * up slowly, so a fan or traffic doesn't read as speech, and a few voiced chunks are needed
 * before a pause can end the turn, so a click or a cough doesn't.
 *
 * [Verdict.NoSpeech] only means "stop recording": a voice that was already loud when the
 * microphone opened sets the floor itself, so the caller still lets the recogniser decide
 * whether anything was said.
 */
class Endpointer(
    private val sampleRate: Int = 16_000,
    private val silenceAfterSpeechMs: Long = 1_200,
    private val noSpeechTimeoutMs: Long = 6_000,
    private val maxMs: Long = 15_000,
    private val minSpeechMs: Long = 250,
) {
    enum class Verdict { Continue, EndOfSpeech, NoSpeech, MaxLength }

    private var elapsedMs = 0L
    private var speechMs = 0L
    private var silenceMs = 0L
    private var floorDb = Float.NaN

    /** Loudness of the last chunk, 0..1, for drawing. */
    var level: Float = 0f
        private set

    val heardSpeech: Boolean get() = speechMs >= minSpeechMs

    /** Feeds 16-bit little-endian mono PCM. */
    fun feed(pcm: ByteArray, length: Int = pcm.size): Verdict {
        val samples = length / 2
        if (samples == 0) return Verdict.Continue
        val chunkMs = samples * 1000L / sampleRate
        elapsedMs += chunkMs

        val db = dbfs(pcm, samples)
        if (floorDb.isNaN()) floorDb = db
        val voiced = db > floorDb + VOICED_ABOVE_FLOOR_DB && db > ABSOLUTE_MIN_DB
        if (voiced) {
            speechMs += chunkMs
            silenceMs = 0
        } else {
            silenceMs += chunkMs
            // Follow the room: down fast, up slowly.
            floorDb = if (db < floorDb) floorDb + (db - floorDb) * 0.5f else floorDb + (db - floorDb) * 0.05f
        }
        level = ((db - floorDb) / 30f).coerceIn(0f, 1f)

        return when {
            heardSpeech && silenceMs >= silenceAfterSpeechMs -> Verdict.EndOfSpeech
            !heardSpeech && elapsedMs >= noSpeechTimeoutMs -> Verdict.NoSpeech
            elapsedMs >= maxMs -> Verdict.MaxLength
            else -> Verdict.Continue
        }
    }

    private fun dbfs(pcm: ByteArray, samples: Int): Float {
        var sum = 0.0
        for (i in 0 until samples) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / samples) / 32768.0
        return if (rms <= 1e-6) -120f else (20 * log10(rms)).toFloat()
    }

    private companion object {
        const val VOICED_ABOVE_FLOOR_DB = 12f
        const val ABSOLUTE_MIN_DB = -50f
    }
}
