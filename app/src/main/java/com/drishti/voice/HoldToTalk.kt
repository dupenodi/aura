package com.drishti.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.drishti.ai.ApiKeyStore
import com.drishti.core.agent.Language
import com.drishti.core.llm.LlmException
import com.drishti.core.speech.SarvamRealtimeStt
import com.drishti.core.speech.SarvamRestStt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Hold the orb and talk; let go to send. Partial text streams back while they speak so they
 * can see they are being heard.
 */
interface HoldToTalk {
    /** Why listening couldn't produce a request, in terms we can explain. */
    enum class Failure { NoPermission, Unavailable, NoSpeech, Error }

    fun hasPermission(): Boolean

    /**
     * [languageTag] null means detect it. [onFinal] gets the transcript and, when the
     * engine detected it, the language it was spoken in.
     */
    fun start(
        languageTag: String?,
        onPartial: (String) -> Unit,
        onFinal: (text: String, languageTag: String?) -> Unit,
        onFailure: (Failure) -> Unit,
    )

    /** Finger lifted: finish and deliver the final transcript. */
    fun stop()

    /** Dragged away or torn down: deliver nothing. */
    fun cancel()

    companion object {
        /** Sarvam when it is chosen and has a key; the phone's recogniser otherwise. */
        fun create(context: Context, scope: CoroutineScope, provider: SpeechProvider, keyterms: () -> List<String>): HoldToTalk =
            if (provider == SpeechProvider.Sarvam && ApiKeyStore.resolve("sarvam").isNotBlank()) {
                SarvamHoldToTalk(context, scope, keyterms)
            } else {
                VoiceSession(context)
            }
    }
}

/**
 * Sarvam realtime speech-to-text (saaras:v4) for hold-to-talk.
 *
 * Recording and streaming are kept apart: the microphone fills a buffer and a channel at
 * the same time. If the socket can't connect or drops, the person is still holding and
 * talking — so recording carries on, and when they let go the whole clip goes to the REST
 * endpoint instead. They never have to say it twice because of the network.
 */
class SarvamHoldToTalk(
    private val context: Context,
    private val scope: CoroutineScope,
    private val keyterms: () -> List<String>,
) : HoldToTalk {
    private val key = { ApiKeyStore.resolve("sarvam") }
    private val realtime = SarvamRealtimeStt(key)
    private val rest = SarvamRestStt(key)
    private val mic = MicRecorder(SAMPLE_RATE)
    private var job: Job? = null
    @Volatile
    private var cancelled = false

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun start(
        languageTag: String?,
        onPartial: (String) -> Unit,
        onFinal: (text: String, languageTag: String?) -> Unit,
        onFailure: (HoldToTalk.Failure) -> Unit,
    ) {
        if (!hasPermission()) {
            onFailure(HoldToTalk.Failure.NoPermission)
            return
        }
        cancelled = false
        // Null asks Sarvam to work the language out from the audio.
        val language = languageTag?.let { Language.fromTag(it) }
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val clip = ByteArrayOutputStream()
        val recorded = CompletableDeferred<Boolean>()

        // The microphone loop blocks, so it gets its own IO thread.
        scope.launch(Dispatchers.IO) {
            val ok = mic.record { chunk ->
                synchronized(clip) { clip.write(chunk) }
                chunks.trySend(chunk)
            }
            chunks.close()
            recorded.complete(ok)
        }

        job = scope.launch(Dispatchers.IO) {
            val heard = try {
                realtime.transcribe(
                    chunks.consumeAsFlow(),
                    SarvamRealtimeStt.Config(language = language, keyterms = keyterms(), sampleRate = SAMPLE_RATE),
                    onPartial = { if (!cancelled) onPartial(it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                Log.w(TAG, "realtime failed (${e.kind}), sending the clip instead: ${e.message}")
                if (!recorded.await()) {
                    onFailure(HoldToTalk.Failure.Unavailable)
                    return@launch
                }
                val pcm = synchronized(clip) { clip.toByteArray() }
                if (pcm.size < SAMPLE_RATE / 5) {
                    onFailure(HoldToTalk.Failure.NoSpeech)
                    return@launch
                }
                runCatching { rest.transcribe(pcm, SAMPLE_RATE, language) }.getOrElse {
                    Log.w(TAG, "REST speech failed too: ${it.message}")
                    onFailure(HoldToTalk.Failure.Error)
                    return@launch
                }
            }
            if (cancelled) return@launch
            val text = heard.text.trim()
            if (!recorded.await()) {
                onFailure(HoldToTalk.Failure.Unavailable)
            } else if (text.isBlank()) {
                onFailure(HoldToTalk.Failure.NoSpeech)
            } else {
                onFinal(text, (heard.language ?: Language.detect(text))?.tag)
            }
        }
    }

    override fun stop() {
        mic.stop()
    }

    override fun cancel() {
        cancelled = true
        mic.stop()
        job?.cancel()
    }

    companion object {
        private const val TAG = "SarvamHoldToTalk"
        private const val SAMPLE_RATE = 16_000
    }
}
