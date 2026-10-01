package com.drishti.voice

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.drishti.ai.ApiKeyStore
import com.drishti.core.agent.Language
import com.drishti.core.agent.Voice
import com.drishti.core.speech.SarvamTts
import com.drishti.data.AuraPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Aura's voice.
 *
 * Sarvam Bulbul v3 when it is chosen and a key is set — natural Indian-language speech,
 * streamed so the first word plays as soon as it is synthesised — and the phone's own TTS
 * otherwise, or whenever the network lets us down. Saying something new cuts off whatever
 * was being said, and [stop] is instant: when the user acts, Aura goes quiet.
 */
class AuraVoice(context: Context) : Voice {
    private val prefs = AuraPrefs.get(context)
    private val platform = SpeechOutput(context)
    private val player = PcmPlayer(context)
    private val sarvam = SarvamTts({ ApiKeyStore.resolve("sarvam") })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Recently spoken lines, as audio. Instructions are repeated when someone hesitates and
     * the fixed phrases recur every session; replaying them costs nothing.
     */
    private val cache = object : LruCache<String, Pair<ByteArray, Int>>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Pair<ByteArray, Int>) = value.first.size
    }

    private fun useSarvam(language: Language): Boolean =
        prefs.speechProvider.value == SpeechProvider.Sarvam &&
            language.sarvamTts &&
            ApiKeyStore.resolve("sarvam").isNotBlank()

    override fun say(text: String, language: Language) {
        stop()
        if (!prefs.speakAloud.value || text.isBlank()) return
        if (!useSarvam(language)) {
            speakOnDevice(text, language)
            return
        }
        val voice = SarvamTts.VoiceConfig(speaker = prefs.voiceSpeaker.value, pace = prefs.voicePace.value)
        val key = "${language.tag}|${voice.speaker}|${voice.pace}|$text"
        job = scope.launch {
            try {
                val cached = cache.get(key)
                if (cached != null) {
                    player.begin(cached.second)
                    player.write(cached.first)
                } else {
                    val all = ByteArrayOutputStream()
                    var started = false
                    // The REST fallback is asked for the same sample rate, so one player setup serves both.
                    val rate = sarvam.speak(text, language, voice) { pcm ->
                        ensureActive()
                        if (!started) {
                            player.begin(voice.sampleRate)
                            started = true
                        }
                        all.write(pcm)
                        player.write(pcm)
                    }
                    cache.put(key, all.toByteArray() to rate)
                }
                // Let the tail play out, then hand audio focus back.
                delay(player.remainingMs() + 50)
                player.end()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sarvam TTS failed, using the phone's voice: ${e.message}")
                player.stop()
                speakOnDevice(text, language)
            }
        }
    }

    private fun speakOnDevice(text: String, language: Language) {
        platform.enabled = true
        platform.setLanguage(AuraLanguage.fromTag(language.tag))
        platform.speak(text)
    }

    override fun stop() {
        job?.cancel()
        job = null
        player.stop()
        platform.stop()
    }

    fun shutdown() {
        stop()
        scope.cancel()
        player.release()
        platform.shutdown()
    }

    companion object {
        private const val TAG = "AuraVoice"
        private const val CACHE_BYTES = 4 * 1024 * 1024
    }
}
