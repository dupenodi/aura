package com.drishti.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short spoken narration.
 *
 * Uses whatever voice the phone already has for the chosen language, so regional
 * languages work as soon as the user has that TTS voice installed. When they don't we
 * record that fact rather than silently pretending it worked.
 */
class SpeechOutput(context: Context) {

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null

    @Volatile
    private var language: AuraLanguage = AuraLanguage.English

    /** False when the chosen language has no installed voice on this device. */
    @Volatile
    var languageAvailable: Boolean = true
        private set

    /** Muted when the user turns "Read steps aloud" off. */
    @Volatile
    var enabled: Boolean = true

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready.set(true)
                applyLanguage()
            }
        }
    }

    fun setLanguage(language: AuraLanguage) {
        if (this.language == language) return
        this.language = language
        if (ready.get()) applyLanguage()
    }

    /** Whether the device can speak [language] without downloading anything. */
    fun supports(language: AuraLanguage): Boolean {
        val engine = tts ?: return false
        val result = runCatching { engine.isLanguageAvailable(language.locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private fun applyLanguage() {
        val engine = tts ?: return
        val result = runCatching { engine.setLanguage(language.locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        languageAvailable = result >= TextToSpeech.LANG_AVAILABLE
        if (!languageAvailable) {
            Log.i(TAG, "No installed voice for ${language.label}; using the default voice")
            runCatching { engine.setLanguage(AuraLanguage.English.locale) }
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!enabled || !ready.get() || text.isBlank()) return
        // TTS is happiest on the main thread; never block callers.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "aura-${System.currentTimeMillis()}",
            )
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
