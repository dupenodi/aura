package com.drishti.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Chat Completions with one forced tool call. Works with OpenRouter, OpenAI, Gemini's
 * OpenAI endpoint and local servers (Ollama, LM Studio).
 *
 * On OpenRouter it also asks for:
 * - `models` fallback — the provider retries the next model itself, with no extra round trip
 *   from the phone when the first is overloaded;
 * - `provider.sort = latency` — the fastest host for the model;
 * - prompt caching on the system prompt (honoured by Anthropic models, automatic elsewhere);
 * - reasoning switched off — a step is a quick judgement, and thinking tokens are pure delay.
 */
class OpenAiCompatClient(
    override val id: String,
    private val baseUrl: String,
    private val apiKey: () -> String,
    private val openRouter: Boolean = baseUrl.contains("openrouter.ai"),
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val timeoutMs: Long = 20_000,
    /** "off" | "low" | "medium" — only sent to OpenRouter. */
    private val reasoning: String = "off",
    private val http: OkHttpClient = Http.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Turned off for good once a model says it can't run without reasoning. */
    @Volatile
    private var sendReasoning = true

    override suspend fun warmUp() {
        // Any authenticated GET opens and keeps the TLS connection; the result is ignored.
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/models").head().apply { auth(this) }.build()
        runCatching { http.await(request, 5_000) { } }
    }

    override suspend fun call(request: ToolCallRequest): ToolCallResult = try {
        callOnce(request)
    } catch (e: LlmException) {
        val msg = e.message.orEmpty()
        when {
            // Some models can't switch reasoning off and reject the whole request; ask again
            // without the setting rather than failing the step.
            e.httpCode == 400 && sendReasoning && msg.contains("reason", ignoreCase = true) -> {
                sendReasoning = false
                call(request)
            }
            // Model ids drift (renamed, retired). An unknown id is refused before any
            // provider-side failover happens, so move down the list here.
            (e.httpCode == 400 || e.httpCode == 404) && isUnknownModel(msg) && request.fallbackModels.isNotEmpty() -> {
                val next = request.fallbackModels.first()
                call(request.copy(model = next, fallbackModels = request.fallbackModels.drop(1).filter { it != next }))
            }
            else -> throw e
        }
    }

    private fun isUnknownModel(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("model") && (m.contains("not a valid") || m.contains("not found") || m.contains("does not exist") ||
            m.contains("invalid model") || m.contains("no endpoints") || m.contains("unknown model"))
    }

    private suspend fun callOnce(request: ToolCallRequest): ToolCallResult {
        val body = buildBody(request)
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { auth(this) }
            .build()

        val started = clock()
        return this.http.await(http, timeoutMs) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, raw)
            parse(raw, request.model, clock() - started)
        }
    }

    private fun auth(builder: Request.Builder) {
        val key = apiKey().trim()
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
    }

    internal fun buildBody(r: ToolCallRequest): JsonObject = buildJsonObject {
        put("model", r.model)
        if (openRouter && r.fallbackModels.isNotEmpty()) {
            putJsonArray("models") {
                add(JsonPrimitive(r.model))
                r.fallbackModels.filter { it != r.model }.forEach { add(JsonPrimitive(it)) }
            }
        }
        put("max_tokens", r.maxTokens)
        put("temperature", r.temperature)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    if (openRouter) {
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", r.system)
                                    putJsonObject("cache_control") { put("type", "ephemeral") }
                                },
                            )
                        }
                    } else {
                        put("content", r.system)
                    }
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    if (r.image == null) {
                        put("content", r.user)
                    } else {
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", r.user) })
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        val b64 = Base64.getEncoder().encodeToString(r.image.jpeg)
                                        put("url", "data:${r.image.mediaType};base64,$b64")
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
        putJsonArray("tools") {
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", r.toolName)
                        put("description", r.toolDescription)
                        put("parameters", r.toolSchema)
                    }
                },
            )
        }
        putJsonObject("tool_choice") {
            put("type", "function")
            putJsonObject("function") { put("name", r.toolName) }
        }
        if (openRouter) {
            putJsonObject("provider") {
                put("sort", "latency")
                put("require_parameters", true)
            }
            if (sendReasoning) putJsonObject("reasoning") {
                if (reasoning == "off") put("enabled", false) else put("effort", reasoning)
                put("exclude", true)
            }
            putJsonObject("usage") { put("include", true) }
        }
    }

    internal fun parse(raw: String, requestedModel: String, latencyMs: Long): ToolCallResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LlmException(LlmException.Kind.BadResponse, "not JSON: ${raw.take(200)}") }
        root["error"]?.let { err ->
            val code = (err as? JsonObject)?.get("code")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 500
            throw LlmException.fromHttp(code, err.toString())
        }
        val message = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw LlmException(LlmException.Kind.BadResponse, "no choices: ${raw.take(200)}")

        val text = when (val c = message["content"]) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.let { t -> (t as? JsonPrimitive)?.contentOrNull } }
                .joinToString("")
            else -> null
        }?.takeIf { it.isNotBlank() }

        val argsRaw = (message["tool_calls"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?.get("function")?.jsonObject?.get("arguments")
        val args: JsonObject? = when (argsRaw) {
            is JsonObject -> argsRaw
            is JsonPrimitive -> argsRaw.contentOrNull?.let { parseArgs(it) }
            else -> null
        } ?: text?.let { extractJsonObject(it) }

        val usage = (root["usage"] as? JsonObject)?.let { u ->
            Usage(
                inputTokens = u.int("prompt_tokens"),
                outputTokens = u.int("completion_tokens"),
                cachedTokens = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0,
                costUsd = (u["cost"] as? JsonPrimitive)?.doubleOrNull,
            )
        } ?: Usage()

        val model = (root["model"] as? JsonPrimitive)?.contentOrNull ?: requestedModel
        return ToolCallResult(args, text, model, usage, latencyMs)
    }

    private fun parseArgs(s: String): JsonObject? =
        runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: extractJsonObject(s)

    /** Some models put the call in prose (`Here you go: {...}`). Take the outermost object. */
    private fun extractJsonObject(s: String): JsonObject? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(s.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.int(k: String): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: 0

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
