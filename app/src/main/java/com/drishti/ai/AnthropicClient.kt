package com.drishti.ai

import com.drishti.BuildConfig
import com.drishti.debug.RingBufferLogger
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface AnthropicApi {
    @Headers("Content-Type: application/json")
    @POST("v1/messages")
    suspend fun createMessage(@Body body: AnthropicMessagesRequest): AnthropicMessagesResponse
}

/**
 * Anthropic Messages API client (tool use + vision).
 * Key comes from [BuildConfig.ANTHROPIC_API_KEY] (local.properties → buildConfigField).
 *
 * Model: [MODEL] = claude-sonnet-4-5 (current Sonnet; Messages API + tools).
 * Fallback alias if needed: claude-sonnet-4-20250514
 */
@Singleton
class AnthropicClient @Inject constructor(
    private val api: AnthropicApi,
) {
    fun hasApiKey(): Boolean = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    fun missingKeyMessage(): String =
        "ANTHROPIC_API_KEY is empty. Add ANTHROPIC_API_KEY=sk-ant-... to local.properties and rebuild the app."

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        model: String = MODEL,
        maxTokens: Int = 4096,
    ): Result<AnthropicMessagesResponse> {
        if (!hasApiKey()) {
            return Result.failure(IllegalStateException(missingKeyMessage()))
        }
        return runCatching {
            val converted = AnthropicMessageAdapter.toAnthropicRequest(messages)
            if (converted.messages.isEmpty()) {
                error("No user/assistant messages to send to Anthropic")
            }
            RingBufferLogger.log(
                "anthropic",
                "chat model=$model messages=${converted.messages.size} tools=${tools.size}",
            )
            val response = api.createMessage(
                AnthropicMessagesRequest(
                    model = model,
                    maxTokens = maxTokens,
                    system = converted.system,
                    messages = converted.messages,
                    tools = AnthropicToolAdapter.fromOpenAiTools(tools),
                    toolChoice = AnthropicToolChoice(type = "auto"),
                ),
            )
            if (response.error != null) {
                error(response.error.message ?: "Anthropic error")
            }
            if (response.content.isEmpty()) {
                error("Anthropic returned empty content")
            }
            response
        }.onFailure { e ->
            RingBufferLogger.log("anthropic", "error=${e.message}")
        }
    }

    companion object {
        /** Current solid Sonnet model for Messages API + tool use. */
        const val MODEL = "claude-sonnet-4-5"

        const val BASE_URL = "https://api.anthropic.com/"
        const val API_VERSION = "2023-06-01"

        fun createOkHttp(apiKey: String): OkHttpClient {
            val logging = HttpLoggingInterceptor { msg ->
                val trimmed = if (msg.length > 400) msg.take(400) + "…" else msg
                RingBufferLogger.log("http", trimmed)
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", API_VERSION)
                        .build()
                    chain.proceed(req)
                }
                .addInterceptor(logging)
                .build()
        }

        fun createApi(client: OkHttpClient, json: Json): AnthropicApi {
            val contentType = "application/json".toMediaType()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(AnthropicApi::class.java)
        }
    }
}
