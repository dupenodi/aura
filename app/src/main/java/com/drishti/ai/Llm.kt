package com.drishti.ai

import com.drishti.BuildConfig
import com.drishti.core.agent.LlmPlanner
import com.drishti.core.agent.ModelConfig
import com.drishti.core.agent.StepPlanner
import com.drishti.core.llm.AnthropicClient
import com.drishti.core.llm.LlmClient
import com.drishti.core.llm.LlmException
import com.drishti.core.llm.OpenAiCompatClient

/**
 * Picks the model provider and models from what is configured.
 *
 * `LLM_PROVIDER=auto` uses the first provider with a key, in the order below. OpenRouter
 * comes first because one key reaches every model and it fails over between them on its
 * own side, with no extra round trip from the phone.
 */
object Llm {

    data class Setup(val client: LlmClient, val models: ModelConfig)

    fun planner(): StepPlanner {
        val setup = setup() ?: return NotConfigured
        return LlmPlanner(setup.client, setup.models)
    }

    fun setup(): Setup? {
        val wanted = BuildConfig.LLM_PROVIDER.trim().lowercase()
        val order = listOf("openrouter", "anthropic", "openai", "gemini", "local")
        val chain = if (wanted == "auto" || wanted.isBlank()) order else listOf(wanted) + (order - wanted)
        return chain.firstNotNullOfOrNull { build(it) }
    }

    private fun build(provider: String): Setup? = when (provider) {
        "openrouter" -> key("openrouter")?.let { k ->
            Setup(
                OpenAiCompatClient(
                    id = "openrouter",
                    baseUrl = "https://openrouter.ai/api/v1",
                    apiKey = { ApiKeyStore.resolve("openrouter").ifBlank { k } },
                    extraHeaders = mapOf("HTTP-Referer" to "https://github.com/dupenodi/aura", "X-Title" to "Aura"),
                ),
                ModelConfig(
                    fast = BuildConfig.OPENROUTER_MODEL,
                    smart = BuildConfig.OPENROUTER_SMART_MODEL,
                    fallbacks = BuildConfig.OPENROUTER_FALLBACK_MODELS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                ),
            )
        }
        "anthropic" -> key("anthropic")?.let {
            Setup(
                AnthropicClient({ ApiKeyStore.resolve("anthropic") }),
                ModelConfig(fast = BuildConfig.ANTHROPIC_MODEL, smart = BuildConfig.ANTHROPIC_SMART_MODEL),
            )
        }
        "openai" -> key("openai")?.let {
            Setup(
                OpenAiCompatClient("openai", "https://api.openai.com/v1", { ApiKeyStore.resolve("openai") }),
                ModelConfig(fast = BuildConfig.OPENAI_MODEL, smart = BuildConfig.OPENAI_MODEL),
            )
        }
        "gemini" -> key("gemini")?.let {
            Setup(
                OpenAiCompatClient("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", { ApiKeyStore.resolve("gemini") }),
                ModelConfig(fast = BuildConfig.GEMINI_MODEL, smart = BuildConfig.GEMINI_MODEL),
            )
        }
        "local" -> BuildConfig.LOCAL_LLM_BASE_URL.takeIf { it.isNotBlank() }?.let { base ->
            Setup(
                OpenAiCompatClient("local", base, { ApiKeyStore.resolve("local") }, openRouter = false, timeoutMs = 60_000),
                ModelConfig(fast = BuildConfig.LOCAL_LLM_MODEL, smart = BuildConfig.LOCAL_LLM_MODEL),
            )
        }
        else -> null
    }

    private fun key(provider: String): String? = ApiKeyStore.resolve(provider).takeIf { it.isNotBlank() }

    /** No provider at all: every plan fails with a message the user can act on. */
    private object NotConfigured : StepPlanner {
        override suspend fun plan(input: com.drishti.core.agent.PlanInput) =
            throw LlmException(LlmException.Kind.NotConfigured, "no model provider configured")
    }
}
