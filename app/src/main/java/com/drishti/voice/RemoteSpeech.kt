package com.drishti.voice

import android.util.Base64
import android.util.Log
import com.drishti.ai.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloud speech engines, used when the user picks one and a key is configured.
 *
 * Both are optional by design: Aura works entirely on-device, and these only add
 * better regional-language accuracy for people who want it. Every method returns null
 * rather than throwing, so a missing key or a dead network degrades to the on-device
 * engine instead of breaking voice input.
 */
object RemoteSpeech {

    private const val TAG = "RemoteSpeech"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(provider: SpeechProvider): Boolean = when (provider) {
        SpeechProvider.OnDevice -> true
        SpeechProvider.Sarvam -> ApiKeyStore.resolve("sarvam").isNotBlank()
        SpeechProvider.Deepgram -> ApiKeyStore.resolve("deepgram").isNotBlank()
    }

    // ---- Speech to text -----------------------------------------------------------

    /** Transcribes a recorded audio file. Returns null when unavailable. */
    fun transcribe(
        provider: SpeechProvider,
        audio: File,
        language: AuraLanguage,
    ): String? = when (provider) {
        SpeechProvider.OnDevice -> null
        SpeechProvider.Sarvam -> sarvamTranscribe(audio, language)
        SpeechProvider.Deepgram -> deepgramTranscribe(audio, language)
    }

    private fun sarvamTranscribe(audio: File, language: AuraLanguage): String? {
        val key = ApiKeyStore.resolve("sarvam").ifBlank { return null }
        return runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audio.name,
                    audio.asRequestBody("audio/wav".toMediaType()),
                )
                .addFormDataPart("language_code", language.sarvamCode)
                .addFormDataPart("model", "saarika:v2")
                .build()
            val request = Request.Builder()
                .url("https://api.sarvam.ai/speech-to-text")
                .addHeader("api-subscription-key", key)
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Sarvam STT ${resp.code}: ${raw.take(200)}")
                    return null
                }
                JSONObject(raw).optString("transcript").takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "Sarvam STT failed: ${it.message}")
            null
        }
    }

    private fun deepgramTranscribe(audio: File, language: AuraLanguage): String? {
        val key = ApiKeyStore.resolve("deepgram").ifBlank { return null }
        return runCatching {
            val url = "https://api.deepgram.com/v1/listen" +
                "?model=nova-2&smart_format=true&language=${language.tag}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token $key")
                .addHeader("Content-Type", "audio/wav")
                .post(audio.readBytes().toRequestBody("audio/wav".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Deepgram STT ${resp.code}: ${raw.take(200)}")
                    return null
                }
                JSONObject(raw)
                    .optJSONObject("results")
                    ?.optJSONArray("channels")
                    ?.optJSONObject(0)
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                    ?.optString("transcript")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "Deepgram STT failed: ${it.message}")
            null
        }
    }

    // ---- Text to speech -----------------------------------------------------------

    /**
     * Synthesises [text] and returns WAV bytes, or null to fall back to on-device TTS.
     * Only Sarvam is wired here — its Indian-language voices are the reason to leave
     * the device at all.
     */
    fun synthesize(
        provider: SpeechProvider,
        text: String,
        language: AuraLanguage,
    ): ByteArray? {
        if (provider != SpeechProvider.Sarvam) return null
        val key = ApiKeyStore.resolve("sarvam").ifBlank { return null }
        return runCatching {
            val payload = JSONObject().apply {
                put("inputs", org.json.JSONArray().put(text.take(500)))
                put("target_language_code", language.sarvamCode)
                put("speaker", "meera")
                put("model", "bulbul:v1")
            }
            val request = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", key)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Sarvam TTS ${resp.code}: ${raw.take(200)}")
                    return null
                }
                val b64 = JSONObject(raw).optJSONArray("audios")?.optString(0).orEmpty()
                if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
            }
        }.getOrElse {
            Log.w(TAG, "Sarvam TTS failed: ${it.message}")
            null
        }
    }
}
