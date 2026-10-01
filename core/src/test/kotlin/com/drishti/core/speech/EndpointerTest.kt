package com.drishti.core.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointerTest {
    private val rate = 16_000

    /** 100 ms of a tone at [amplitude] (0..1) over a little noise at [noise]. */
    private fun chunk(amplitude: Double, noise: Double = 0.003, seed: Int = 0): ByteArray {
        val rnd = Random(seed)
        val n = rate / 10
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = amplitude * sin(2 * PI * 220 * i / rate) + noise * (rnd.nextDouble() * 2 - 1)
            val s = (v * 32767).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun run(e: Endpointer, chunks: List<ByteArray>): Pair<Endpointer.Verdict, Int> {
        chunks.forEachIndexed { i, c ->
            val v = e.feed(c)
            if (v != Endpointer.Verdict.Continue) return v to i
        }
        return Endpointer.Verdict.Continue to chunks.size
    }

    @Test
    fun endsAfterAPauseFollowingSpeech() {
        val audio = List(5) { chunk(0.0, seed = it) } + List(15) { chunk(0.3, seed = it) } + List(30) { chunk(0.0, seed = it) }
        val (verdict, at) = run(Endpointer(rate), audio)
        assertEquals(Endpointer.Verdict.EndOfSpeech, verdict)
        // 5 quiet + 15 speech + 12 chunks (1.2 s) of silence.
        assertEquals(31, at)
    }

    @Test
    fun shortPausesInsideASentenceDoNotEndIt() {
        val speech = List(8) { chunk(0.3, seed = it) }
        val pause = List(6) { chunk(0.0, seed = it) } // 600 ms, under the 1.2 s cut-off
        val audio = speech + pause + speech + List(20) { chunk(0.0, seed = it) }
        val (verdict, at) = run(Endpointer(rate), audio)
        assertEquals(Endpointer.Verdict.EndOfSpeech, verdict)
        assertTrue(at > speech.size * 2 + pause.size, "ended inside the sentence at $at")
    }

    @Test
    fun silenceTimesOutAsNoSpeech() {
        val (verdict, at) = run(Endpointer(rate), List(100) { chunk(0.0, seed = it) })
        assertEquals(Endpointer.Verdict.NoSpeech, verdict)
        assertEquals(59, at)
    }

    @Test
    fun steadyRoomNoiseIsNotSpeech() {
        // A loud fan from the first chunk: the floor starts there, so it never reads as voice.
        val (verdict, _) = run(Endpointer(rate), List(100) { chunk(0.0, noise = 0.08, seed = it) })
        assertEquals(Endpointer.Verdict.NoSpeech, verdict)
    }

    @Test
    fun aSingleClickDoesNotCountAsSpeech() {
        val audio = List(3) { chunk(0.0, seed = it) } + chunk(0.5) + List(80) { chunk(0.0, seed = it) }
        val (verdict, _) = run(Endpointer(rate), audio)
        assertEquals(Endpointer.Verdict.NoSpeech, verdict)
    }

    @Test
    fun nonStopTalkingIsCappedAtMaxLength() {
        val audio = List(3) { chunk(0.0, seed = it) } + List(200) { chunk(0.3, seed = it) }
        val (verdict, at) = run(Endpointer(rate), audio)
        assertEquals(Endpointer.Verdict.MaxLength, verdict)
        assertEquals(149, at)
    }

    @Test
    fun levelRisesWithVoice() {
        val e = Endpointer(rate)
        e.feed(chunk(0.0))
        val quiet = e.level
        e.feed(chunk(0.3))
        assertTrue(e.level > quiet + 0.5f, "level ${e.level} vs $quiet")
    }
}
