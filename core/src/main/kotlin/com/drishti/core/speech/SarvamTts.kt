package com.drishti.core.speech

import com.drishti.core.agent.Language
import com.drishti.core.llm.Http
import com.drishti.core.llm.LlmException
import com.drishti.core.llm.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Sarvam Bulbul v3 text-to-speech.
 *
 * Streams raw 16-bit PCM from `/text-to-speech/stream` so playback starts with the first
 * chunk rather than after the whole sentence is synthesised, and falls back to the REST
 * endpoint if streaming is refused. Both default to Bulbul v2 on Sarvam's side, so the
 * model is always sent explicitly.
 */
class SarvamTts(
    private val apiKey: () -> String,
    private val http: OkHttpClient = Http.shared,
    private val baseUrl: String = "https://api.sarvam.ai",
) {
    data class VoiceConfig(
        /** One of Bulbul v3's speakers, lowercase. */
        val speaker: String = "priya",
        /** 0.5–2.0. A touch slower than default suits people following instructions. */
        val pace: Double = 0.9,
        val temperature: Double = 0.6,
        val sampleRate: Int = 24_000,
        val model: String = "bulbul:v3",
    )

    @Volatile
    private var streamingRefused = false

    /**
     * Speaks [text] into [onPcm] as audio arrives. Returns the sample rate of what was sent.
     * Cancelling the coroutine cancels the request.
     */
    suspend fun speak(text: String, language: Language, voice: VoiceConfig, onPcm: (ByteArray) -> Unit): Int {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.NotConfigured, "sarvam key missing")
        if (!streamingRefused) {
            try {
                return stream(key, text, language, voice, onPcm)
            } catch (e: LlmException) {
                // A 4xx here means this account or codec can't stream; don't keep asking.
                if (e.httpCode in 400..499 && e.kind != LlmException.Kind.Auth && e.kind != LlmException.Kind.RateLimit) {
                    streamingRefused = true
                } else {
                    throw e
                }
            }
        }
        val wav = synthesize(key, text, language, voice)
        onPcm(Wav.pcmOf(wav))
        return Wav.sampleRate(wav) ?: voice.sampleRate
    }

    internal fun body(text: String, language: Language, voice: VoiceConfig, codec: String) = buildJsonObject {
        put("text", text.take(MAX_CHARS))
        put("language_code", language.sarvamCode)
        put("model", voice.model)
        put("speaker", voice.speaker)
        put("pace", voice.pace)
        put("temperature", voice.temperature)
        put("speech_sample_rate", voice.sampleRate)
        put("output_audio_codec", codec)
    }

    private suspend fun stream(key: String, text: String, language: Language, voice: VoiceConfig, onPcm: (ByteArray) -> Unit): Int {
        val request = Request.Builder()
            .url("$baseUrl/text-to-speech/stream")
            .header("api-subscription-key", key)
            .post(body(text, language, voice, "linear16").toString().toRequestBody(JSON))
            .build()
        return http.await(request, 30_000) { resp ->
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, resp.body?.string().orEmpty())
            val source = resp.body!!.source()
            val buf = ByteArray(CHUNK)
            var first = true
            var rate = voice.sampleRate
            // A network chunk can split a 16-bit sample; carry the odd byte to the next one.
            var carry: Byte? = null
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                var chunk = buf.copyOf(n)
                if (first) {
                    first = false
                    // Some responses carry a WAV header even when raw PCM is asked for.
                    Wav.sampleRate(chunk)?.let { rate = it }
                    chunk = chunk.copyOfRange(Wav.dataOffset(chunk), chunk.size)
                }
                carry?.let { chunk = byteArrayOf(it) + chunk }
                carry = null
                if (chunk.size % 2 == 1) {
                    carry = chunk.last()
                    chunk = chunk.copyOf(chunk.size - 1)
                }
                if (chunk.isNotEmpty()) onPcm(chunk)
            }
            rate
        }
    }

    private suspend fun synthesize(key: String, text: String, language: Language, voice: VoiceConfig): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/text-to-speech")
            .header("api-subscription-key", key)
            .post(body(text, language, voice, "wav").toString().toRequestBody(JSON))
            .build()
        return http.await(request, 30_000) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, raw)
            val audio = (Json.parseToJsonElement(raw).jsonObject["audios"] as? JsonArray)
                ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw LlmException(LlmException.Kind.BadResponse, "no audio in response")
            Base64.getDecoder().decode(audio)
        }
    }

    companion object {
        /** Bulbul v3 takes up to 2500 characters; Aura's lines are far shorter. */
        const val MAX_CHARS = 2_500
        private const val CHUNK = 4_800
        private val JSON = "application/json".toMediaType()

        val SPEAKERS = listOf(
            "priya", "ritu", "neha", "pooja", "simran", "kavya", "ishita", "shreya", "roopa", "tanya", "shruti", "suhani", "kavitha", "rupali",
            "shubh", "aditya", "rahul", "rohan", "amit", "dev", "ratan", "varun", "manan", "sumit", "kabir", "aayan", "ashutosh", "advait",
            "anand", "tarun", "sunny", "mani", "gokul", "vijay", "mohit", "rehan", "soham",
        )
    }
}
