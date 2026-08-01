package com.drishti.ai

import com.drishti.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AnthropicClient(
    private val apiKeyProvider: () -> String = { ApiKeyStore.resolve("anthropic") },
    private val model: String = BuildConfig.ANTHROPIC_MODEL,
) : ChatBackend {
    override val providerId: String = "anthropic"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun createMessage(
        system: String,
        messages: List<ChatModels.Message>,
        tools: List<JsonObject>,
        maxTokens: Int,
    ): ChatModels.Response {
        val key = apiKeyProvider().trim()
        require(key.isNotEmpty()) { "ANTHROPIC_API_KEY is missing" }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("tools", buildJsonArray { tools.forEach { add(it) } })
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { msg ->
                        add(
                            buildJsonObject {
                                put("role", msg.role)
                                put("content", msg.contentToJson())
                            },
                        )
                    }
                },
            )
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Anthropic HTTP ${resp.code}: $raw")
            }
            val parsed = json.decodeFromString(ChatModels.Response.serializer(), raw)
            return parsed.copy(provider = providerId)
        }
    }

    private fun ChatModels.Message.contentToJson(): JsonElement = when (val c = content) {
        is String -> JsonPrimitive(c)
        is List<*> -> buildJsonArray {
            c.forEach { block ->
                when (block) {
                    is ChatModels.ContentBlock.Text -> add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                        },
                    )
                    is ChatModels.ContentBlock.Image -> add(
                        buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", block.mediaType)
                                    put("data", block.base64)
                                },
                            )
                        },
                    )
                    is ChatModels.ContentBlock.ToolUse -> add(
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", block.id)
                            put("name", block.name)
                            put("input", block.input)
                        },
                    )
                    is ChatModels.ContentBlock.ToolResult -> add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", block.toolUseId)
                            put("content", block.content)
                            if (block.isError) put("is_error", true)
                        },
                    )
                    else -> Unit
                }
            }
        }
        else -> JsonPrimitive(c.toString())
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
