package com.drishti.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * One request, one forced tool call back.
 *
 * Aura asks for exactly one structured step per turn, so the client surface is narrow: a
 * system prompt that stays byte-identical for the whole session (so providers can cache it),
 * one user turn of text and optionally a screenshot, and one tool the model must call.
 */
data class ToolCallRequest(
    val system: String,
    val user: String,
    val image: ImagePart? = null,
    val toolName: String,
    val toolDescription: String,
    val toolSchema: JsonObject,
    val model: String,
    /** Tried in order by the provider if [model] is down or overloaded (OpenRouter). */
    val fallbackModels: List<String> = emptyList(),
    val maxTokens: Int = 400,
    val temperature: Double = 0.2,
)

class ImagePart(val jpeg: ByteArray, val mediaType: String = "image/jpeg")

data class ToolCallResult(
    /** The tool arguments; null when the model answered in prose instead. */
    val args: JsonObject?,
    val text: String?,
    /** Model that actually answered (may be a fallback). */
    val model: String,
    val usage: Usage,
    val latencyMs: Long,
)

data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Input tokens served from the provider's prompt cache. */
    val cachedTokens: Int = 0,
    val costUsd: Double? = null,
)

interface LlmClient {
    val id: String

    suspend fun call(request: ToolCallRequest): ToolCallResult

    /** Opens the connection ahead of the first real call so it doesn't pay for TLS. */
    suspend fun warmUp() {}
}

/** A failed call, with what we know about why — the caller turns it into words. */
class LlmException(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { Auth, Credit, RateLimit, Timeout, Network, Server, BadResponse, NotConfigured }

    /** Worth trying again (possibly on another model) rather than giving up. */
    val retryable: Boolean get() = kind in setOf(Kind.RateLimit, Kind.Timeout, Kind.Network, Kind.Server, Kind.BadResponse)

    companion object {
        fun fromHttp(code: Int, body: String): LlmException {
            val kind = when (code) {
                401, 403 -> Kind.Auth
                402 -> Kind.Credit
                408 -> Kind.Timeout
                429 -> Kind.RateLimit
                in 500..599 -> Kind.Server
                else -> Kind.BadResponse
            }
            return LlmException(kind, "HTTP $code: ${body.take(300)}", code)
        }
    }
}
