package com.drishti.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * A hold-to-talk recording session.
 *
 * The design is "hold the orb and talk, release to send", which the one-shot recogniser
 * couldn't express: it had no way to be stopped and no partial text to show. This wraps
 * the recogniser so the caller can start on press, stream the live transcript into the
 * bubble, and finish the moment the finger lifts.
 */
class VoiceSession(private val context: Context) : HoldToTalk {

    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onFailure: ((HoldToTalk.Failure) -> Unit)? = null

    /** Best transcript so far, used when the user releases before the engine settles. */
    private var latestPartial: String = ""

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening. [languageTag] is a BCP-47 tag such as "en-IN" or "hi-IN"; the
     * platform recogniser falls back to its default when it can't serve that language.
     */
    override fun start(
        languageTag: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onFailure: (HoldToTalk.Failure) -> Unit,
    ) {
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onFailure = onFailure
        finished = false
        latestPartial = ""

        if (!hasPermission()) {
            fail(HoldToTalk.Failure.NoPermission)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail(HoldToTalk.Failure.Unavailable)
            return
        }

        val engine = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull() ?: run { fail(HoldToTalk.Failure.Unavailable); return }
        recognizer = engine

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    latestPartial = text
                    this@VoiceSession.onPartial?.invoke(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .ifBlank { latestPartial }
                if (text.isBlank()) fail(HoldToTalk.Failure.NoSpeech) else succeed(text)
            }

            override fun onError(error: Int) {
                // A stop() with speech already captured surfaces as an error on some
                // engines; prefer what we heard over reporting a failure.
                if (latestPartial.isNotBlank()) {
                    succeed(latestPartial)
                    return
                }
                fail(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> HoldToTalk.Failure.NoPermission
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> HoldToTalk.Failure.NoSpeech
                        else -> HoldToTalk.Failure.Error
                    },
                )
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { engine.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening failed: ${it.message}")
                fail(HoldToTalk.Failure.Error)
            }
    }

    /** Finger lifted: stop recording and let the engine deliver its final result. */
    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Abandon the session entirely (drag, or the service going away). */
    override fun cancel() {
        finished = true
        runCatching { recognizer?.cancel() }
        destroy()
    }

    private fun succeed(text: String) {
        if (finished) return
        finished = true
        onFinal?.invoke(text)
        destroy()
    }

    private fun fail(reason: HoldToTalk.Failure) {
        if (finished) return
        finished = true
        onFailure?.invoke(reason)
        destroy()
    }

    private fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        onPartial = null
        onFinal = null
        onFailure = null
    }

    companion object {
        private const val TAG = "VoiceSession"
    }
}
