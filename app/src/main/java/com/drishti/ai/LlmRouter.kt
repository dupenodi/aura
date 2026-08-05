package com.drishti.ai

import android.util.Log
import com.drishti.BuildConfig
import kotlinx.serialization.json.JsonObject

/**
 * Routes chat requests across providers.
 *
 * LLM_PROVIDER values:
 * - auto (default): try local → openrouter → gemini → anthropic → openai (skip unconfigured)
 * - local | openrouter | gemini | anthropic | openai: force that provider
 */
class LlmRouter(
    private val preferred: String = BuildConfig.LLM_PROVIDER,
) : ChatBackend {
    override val providerId: String = "router"

    private val backends: List<ChatBackend> by lazy { buildBackends() }

    override fun createMessage(
        system: String,
        messages: List<ChatModels.Message>,
        tools: List<JsonObject>,
        maxTokens: Int,
    ): ChatModels.Response {
        val chain = selectChain()
        require(chain.isNotEmpty()) {
            "No LLM providers configured. Set LOCAL_LLM_BASE_URL and/or API keys in local.properties."
        }

        var lastError: Throwable? = null
        for (backend in chain) {
            try {
                Log.i(TAG, "Trying provider=${backend.providerId}")
                return backend.createMessage(system, messages, tools, maxTokens)
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${backend.providerId} failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("All LLM providers failed")
    }

    fun availableProviders(): List<String> = backends.map { it.providerId }

    fun selectedProviderIds(): List<String> = selectChain().map { it.providerId }

    private fun selectChain(): List<ChatBackend> {
        val want = preferred.trim().lowercase()
        return when (want) {
            "auto", "" -> backends
            else -> {
                val forced = backends.filter { it.providerId == want }
                if (forced.isNotEmpty()) forced else backends
            }
        }
    }

    private fun buildBackends(): List<ChatBackend> {
        val list = mutableListOf<ChatBackend>()

        val localBase = BuildConfig.LOCAL_LLM_BASE_URL.trim()
        if (localBase.isNotEmpty()) {
            list.add(
                OpenAiCompatibleClient(
                    providerId = "local",
                    baseUrl = localBase,
                    model = BuildConfig.LOCAL_LLM_MODEL.ifBlank { "llama3.2" },
                    apiKeyProvider = { ApiKeyStore.resolve("local").ifBlank { "ollama" } },
                    allowEmptyKey = true,
                ),
            )
        }

        if (ApiKeyStore.resolve("openrouter").isNotBlank()) {
            list.add(
                OpenAiCompatibleClient(
                    providerId = "openrouter",
                    baseUrl = "https://openrouter.ai/api/v1",
                    model = BuildConfig.OPENROUTER_MODEL,
                    apiKeyProvider = { ApiKeyStore.resolve("openrouter") },
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/drishti-poc",
                        "X-Title" to "Aura",
                    ),
                ),
            )
        }

        if (ApiKeyStore.resolve("gemini").isNotBlank()) {
            list.add(
                OpenAiCompatibleClient(
                    providerId = "gemini",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                    model = BuildConfig.GEMINI_MODEL,
                    apiKeyProvider = { ApiKeyStore.resolve("gemini") },
                ),
            )
        }

        if (ApiKeyStore.resolve("anthropic").isNotBlank()) {
            list.add(AnthropicClient())
        }

        if (ApiKeyStore.resolve("openai").isNotBlank()) {
            list.add(
                OpenAiCompatibleClient(
                    providerId = "openai",
                    baseUrl = "https://api.openai.com/v1",
                    model = BuildConfig.OPENAI_MODEL,
                    apiKeyProvider = { ApiKeyStore.resolve("openai") },
                ),
            )
        }

        return list
    }

    companion object {
        private const val TAG = "LlmRouter"
    }
}
