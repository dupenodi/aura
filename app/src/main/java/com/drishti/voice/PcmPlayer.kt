package com.drishti.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Plays 16-bit mono PCM as it arrives, so speech starts with the first streamed chunk.
 *
 * One AudioTrack is kept for the life of the voice, re-created only when the sample rate
 * changes: building a track costs tens of milliseconds, which would be heard as a pause
 * before every instruction.
 */
class PcmPlayer(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
    } else {
        null
    }

    private val lock = Any()
    private var track: AudioTrack? = null
    private var rate = 0
    private var framesWritten = 0L

    /** Gets ready to play at [sampleRate] and takes audio focus so music ducks. */
    fun begin(sampleRate: Int) = synchronized(lock) {
        if (track == null || rate != sampleRate) {
            track?.release()
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, sampleRate / 5 * 2))
                .build()
            rate = sampleRate
        }
        focus?.let { audio.requestAudioFocus(it) }
        track?.flush()
        framesWritten = 0
        track?.play()
    }

    /** Blocks until [pcm] is queued. Returns false once [stop] has been called. */
    fun write(pcm: ByteArray): Boolean {
        val t = synchronized(lock) { track } ?: return false
        var off = 0
        while (off < pcm.size) {
            // stop() pauses the track; checking each pass ends a write cut off mid-chunk.
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return false
            val n = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return false
            off += n
        }
        framesWritten += pcm.size / 2
        return true
    }

    /** Milliseconds of queued audio not yet heard. */
    fun remainingMs(): Long {
        val t = synchronized(lock) { track } ?: return 0
        val played = t.playbackHeadPosition.toLong() and 0xffffffffL
        return ((framesWritten - played).coerceAtLeast(0) * 1000 / rate.coerceAtLeast(1))
    }

    /** Done speaking: give focus back so their music returns to full volume. */
    fun end() = synchronized(lock) {
        track?.pause()
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    /** Cut off immediately — the user acted, or Stop was pressed. */
    fun stop() = synchronized(lock) {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    fun release() = synchronized(lock) {
        stop()
        track?.release()
        track = null
    }
}
