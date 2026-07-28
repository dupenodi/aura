package com.drishti.ai

import com.drishti.BuildConfig
import com.drishti.debug.RingBufferLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Deepgram REST helpers (STT listen + TTS speak).
 * Auth: `Authorization: Token <DEEPGRAM_API_KEY>`
 */
object DeepgramClient {
    const val BASE_URL = "https://api.deepgram.com/"
    const val STT_MODEL = "nova-2"
    const val TTS_MODEL = "aura-asteria-en"
    /** linear16 PCM sample rate for Aura speak. */
    const val TTS_SAMPLE_RATE = 24_000

    fun hasApiKey(): Boolean = BuildConfig.DEEPGRAM_API_KEY.isNotBlank()

    fun createOkHttp(): OkHttpClient {
        val key = BuildConfig.DEEPGRAM_API_KEY
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
                    .header("Authorization", "Token $key")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()
    }

    fun transcribeWav(client: OkHttpClient, wavFile: File): String {
        val url =
            "${BASE_URL}v1/listen?model=$STT_MODEL&smart_format=true&punctuate=true"
        val body = wavFile.asRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "audio/wav")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Deepgram STT HTTP ${response.code}: ${raw.take(300)}")
            }
            val root = JSONObject(raw)
            val transcript = root
                .optJSONObject("results")
                ?.optJSONArray("channels")
                ?.optJSONObject(0)
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optString("transcript")
                ?.trim()
                .orEmpty()
            if (transcript.isEmpty()) error("Deepgram STT returned empty transcript")
            return transcript
        }
    }

    /**
     * Returns raw linear16 LE PCM bytes at [TTS_SAMPLE_RATE] mono.
     */
    fun speakLinear16(client: OkHttpClient, text: String): ByteArray {
        val url =
            "${BASE_URL}v1/speak?model=$TTS_MODEL&encoding=linear16&sample_rate=$TTS_SAMPLE_RATE"
        val json = JSONObject().put("text", text).toString()
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(300).orEmpty()
                error("Deepgram TTS HTTP ${response.code}: $err")
            }
            return response.body?.bytes() ?: error("Deepgram TTS empty body")
        }
    }
}
