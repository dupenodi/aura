package com.clicky.ai

import com.clicky.debug.RingBufferLogger
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class LlmProvider {
    OpenAI,
    Anthropic,
}

data class LlmChatResult(
    val provider: LlmProvider,
    val model: String,
    val message: ChatMessage,
    val finishReason: String?,
)

/**
 * Provider router: OpenAI first when keyed; fall back to Anthropic on missing key
 * or network / 5xx / 429 / auth failures.
 */
@Singleton
class LlmRouter @Inject constructor(
    private val openAi: OpenAiClient,
    private val anthropic: AnthropicClient,
) {
    fun hasOpenAiKey(): Boolean = openAi.hasApiKey()
    fun hasAnthropicKey(): Boolean = anthropic.hasApiKey()
    fun hasAnyKey(): Boolean = hasOpenAiKey() || hasAnthropicKey()

    fun missingKeysMessage(): String =
        "No LLM API key configured. Add OPENAI_API_KEY and/or ANTHROPIC_API_KEY to local.properties and rebuild the app."

    /**
     * @param preferAnthropic when true, skip OpenAI for the rest of a run after a fallback.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        openAiModel: String,
        preferAnthropic: Boolean = false,
    ): Result<LlmChatResult> {
        val tryOpenAi = hasOpenAiKey() && !preferAnthropic
        if (tryOpenAi) {
            val openAiResult = openAi.chat(
                model = openAiModel,
                messages = messages,
                tools = tools,
                parallelToolCalls = false,
            )
            openAiResult.onSuccess { response ->
                val choice = response.choices.first()
                RingBufferLogger.log("llm", "provider=OpenAI model=${response.model ?: openAiModel}")
                return Result.success(
                    LlmChatResult(
                        provider = LlmProvider.OpenAI,
                        model = response.model ?: openAiModel,
                        message = choice.message,
                        finishReason = choice.finishReason,
                    ),
                )
            }
            val err = openAiResult.exceptionOrNull()
            if (err != null && shouldFallback(err)) {
                RingBufferLogger.log(
                    "llm",
                    "OpenAI failed (${err.message}); falling back to Anthropic",
                )
            } else if (err != null) {
                return Result.failure(err)
            }
        } else if (!hasOpenAiKey()) {
            RingBufferLogger.log("llm", "OPENAI_API_KEY blank; using Anthropic if available")
        }

        if (!hasAnthropicKey()) {
            val msg = when {
                !hasAnyKey() -> missingKeysMessage()
                else -> "OpenAI failed and ANTHROPIC_API_KEY is empty. Add ANTHROPIC_API_KEY=sk-ant-... to local.properties and rebuild."
            }
            return Result.failure(IllegalStateException(msg))
        }

        return anthropic.chat(messages = messages, tools = tools).map { response ->
            val model = response.model ?: AnthropicClient.MODEL
            RingBufferLogger.log("llm", "provider=Anthropic model=$model")
            LlmChatResult(
                provider = LlmProvider.Anthropic,
                model = model,
                message = AnthropicMessageAdapter.fromAnthropicResponse(response),
                finishReason = response.stopReason,
            )
        }
    }

    fun shouldFallback(error: Throwable): Boolean {
        when (error) {
            is IllegalStateException -> {
                // Missing key or empty choices / API error body — try Anthropic.
                return true
            }
            is SocketTimeoutException, is IOException -> return true
            is HttpException -> {
                val code = error.code()
                // Include 400 so a bad OpenAI payload / model id can still fall back to Anthropic.
                return code == 400 || code == 401 || code == 403 || code == 429 || code >= 500
            }
        }
        // Retrofit sometimes wraps; walk causes.
        var cause = error.cause
        while (cause != null) {
            if (cause is HttpException) {
                val code = cause.code()
                return code == 400 || code == 401 || code == 403 || code == 429 || code >= 500
            }
            if (cause is IOException) return true
            cause = cause.cause
        }
        // Unknown failures: still fall back so Clicky stays usable during OpenAI outages.
        return true
    }
}
