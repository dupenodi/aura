package com.drishti.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/** Anthropic Messages API, one forced tool call, system prompt cached across steps. */
class AnthropicClient(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val timeoutMs: Long = 20_000,
    private val http: OkHttpClient = Http.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {
    override val id: String = "anthropic"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun call(request: ToolCallRequest): ToolCallResult {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.NotConfigured, "anthropic key missing")
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(buildBody(request).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val started = clock()
        return this.http.await(http, timeoutMs) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val kind = if (resp.code == 529) LlmException.Kind.Server else null
                throw kind?.let { LlmException(it, "HTTP ${resp.code}: ${raw.take(300)}", resp.code) }
                    ?: LlmException.fromHttp(resp.code, raw)
            }
            parse(raw, request.model, clock() - started)
        }
    }

    internal fun buildBody(r: ToolCallRequest): JsonObject = buildJsonObject {
        put("model", r.model)
        put("max_tokens", r.maxTokens)
        put("temperature", r.temperature)
        putJsonArray("system") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", r.system)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                },
            )
        }
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        r.image?.let { img ->
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", img.mediaType)
                                        put("data", Base64.getEncoder().encodeToString(img.jpeg))
                                    }
                                },
                            )
                        }
                        add(buildJsonObject { put("type", "text"); put("text", r.user) })
                    }
                },
            )
        }
        putJsonArray("tools") {
            add(
                buildJsonObject {
                    put("name", r.toolName)
                    put("description", r.toolDescription)
                    put("input_schema", r.toolSchema)
                },
            )
        }
        putJsonObject("tool_choice") {
            put("type", "tool")
            put("name", r.toolName)
        }
    }

    internal fun parse(raw: String, requestedModel: String, latencyMs: Long): ToolCallResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LlmException(LlmException.Kind.BadResponse, "not JSON: ${raw.take(200)}") }
        val content = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val tool = content.firstOrNull { (it as? JsonObject)?.get("type")?.let { t -> (t as JsonPrimitive).content } == "tool_use" }
        val text = content.mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("").takeIf { it.isNotBlank() }
        val usage = (root["usage"] as? JsonObject)?.let { u ->
            fun i(k: String) = (u[k] as? JsonPrimitive)?.intOrNull ?: 0
            Usage(
                inputTokens = i("input_tokens") + i("cache_read_input_tokens") + i("cache_creation_input_tokens"),
                outputTokens = i("output_tokens"),
                cachedTokens = i("cache_read_input_tokens"),
            )
        } ?: Usage()
        return ToolCallResult(
            args = (tool as? JsonObject)?.get("input") as? JsonObject,
            text = text,
            model = (root["model"] as? JsonPrimitive)?.contentOrNull ?: requestedModel,
            usage = usage,
            latencyMs = latencyMs,
        )
    }
}
