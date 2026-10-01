package com.drishti.core.speech

import com.drishti.core.agent.Language
import com.drishti.core.llm.Http
import com.drishti.core.llm.LlmException
import com.drishti.core.llm.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SttResult(
    val text: String,
    /** What Sarvam detected, when it was asked to detect. */
    val language: Language?,
    /** From the end of speech to the final transcript. */
    val finalLatencyMs: Long,
)

/**
 * Sarvam realtime speech-to-text over a WebSocket (`/speech-to-text-realtime/ws`).
 *
 * Built for hold-to-talk: manual endpointing, so pressing the orb sends `speech_start`,
 * lifting the finger sends `speech_end`, and the final transcript follows straight away
 * instead of waiting for the server to notice silence. Partials stream back while they speak
 * so the bubble can show what is being heard.
 */
class SarvamRealtimeStt(
    private val apiKey: () -> String,
    private val http: OkHttpClient = Http.shared,
    private val baseUrl: String = "wss://api.sarvam.ai",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        /** Null lets Sarvam detect the language. */
        val language: Language?,
        /** `saaras:v4` is the only realtime model that takes keyterms. */
        val model: String = "saaras:v4",
        /** codemix keeps English words (app names) in Latin script, as they appear on screen. */
        val mode: String = "codemix",
        /** App names and setting words to bias recognition towards; at most 50. */
        val keyterms: List<String> = emptyList(),
        val streamType: String = "fast",
        val sampleRate: Int = 16_000,
        /** How long to wait for the final transcript after they stop. */
        val finalTimeoutMs: Long = 5_000,
    )

    fun url(config: Config): String {
        val b = (baseUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://") + "/speech-to-text-realtime/ws")
            .toHttpUrl().newBuilder()
            .addQueryParameter("language_code", config.language?.sarvamRealtimeCode ?: "auto")
            .addQueryParameter("model", config.model)
            .addQueryParameter("mode", config.mode)
            .addQueryParameter("stream_type", config.streamType)
            .addQueryParameter("endpointing", "manual")
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("sample_rate", config.sampleRate.toString())
        val terms = config.keyterms.map { it.trim().take(64) }.filter { it.isNotEmpty() }.distinct().take(50)
        if (terms.isNotEmpty() && config.model == "saaras:v4") {
            b.addQueryParameter("keyterms", JsonArray(terms.map { JsonPrimitive(it) }).toString())
        }
        return b.build().toString()
    }

    /**
     * Streams [audio] (16-bit mono PCM chunks) until the flow completes — the finger lifting —
     * and returns the final transcript. Throws [LlmException] on auth, quota or network
     * failure so the caller can fall back to another recogniser.
     */
    suspend fun transcribe(audio: Flow<ByteArray>, config: Config, onPartial: (String) -> Unit = {}): SttResult {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.NotConfigured, "sarvam key missing")

        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val failure = CompletableDeferred<LlmException>()
        val request = Request.Builder()
            .url(url(config).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://"))
            .header("Api-Subscription-Key", key)
            .build()
        val socket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { messages.trySend(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1000) failure.complete(closeError(code, reason))
                    messages.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    messages.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    failure.complete(
                        if (code != null) LlmException.fromHttp(code, response.message)
                        else LlmException(LlmException.Kind.Network, t.message ?: "socket failed", cause = t),
                    )
                    messages.close()
                }
            },
        )

        val final = CompletableDeferred<SttResult>()
        val latestPartial = AtomicReference("")
        val endedAt = AtomicLong(0L)
        try {
            return coroutineScope {
                val reader = launch {
                    val finals = StringBuilder()
                    var detected: Language? = null
                    for (m in messages) {
                        when (m.str("event")) {
                            "transcript.partial" -> m.str("text")?.takeIf { it.isNotBlank() }?.let {
                                latestPartial.set(it)
                                onPartial(it)
                            }
                            "transcript.final" -> {
                                m.str("text")?.takeIf { it.isNotBlank() }?.let {
                                    if (finals.isNotEmpty()) finals.append(' ')
                                    finals.append(it.trim())
                                }
                                detected = Language.fromSarvam(m.str("language")) ?: detected
                                val ended = endedAt.get()
                                if (ended > 0) final.complete(SttResult(finals.toString(), detected, clock() - ended))
                            }
                            "error" -> {
                                val fatal = (m["is_fatal"] as? JsonPrimitive)?.booleanOrNull ?: false
                                if (fatal) failure.complete(LlmException(LlmException.Kind.BadResponse, "sarvam: ${m.str("code")}: ${m.str("message")}"))
                            }
                        }
                    }
                }

                socket.send("""{"event":"speech_start"}""")
                val encoder = Base64.getEncoder()
                audio.collect { chunk ->
                    if (failure.isCompleted) throw failure.getCompleted()
                    if (chunk.isNotEmpty()) socket.send("""{"event":"audio_input","audio":"${encoder.encodeToString(chunk)}"}""")
                }
                endedAt.set(clock())
                socket.send("""{"event":"speech_end"}""")

                val result = withTimeoutOrNull(config.finalTimeoutMs) {
                    kotlinx.coroutines.selects.select<SttResult?> {
                        final.onAwait { it }
                        failure.onAwait { throw it }
                    }
                } ?: SttResult(latestPartial.get(), null, clock() - endedAt.get())
                socket.send("""{"event":"end"}""")
                reader.cancel()
                result
            }
        } finally {
            socket.close(1000, null)
        }
    }

    private fun closeError(code: Int, reason: String): LlmException = when (code) {
        1003 -> LlmException(LlmException.Kind.Auth, "sarvam closed $code: $reason")
        1008 -> LlmException(LlmException.Kind.Timeout, "sarvam idle: $reason")
        4000 -> LlmException(LlmException.Kind.BadResponse, "sarvam rejected config: $reason")
        else -> LlmException(LlmException.Kind.Server, "sarvam closed $code: $reason")
    }
}

/**
 * Sarvam REST speech-to-text for a recorded clip (up to 30 seconds): the fallback when the
 * realtime socket can't connect.
 */
class SarvamRestStt(
    private val apiKey: () -> String,
    private val http: OkHttpClient = Http.shared,
    private val baseUrl: String = "https://api.sarvam.ai",
) {
    suspend fun transcribe(
        pcm: ByteArray,
        sampleRate: Int,
        language: Language?,
        model: String = "saaras:v4",
        mode: String = "codemix",
    ): SttResult {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.NotConfigured, "sarvam key missing")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", Wav.wrap(pcm, sampleRate).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("mode", mode)
            .addFormDataPart("language_code", language?.sarvamCode ?: "unknown")
            .build()
        val request = Request.Builder().url("$baseUrl/speech-to-text").header("api-subscription-key", key).post(body).build()
        val started = System.currentTimeMillis()
        return http.await(request, 20_000) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, raw)
            val json = Json.parseToJsonElement(raw).jsonObject
            SttResult(
                text = json.str("transcript").orEmpty().trim(),
                language = Language.fromSarvam(json.str("language_code")),
                finalLatencyMs = System.currentTimeMillis() - started,
            )
        }
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
