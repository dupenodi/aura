package com.drishti.ai

import com.drishti.BuildConfig
import com.drishti.debug.RingBufferLogger
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface OpenAiApi {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(@Body body: ChatCompletionRequest): ChatCompletionResponse
}

/**
 * Thin OpenAI Chat Completions client.
 * Key comes from [BuildConfig.OPENAI_API_KEY] (local.properties → buildConfigField).
 *
 * Models (not gpt-4o):
 * - [MODEL_PLANNING] / [MODEL_VISION] / [MODEL_ESCALATED]: gpt-5.6 — default (faster than gpt-4.1 vision)
 * - [MODEL_FAST]: gpt-4.1-mini — optional tree-only fast path
 * - [MODEL_GPT5]: gpt-5 — optional alternate GPT-5 family id
 * - [MODEL_FALLBACK]: gpt-4.1 — auto-used on model_not_found / 404 / model 400
 *
 * Token caps are model-aware: gpt-5* → `max_completion_tokens`, gpt-4* → `max_tokens`.
 * Do not override temperature. parallelToolCalls stays false for predictable one-action steps.
 */
@Singleton
class OpenAiClient @Inject constructor(
    private val api: OpenAiApi,
) {
    fun hasApiKey(): Boolean = BuildConfig.OPENAI_API_KEY.isNotBlank()

    fun missingKeyMessage(): String =
        "OPENAI_API_KEY is empty. Add OPENAI_API_KEY=sk-... to local.properties and rebuild the app."

    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        parallelToolCalls: Boolean = false,
    ): Result<ChatCompletionResponse> {
        if (!hasApiKey()) {
            return Result.failure(IllegalStateException(missingKeyMessage()))
        }
        val primary = chatOnce(
            model = model,
            messages = messages,
            tools = tools,
            parallelToolCalls = parallelToolCalls,
        )
        if (primary.isSuccess) return primary

        val err = primary.exceptionOrNull() ?: return primary
        if (model != MODEL_FALLBACK && isModelUnavailableFailure(err)) {
            RingBufferLogger.log(
                "openai",
                "model=$model unavailable; falling back to $MODEL_FALLBACK " +
                    "(${formatOpenAiFailure(err)})",
            )
            return chatOnce(
                model = MODEL_FALLBACK,
                messages = messages,
                tools = tools,
                parallelToolCalls = parallelToolCalls,
            )
        }
        return primary
    }

    private suspend fun chatOnce(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        parallelToolCalls: Boolean,
    ): Result<ChatCompletionResponse> {
        return runCatching {
            val imageMsgs = messages.count { containsImage(it) }
            RingBufferLogger.log(
                "openai",
                "chat model=$model messages=${messages.size} imageMessages=$imageMsgs " +
                    "keyPresent=${hasApiKey()} keyLen=${BuildConfig.OPENAI_API_KEY.length}",
            )
            if (imageMsgs > 0) {
                RingBufferLogger.log("vlm", "OpenAI request includes $imageMsgs image message(s)")
            }
            val response = api.createChatCompletion(
                ChatCompletionRequest(
                    model = model,
                    messages = messages,
                    tools = tools,
                    parallelToolCalls = parallelToolCalls,
                    toolChoice = "auto",
                    maxTokens = maxTokensFor(model),
                    maxCompletionTokens = maxCompletionTokensFor(model),
                ),
            )
            if (response.error != null) {
                error(response.error.message ?: "OpenAI error")
            }
            if (response.choices.isEmpty()) {
                error("OpenAI returned no choices")
            }
            response
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { e ->
                val detailed = formatOpenAiFailure(e)
                RingBufferLogger.log("openai", "error=$detailed")
                Result.failure(IllegalStateException(detailed, e))
            },
        )
    }

    companion object {
        /** Optional alternate GPT-5 family id. */
        const val MODEL_GPT5 = "gpt-5"

        /** Default agent planning model (probed: gpt-5.6). */
        const val MODEL_PLANNING = "gpt-5.6"

        /** Vision / screenshot turns — same default as planning. */
        const val MODEL_VISION = "gpt-5.6"

        /** Optional tree-only fast path (not used as default in AgentLoop). */
        const val MODEL_FAST = "gpt-4.1-mini"

        /** After repeated tool failures. */
        const val MODEL_ESCALATED = "gpt-5.6"

        /** Auto-used when the primary model id is missing / not found. */
        const val MODEL_FALLBACK = "gpt-4.1"

        /**
         * GPT-5 counts reasoning toward the completion budget; leave headroom for tool calls.
         */
        const val GPT5_MAX_COMPLETION_TOKENS = 8192

        /** GPT-4 family output cap (`max_tokens`). */
        const val GPT4_MAX_TOKENS = 8192

        const val BASE_URL = "https://api.openai.com/"

        fun containsImage(message: ChatMessage): Boolean {
            val c = message.content
            return c is MessageContent.Parts && c.parts.any { it is ContentPart.ImageUrl }
        }

        /**
         * Pick model for this LLM call:
         * - escalate → [MODEL_ESCALATED]
         * - any image in the thread → [MODEL_VISION]
         * - else → [MODEL_PLANNING] (gpt-5.6)
         */
        fun selectModel(messages: List<ChatMessage>, escalate: Boolean = false): String {
            if (escalate) return MODEL_ESCALATED
            if (messages.any { containsImage(it) }) return MODEL_VISION
            return MODEL_PLANNING
        }

        /** gpt-5* only — GPT-4 rejects / ignores this field path. */
        fun maxCompletionTokensFor(model: String): Int? =
            if (model.startsWith("gpt-5")) GPT5_MAX_COMPLETION_TOKENS else null

        /** gpt-4* only — GPT-5 requires [maxCompletionTokensFor] instead. */
        fun maxTokensFor(model: String): Int? =
            if (model.startsWith("gpt-4")) GPT4_MAX_TOKENS else null

        /**
         * Detect missing / unknown model so we can retry with [MODEL_FALLBACK].
         * Matches model_not_found, HTTP 404, and 400s that mention the model id.
         */
        fun isModelUnavailableFailure(error: Throwable): Boolean {
            val detailed = formatOpenAiFailure(error).lowercase()
            if (detailed.contains("model_not_found")) return true
            if (detailed.contains("http 404")) return true
            if (detailed.contains("does not exist") && detailed.contains("model")) return true
            if (detailed.contains("http 400") &&
                (
                    detailed.contains("model") &&
                        (
                            detailed.contains("not found") ||
                                detailed.contains("does not exist") ||
                                detailed.contains("invalid model") ||
                                detailed.contains("unknown model") ||
                                detailed.contains("you do not have access")
                            )
                    )
            ) {
                return true
            }
            return false
        }

        fun createJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            isLenient = true
        }

        fun createOkHttp(apiKey: String): OkHttpClient {
            val logging = HttpLoggingInterceptor { msg ->
                // Avoid dumping full base64 screenshots into logcat.
                val trimmed = if (msg.length > 400) msg.take(400) + "…" else msg
                RingBufferLogger.log("http", trimmed)
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    chain.proceed(req)
                }
                .addInterceptor(logging)
                .build()
        }

        fun createApi(client: OkHttpClient, json: Json): OpenAiApi {
            val contentType = "application/json".toMediaType()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(OpenAiApi::class.java)
        }

        /** Surface HTTP status + short body for the ring buffer (never logs the API key). */
        fun formatOpenAiFailure(error: Throwable): String {
            var http: HttpException? = error as? HttpException
            var cause = error.cause
            while (http == null && cause != null) {
                http = cause as? HttpException
                cause = cause.cause
            }
            if (http != null) {
                val snippet = runCatching {
                    http.response()?.errorBody()?.string()?.take(400)?.replace("\n", " ")
                }.getOrNull().orEmpty()
                val base = "OpenAI HTTP ${http.code()}"
                return if (snippet.isNotBlank()) "$base: $snippet" else "$base: ${http.message()}"
            }
            return error.message ?: error::class.java.simpleName
        }
    }
}
