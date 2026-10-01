package com.drishti.core.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM helpers. */
object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    /** Where the samples start in a WAV file, or 0 if [bytes] is raw PCM. */
    fun dataOffset(bytes: ByteArray): Int {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return i + 8
            i += 8 + size + (size and 1)
        }
        return 44.coerceAtMost(bytes.size)
    }

    fun sampleRate(bytes: ByteArray): Int? {
        if (dataOffset(bytes) == 0 || bytes.size < 28) return null
        return ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun pcmOf(bytes: ByteArray): ByteArray = bytes.copyOfRange(dataOffset(bytes), bytes.size)
}
