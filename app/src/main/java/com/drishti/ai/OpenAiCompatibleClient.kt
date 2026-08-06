package com.drishti.ai

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions–compatible client.
 * Works with OpenAI, OpenRouter, Ollama, LM Studio, vLLM, etc.
 */
class OpenAiCompatibleClient(
    override val providerId: String,
    private val baseUrl: String,
    private val model: String,
    private val apiKeyProvider: () -> String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val allowEmptyKey: Boolean = false,
) : ChatBackend {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun createMessage(
        system: String,
        messages: List<ChatModels.Message>,
        tools: List<JsonObject>,
        maxTokens: Int,
    ): ChatModels.Response {
        val key = apiKeyProvider().trim()
        if (!allowEmptyKey) {
            require(key.isNotEmpty()) { "$providerId API key is missing" }
        }

        val openAiMessages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                },
            )
            messages.forEach { msg ->
                msg.toOpenAiMessages().forEach { add(it) }
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", openAiMessages)
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool["name"] ?: JsonPrimitive(""))
                                            put("description", tool["description"] ?: JsonPrimitive(""))
                                            put(
                                                "parameters",
                                                tool["input_schema"] ?: buildJsonObject {
                                                    put("type", "object")
                                                    put("properties", buildJsonObject {})
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))

        if (key.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        extraHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        return http.cancellableCall(requestBuilder.build()) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("$providerId HTTP ${resp.code}: $raw")
            }
            parseResponse(raw)
        }
    }

    private fun parseResponse(raw: String): ChatModels.Response {
        val root = json.parseToJsonElement(raw).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("$providerId: empty choices")
        val message = choice["message"]?.jsonObject ?: error("$providerId: missing message")
        val blocks = mutableListOf<ChatModels.ResponseBlock>()

        val text = message["content"]?.let { contentToText(it) }
        if (!text.isNullOrBlank()) {
            blocks.add(ChatModels.ResponseBlock(type = "text", text = text))
        }

        val toolCalls = message["tool_calls"]?.jsonArray.orEmpty()
        toolCalls.forEach { callEl ->
            val call = callEl.jsonObject
            val id = call["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val fn = call["function"]?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val argsRaw = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
            val input = try {
                if (argsRaw.isBlank()) {
                    buildJsonObject {}
                } else {
                    json.parseToJsonElement(argsRaw).jsonObject
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tool args: $argsRaw", e)
                buildJsonObject { put("raw", argsRaw) }
            }
            blocks.add(
                ChatModels.ResponseBlock(
                    type = "tool_use",
                    id = id,
                    name = name,
                    input = input,
                ),
            )
        }

        return ChatModels.Response(
            id = root["id"]?.jsonPrimitive?.contentOrNull,
            content = blocks,
            stopReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            provider = providerId,
        )
    }

    private fun contentToText(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { el ->
            el.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun ChatModels.Message.toOpenAiMessages(): List<JsonObject> {
        return when (val c = content) {
            is String -> listOf(
                buildJsonObject {
                    put("role", role)
                    put("content", c)
                },
            )
            is List<*> -> {
                val toolResults = c.filterIsInstance<ChatModels.ContentBlock.ToolResult>()
                if (toolResults.isNotEmpty() && role == "user") {
                    return toolResults.map { tr ->
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tr.toolUseId)
                            put("content", tr.content)
                        }
                    }
                }

                val toolUses = c.filterIsInstance<ChatModels.ContentBlock.ToolUse>()
                if (toolUses.isNotEmpty() && role == "assistant") {
                    val textParts = c.filterIsInstance<ChatModels.ContentBlock.Text>()
                        .joinToString("\n") { it.text }
                    return listOf(
                        buildJsonObject {
                            put("role", "assistant")
                            if (textParts.isNotBlank()) put("content", textParts)
                            put(
                                "tool_calls",
                                buildJsonArray {
                                    toolUses.forEach { tu ->
                                        add(
                                            buildJsonObject {
                                                put("id", tu.id)
                                                put("type", "function")
                                                put(
                                                    "function",
                                                    buildJsonObject {
                                                        put("name", tu.name)
                                                        put("arguments", tu.input.toString())
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }

                // Multimodal / text user content
                val parts = buildJsonArray {
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
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put(
                                                "url",
                                                "data:${block.mediaType};base64,${block.base64}",
                                            )
                                        },
                                    )
                                },
                            )
                            else -> Unit
                        }
                    }
                }
                listOf(
                    buildJsonObject {
                        put("role", role)
                        put("content", parts)
                    },
                )
            }
            else -> listOf(
                buildJsonObject {
                    put("role", role)
                    put("content", c.toString())
                },
            )
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatible"
        private val JSON = "application/json".toMediaType()
    }
}
