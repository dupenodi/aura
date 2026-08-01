package com.drishti.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** English TextToSpeech wrapper for short narration. */
class SpeechOutput(context: Context) {
    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready.set(true)
            }
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready.get() || text.isBlank()) return
        // TTS is happiest on the main thread; never block callers.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drishti-${System.currentTimeMillis()}")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
