package com.clicky.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.clicky.BuildConfig
import com.clicky.debug.RingBufferLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.tanh

/**
 * TTS fallback order: Sarvam Bulbul → Deepgram Aura → OpenAI `gpt-4o-mini-tts`.
 * Playback uses USAGE_MEDIA + SPEECH, max AudioTrack volume, and ~[PCM_GAIN]x soft-clipped PCM.
 * [interrupt] ducks/cancels in-flight speech.
 *
 * Agent tools should prefer [speakAsync]: kick off playback and return quickly so the loop
 * can plan/act while narrating. Cancel / new listen still calls [interrupt].
 */
@Singleton
class Speaker @Inject constructor() {
    private val sarvamClient: OkHttpClient by lazy { SarvamClient.createOkHttp() }
    private val deepgramClient: OkHttpClient by lazy { DeepgramClient.createOkHttp() }

    private val openAiClient: OkHttpClient by lazy {
        OpenAiClient.createOkHttp(BuildConfig.OPENAI_API_KEY).newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val mutex = Mutex()
    private val interruptFlag = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackJob = AtomicReference<Job?>(null)

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    fun hasApiKey(): Boolean =
        SarvamClient.hasApiKey() ||
            DeepgramClient.hasApiKey() ||
            BuildConfig.OPENAI_API_KEY.isNotBlank()

    /** Cancel current playback immediately (bubble interrupt / new turn). */
    fun interrupt() {
        interruptFlag.set(true)
        playbackJob.getAndSet(null)?.cancel()
        activeCall.getAndSet(null)?.let { call ->
            runCatching { call.cancel() }
        }
        runCatching {
            track?.let { t ->
                runCatching { t.pause() }
                runCatching { t.flush() }
                runCatching { t.stop() }
            }
        }
        RingBufferLogger.log("tts", "interrupt")
    }

    /**
     * Non-blocking TTS for the agent loop: interrupt any prior utterance, start playback
     * on a background job, wait briefly for kickoff, then return so tools can overlap narration.
     * Language / Kannada (Sarvam Indic) behavior matches [speak].
     */
    suspend fun speakAsync(text: String, language: String? = null): Result<Unit> {
        val utterance = text.trim()
        if (utterance.isEmpty()) {
            return Result.success(Unit)
        }
        if (!hasApiKey()) {
            return Result.failure(
                IllegalStateException(
                    "No TTS key — add SARVAM_API_KEY, DEEPGRAM_API_KEY, and/or OPENAI_API_KEY " +
                        "to local.properties and rebuild.",
                ),
            )
        }
        interrupt()
        // Clear interrupt so the new job is not born cancelled.
        interruptFlag.set(false)
        RingBufferLogger.log(
            "tts",
            "speakAsync kickoff chars=${utterance.length} lang=${language ?: "default"}",
        )
        val job = playbackScope.launch {
            speak(utterance, language).onFailure { e ->
                RingBufferLogger.log("tts", "speakAsync background error=${e.message}")
            }
        }
        playbackJob.set(job)
        // Brief settle so network/AudioTrack start overlaps the next agent step.
        delay(ASYNC_KICKOFF_MS)
        return Result.success(Unit)
    }

    /**
     * @param language optional BCP-47 / Sarvam code (kn-IN, hi-IN, …). Defaults to en-IN.
     * For Indic languages, Sarvam is preferred; OpenAI English fallback is skipped.
     * Blocks until playback finishes — prefer [speakAsync] from agent tools.
     */
    suspend fun speak(text: String, language: String? = null): Result<Unit> = mutex.withLock {
        val utterance = text.trim()
        if (utterance.isEmpty()) {
            return@withLock Result.success(Unit)
        }
        if (!hasApiKey()) {
            return@withLock Result.failure(
                IllegalStateException(
                    "No TTS key — add SARVAM_API_KEY, DEEPGRAM_API_KEY, and/or OPENAI_API_KEY " +
                        "to local.properties and rebuild.",
                ),
            )
        }

        val targetLang = SarvamClient.normalizeTtsLanguage(language)
        val indic = SarvamClient.isIndicLanguage(targetLang)
        interruptFlag.set(false)
        isSpeaking = true
        RingBufferLogger.log(
            "tts",
            "speak chars=${utterance.length} lang=$targetLang indic=$indic",
        )

        try {
            withTimeout(SPEAK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (SarvamClient.hasApiKey()) {
                        val sarvam = runCatching { playSarvam(utterance, targetLang) }
                        if (sarvam.isSuccess) return@withContext
                        RingBufferLogger.log(
                            "tts",
                            "sarvam failed lang=$targetLang " +
                                "(${sarvam.exceptionOrNull()?.message}); fallback carefully",
                        )
                        if (interruptFlag.get()) return@withContext
                        // Indic: retry Sarvam once is enough; avoid English OpenAI for kn/hi/…
                        if (indic) {
                            RingBufferLogger.log(
                                "tts",
                                "indic lang=$targetLang — skipping OpenAI English fallback",
                            )
                        }
                    }
                    if (DeepgramClient.hasApiKey()) {
                        val dg = runCatching {
                            playDeepgram(utterance)
                        }
                        if (dg.isSuccess) return@withContext
                        RingBufferLogger.log(
                            "tts",
                            "deepgram failed (${dg.exceptionOrNull()?.message}); " +
                                if (indic) "not using OpenAI for Indic" else "trying OpenAI",
                        )
                        if (interruptFlag.get()) return@withContext
                    }
                    if (indic) {
                        error(
                            "Indic TTS failed for $targetLang (Sarvam preferred). " +
                                "OpenAI English fallback skipped — check SARVAM_API_KEY / text script.",
                        )
                    }
                    if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                        error(
                            "TTS failed and OPENAI_API_KEY is empty. " +
                                "Add SARVAM_API_KEY (preferred), DEEPGRAM_API_KEY, or OPENAI_API_KEY " +
                                "to local.properties and rebuild.",
                        )
                    }
                    playOpenAi(utterance)
                }
            }
            if (interruptFlag.get()) {
                RingBufferLogger.log("tts", "cancelled")
            } else {
                RingBufferLogger.log("tts", "done")
            }
            Result.success(Unit)
        } catch (t: TimeoutCancellationException) {
            RingBufferLogger.log("tts", "timeout after ${SPEAK_TIMEOUT_MS}ms")
            interrupt()
            Result.failure(IllegalStateException("TTS timed out after ${SPEAK_TIMEOUT_MS / 1000}s"))
        } catch (t: Throwable) {
            RingBufferLogger.log("tts", "error=${t.message}")
            Result.failure(t)
        } finally {
            activeCall.set(null)
            releaseTrack()
            isSpeaking = false
            interruptFlag.set(false)
        }
    }

    private suspend fun playSarvam(utterance: String, targetLanguageCode: String) {
        val lang = SarvamClient.normalizeTtsLanguage(targetLanguageCode)
        RingBufferLogger.log(
            "tts",
            "provider=Sarvam model=${SarvamClient.TTS_MODEL} speaker=${SarvamClient.TTS_SPEAKER} " +
                "lang=$lang",
        )
        val call = sarvamClient.newCall(SarvamClient.buildTtsRequest(utterance, lang))
        activeCall.set(call)
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Sarvam TTS HTTP ${response.code}: ${raw.take(300)}")
                }
                val pcm = SarvamClient.decodeTtsPcm(raw)
                if (interruptFlag.get()) return
                playPcm(pcm, SarvamClient.TTS_SAMPLE_RATE)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun playDeepgram(utterance: String) {
        RingBufferLogger.log("tts", "provider=Deepgram model=${DeepgramClient.TTS_MODEL}")
        val url =
            "${DeepgramClient.BASE_URL}v1/speak?model=${DeepgramClient.TTS_MODEL}" +
                "&encoding=linear16&sample_rate=${DeepgramClient.TTS_SAMPLE_RATE}"
        val json = JSONObject().put("text", utterance).toString()
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val call = deepgramClient.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300).orEmpty()
                    error("Deepgram TTS HTTP ${response.code}: $err")
                }
                val pcm = response.body?.bytes() ?: error("Deepgram TTS empty body")
                if (interruptFlag.get()) return
                playPcm(pcm, DeepgramClient.TTS_SAMPLE_RATE)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun playOpenAi(utterance: String) {
        RingBufferLogger.log("tts", "provider=OpenAI model=$OPENAI_MODEL")
        val json = JSONObject()
            .put("model", OPENAI_MODEL)
            .put("voice", OPENAI_VOICE)
            .put("input", utterance)
            .put("response_format", "pcm")
            .toString()
        val request = Request.Builder()
            .url(OpenAiClient.BASE_URL + "v1/audio/speech")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val call = openAiClient.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300).orEmpty()
                    error("TTS HTTP ${response.code}: $err")
                }
                val body = response.body ?: error("TTS empty body")
                val audioTrack = createTrack(OPENAI_SAMPLE_RATE)
                track = audioTrack
                audioTrack.play()
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    error("AudioTrack failed to enter PLAYING state")
                }

                val buffer = ByteArray(STREAM_CHUNK)
                // Carry an odd trailing byte across reads so PCM16 pairs stay intact.
                var carry: ByteArray = ByteArray(0)
                body.byteStream().use { stream ->
                    while (currentCoroutineContext().isActive && !interruptFlag.get()) {
                        currentCoroutineContext().ensureActive()
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (interruptFlag.get()) break
                        val combined = ByteArray(carry.size + n)
                        if (carry.isNotEmpty()) {
                            System.arraycopy(carry, 0, combined, 0, carry.size)
                        }
                        System.arraycopy(buffer, 0, combined, carry.size, n)
                        val evenLen = combined.size and 1.inv()
                        if (evenLen > 0) {
                            val frame = combined.copyOf(evenLen)
                            amplifyPcm16LeInPlace(frame)
                            writeFully(audioTrack, frame, 0, frame.size)
                        }
                        carry = if (combined.size % 2 == 1) {
                            byteArrayOf(combined[combined.size - 1])
                        } else {
                            ByteArray(0)
                        }
                    }
                }

                if (!interruptFlag.get()) {
                    // Brief drain so the last buffered frames can leave the mixer.
                    delay(40)
                }
                releaseTrack()
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun playPcm(pcm: ByteArray, sampleRate: Int) {
        val boosted = amplifyPcm16LeInPlace(pcm.copyOf(), logGain = true)
        val audioTrack = createTrack(sampleRate)
        track = audioTrack
        audioTrack.play()
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            error("AudioTrack failed to enter PLAYING state")
        }
        var offset = 0
        while (offset < boosted.size &&
            currentCoroutineContext().isActive &&
            !interruptFlag.get()
        ) {
            currentCoroutineContext().ensureActive()
            val n = minOf(STREAM_CHUNK, boosted.size - offset)
            writeFully(audioTrack, boosted, offset, n)
            offset += n
        }
        if (!interruptFlag.get()) {
            delay(40)
        }
        releaseTrack()
    }

    /**
     * Boost PCM16 LE amplitude for louder TTS, with tanh soft-clip into int16 range.
     * Mutates [pcm] in place and returns it.
     */
    private fun amplifyPcm16LeInPlace(pcm: ByteArray, logGain: Boolean = false): ByteArray {
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val amplified = sample * PCM_GAIN
            val clipped = softClipToInt16(amplified)
            pcm[i] = (clipped and 0xFF).toByte()
            pcm[i + 1] = ((clipped shr 8) and 0xFF).toByte()
            i += 2
        }
        if (logGain) {
            RingBufferLogger.log(
                "tts",
                "pcm_gain=$PCM_GAIN soft_clip=tanh bytes=${pcm.size}",
            )
        }
        return pcm
    }

    /** Soft-clip a gain-amplified sample into signed 16-bit range. */
    private fun softClipToInt16(amplified: Float): Int {
        // tanh keeps peaks below full-scale without hard brick-wall distortion.
        val y = tanh((amplified / 32768.0)) * 32767.0
        return y.toInt().coerceIn(-32768, 32767)
    }

    /**
     * Non-blocking writes with polling so a stuck AudioTrack cannot hang the agent loop forever.
     * [interrupt] / cancellation unblocks by pausing/flushing the track.
     */
    private suspend fun writeFully(
        audioTrack: AudioTrack,
        data: ByteArray,
        start: Int,
        length: Int,
    ) {
        var offset = 0
        var idleSpins = 0
        while (offset < length &&
            currentCoroutineContext().isActive &&
            !interruptFlag.get()
        ) {
            currentCoroutineContext().ensureActive()
            val written = audioTrack.write(
                data,
                start + offset,
                length - offset,
                AudioTrack.WRITE_NON_BLOCKING,
            )
            when {
                written > 0 -> {
                    offset += written
                    idleSpins = 0
                }
                written == 0 -> {
                    idleSpins++
                    if (idleSpins > MAX_IDLE_SPINS) {
                        error("AudioTrack write stalled (buffer not draining)")
                    }
                    delay(WRITE_POLL_MS)
                }
                else -> error("AudioTrack write failed: $written")
            }
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, ENCODING)
        val bufSize = minBuf.coerceAtLeast(STREAM_CHUNK * 4)
        // USAGE_MEDIA (+ SPEECH): routes to media volume (what users max), not quiet sonification.
        // USAGE_ASSISTANT is an alternate loud assistant path; MEDIA matches volume-key expectation.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val maxVol = AudioTrack.getMaxVolume()
        track.setVolume(maxVol)
        RingBufferLogger.log(
            "tts",
            "AudioTrack usage=USAGE_MEDIA content=SPEECH volume=$maxVol " +
                "pcm_gain=$PCM_GAIN soft_clip=tanh sampleRate=$sampleRate",
        )
        return track
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    companion object {
        const val OPENAI_MODEL = "gpt-4o-mini-tts"
        const val OPENAI_VOICE = "alloy"
        /** OpenAI `response_format=pcm` is 24 kHz 16-bit mono LE. */
        const val OPENAI_SAMPLE_RATE = 24_000
        /** Hard cap so speak never blocks the agent loop indefinitely. */
        const val SPEAK_TIMEOUT_MS = 15_000L
        /** How long [speakAsync] waits before returning success to the agent tool. */
        const val ASYNC_KICKOFF_MS = 200L
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val STREAM_CHUNK = 4096
        private const val WRITE_POLL_MS = 10L
        /** ~5s of zero-progress writes before abort. */
        private const val MAX_IDLE_SPINS = 500
        /** Software gain on PCM16 samples (Sarvam/Deepgram/OpenAI) before soft-clip. */
        const val PCM_GAIN = 1.8f
    }
}
