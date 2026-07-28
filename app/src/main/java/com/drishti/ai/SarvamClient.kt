package com.drishti.ai

import android.util.Base64
import com.drishti.BuildConfig
import com.drishti.debug.RingBufferLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sarvam REST helpers (STT Saaras + TTS Bulbul).
 * Auth: `api-subscription-key: <SARVAM_API_KEY>` (not Bearer).
 */
object SarvamClient {
    const val BASE_URL = "https://api.sarvam.ai/"
    const val STT_MODEL = "saaras:v3"
    const val STT_MODE = "transcribe"
    const val TTS_MODEL = "bulbul:v3"
    const val TTS_SPEAKER = "shubh"
    /** Default BCP-47 when STT did not detect a language. */
    const val TTS_LANGUAGE = "en-IN"
    /** Request linear16 PCM for AudioTrack playback. */
    const val TTS_SAMPLE_RATE = 24_000
    const val TTS_CODEC = "linear16"

    data class SttResult(
        val transcript: String,
        /** Raw language_code from Sarvam when present (e.g. kn-IN, hi-IN). */
        val languageCode: String? = null,
        /**
         * English (or other) translation when Sarvam returns one
         * (e.g. `translated_text` on translate / STT-translate responses).
         */
        val translatedText: String? = null,
    )

    fun hasApiKey(): Boolean = BuildConfig.SARVAM_API_KEY.isNotBlank()

    fun createOkHttp(): OkHttpClient {
        val key = BuildConfig.SARVAM_API_KEY
        val logging = HttpLoggingInterceptor { msg ->
            val trimmed = if (msg.length > 400) msg.take(400) + "…" else msg
            RingBufferLogger.log("http", trimmed)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("api-subscription-key", key)
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()
    }

    /**
     * Multipart WAV → transcript via Saaras v3.
     * [language_code] `unknown` enables auto-detect (hi-IN, kn-IN, en-IN, …).
     */
    fun transcribeWav(client: OkHttpClient, wavFile: File): SttResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("mode", STT_MODE)
            .addFormDataPart("language_code", "unknown")
            .build()
        val request = Request.Builder()
            .url("${BASE_URL}speech-to-text")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Sarvam STT HTTP ${response.code}: ${raw.take(300)}")
            }
            val obj = JSONObject(raw)
            val transcript = obj.optString("transcript").trim()
            if (transcript.isEmpty()) error("Sarvam STT returned empty transcript")
            val language = obj.optString("language_code")
                .ifBlank { obj.optString("language") }
                .trim()
                .ifBlank { null }
            // Optional English (or other) translation when present; mode=transcribe usually omits it.
            val translated = sequenceOf(
                obj.optString("translated_text"),
                obj.optString("translation"),
                obj.optString("english_translation"),
            ).map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.equals(transcript, ignoreCase = true) }
            return SttResult(
                transcript = transcript,
                languageCode = language,
                translatedText = translated,
            )
        }
    }

    fun buildTtsRequest(text: String, targetLanguageCode: String = TTS_LANGUAGE): Request {
        val lang = normalizeTtsLanguage(targetLanguageCode)
        val json = JSONObject()
            .put("text", text)
            .put("target_language_code", lang)
            .put("model", TTS_MODEL)
            .put("speaker", TTS_SPEAKER)
            .put("speech_sample_rate", TTS_SAMPLE_RATE.toString())
            .put("output_audio_codec", TTS_CODEC)
            .toString()
        return Request.Builder()
            .url("${BASE_URL}text-to-speech")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Decode Sarvam TTS JSON → raw linear16 LE PCM at [TTS_SAMPLE_RATE] mono.
     * Strips a WAV header if the payload is RIFF/WAVE.
     */
    fun decodeTtsPcm(responseBody: String): ByteArray {
        val audios = JSONObject(responseBody).optJSONArray("audios")
            ?: error("Sarvam TTS missing audios")
        if (audios.length() == 0) error("Sarvam TTS empty audios")
        val b64 = audios.optString(0).orEmpty()
        if (b64.isBlank()) error("Sarvam TTS empty audio payload")
        val decoded = Base64.decode(b64, Base64.DEFAULT)
        return stripWavHeaderIfPresent(decoded)
    }

    /**
     * Returns raw linear16 LE PCM bytes at [TTS_SAMPLE_RATE] mono.
     */
    fun speakLinear16(
        client: OkHttpClient,
        text: String,
        targetLanguageCode: String = TTS_LANGUAGE,
    ): ByteArray {
        val call = client.newCall(buildTtsRequest(text, targetLanguageCode))
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Sarvam TTS HTTP ${response.code}: ${raw.take(300)}")
            }
            return decodeTtsPcm(raw)
        }
    }

    /**
     * Map common Sarvam / short codes → Bulbul `target_language_code`.
     * Defaults to en-IN when unknown.
     */
    fun normalizeTtsLanguage(code: String?): String {
        if (code.isNullOrBlank()) return TTS_LANGUAGE
        val raw = code.trim().replace('_', '-')
        val lower = raw.lowercase()
        // Already BCP-47 style
        when (lower) {
            "kn-in", "kn" -> return "kn-IN"
            "en-in", "en", "en-us", "en-gb" -> return "en-IN"
            "hi-in", "hi" -> return "hi-IN"
            "ta-in", "ta" -> return "ta-IN"
            "te-in", "te" -> return "te-IN"
            "ml-in", "ml" -> return "ml-IN"
            "mr-in", "mr" -> return "mr-IN"
            "bn-in", "bn" -> return "bn-IN"
            "gu-in", "gu" -> return "gu-IN"
            "pa-in", "pa" -> return "pa-IN"
            "or-in", "or", "od-in", "od" -> return "od-IN"
            "as-in", "as" -> return "as-IN"
            "kn_in" -> return "kn-IN"
        }
        // Preserve region casing if looks like xx-YY
        val parts = raw.split('-')
        if (parts.size == 2 && parts[0].length == 2 && parts[1].length == 2) {
            return "${parts[0].lowercase()}-${parts[1].uppercase()}"
        }
        if (parts.size == 1 && parts[0].length == 2) {
            return "${parts[0].lowercase()}-IN"
        }
        return TTS_LANGUAGE
    }

    /** True for Indic TTS codes where OpenAI English fallback is a poor match. */
    fun isIndicLanguage(code: String?): Boolean {
        val lang = normalizeTtsLanguage(code)
        if (lang == "en-IN") return false
        return lang.endsWith("-IN")
    }

    /** If payload starts with RIFF/WAVE, return PCM from the data chunk. */
    internal fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        val riff = bytes.decodeToString(0, 4)
        val wave = bytes.decodeToString(8, 12)
        if (riff != "RIFF" || wave != "WAVE") return bytes
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.decodeToString(offset, offset + 4)
            val chunkSize =
                (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            offset += 8
            if (chunkId == "data") {
                val end = (offset + chunkSize).coerceAtMost(bytes.size)
                return bytes.copyOfRange(offset, end)
            }
            offset += chunkSize
        }
        // Fallback: skip classic 44-byte PCM header.
        return bytes.copyOfRange(44, bytes.size)
    }
}
