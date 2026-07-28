package com.drishti.ai

import com.drishti.debug.RingBufferLogger
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

/**
 * Maps shared [ToolSpec] (OpenAI-style) → Anthropic Messages API tool schema.
 */
object AnthropicToolAdapter {
    fun fromOpenAiTools(tools: List<ToolSpec>): List<AnthropicToolSpec> =
        tools.map { spec ->
            AnthropicToolSpec(
                name = spec.function.name,
                description = spec.function.description,
                inputSchema = sanitizeInputSchema(spec.function.parameters),
            )
        }

    /**
     * Anthropic input_schema is JSON Schema without OpenAI `strict` extras.
     * Nullable union types become optional single-type fields.
     */
    private fun sanitizeInputSchema(parameters: JsonObject): JsonObject {
        val properties = parameters["properties"]?.jsonObject
        val required = parameters["required"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

        val cleanedProps = buildJsonObject {
            properties?.forEach { (name, raw) ->
                val obj = raw as? JsonObject ?: return@forEach
                put(name, sanitizeProperty(obj))
            }
        }

        val nullableNames = properties?.mapNotNull { (name, raw) ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            if (isNullableType(obj["type"])) name else null
        }.orEmpty().toSet()

        val cleanedRequired = required.filterNot { it in nullableNames }

        return buildJsonObject {
            put("type", "object")
            put("properties", cleanedProps)
            put(
                "required",
                buildJsonArray {
                    cleanedRequired.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }

    private fun sanitizeProperty(obj: JsonObject): JsonObject = buildJsonObject {
        obj.forEach { (key, value) ->
            when (key) {
                "type" -> put(key, collapseNullableType(value))
                else -> put(key, value)
            }
        }
    }

    private fun isNullableType(type: JsonElement?): Boolean {
        val arr = type as? JsonArray ?: return false
        return arr.any { it is JsonPrimitive && it.contentOrNull == "null" }
    }

    private fun collapseNullableType(type: JsonElement): JsonElement {
        val arr = type as? JsonArray ?: return type
        val nonNull = arr.filterNot { it is JsonPrimitive && it.contentOrNull == "null" }
        return when (nonNull.size) {
            0 -> JsonPrimitive("string")
            1 -> nonNull.first()
            else -> JsonArray(nonNull)
        }
    }
}

/**
 * Converts OpenAI-shaped [ChatMessage] history ↔ Anthropic Messages API payloads.
 * Images are wired for vision (JPEG base64) when present on user parts.
 */
object AnthropicMessageAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    data class ConvertedRequest(
        val system: String?,
        val messages: List<AnthropicMessage>,
    )

    fun toAnthropicRequest(messages: List<ChatMessage>): ConvertedRequest {
        val systemParts = mutableListOf<String>()
        val out = mutableListOf<AnthropicMessage>()

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    textOf(msg)?.takeIf { it.isNotBlank() }?.let { systemParts += it }
                }
                "user" -> {
                    val blocks = userBlocks(msg)
                    if (blocks.isNotEmpty()) {
                        mergeOrAdd(out, role = "user", blocks)
                    }
                }
                "assistant" -> {
                    val blocks = assistantBlocks(msg)
                    if (blocks.isNotEmpty()) {
                        mergeOrAdd(out, role = "assistant", blocks)
                    }
                }
                "tool" -> {
                    val toolUseId = msg.toolCallId ?: continue
                    val content = textOf(msg).orEmpty()
                    mergeOrAdd(
                        out,
                        role = "user",
                        listOf(
                            AnthropicContentBlock.ToolResult(
                                toolUseId = toolUseId,
                                content = content,
                            ),
                        ),
                    )
                }
            }
        }

        // Anthropic requires alternating user/assistant; drop leading assistant if needed.
        while (out.isNotEmpty() && out.first().role != "user") {
            out.removeAt(0)
        }
        // Must not end with empty list; caller should ensure at least one user message.
        return ConvertedRequest(
            system = systemParts.joinToString("\n\n").ifBlank { null },
            messages = out,
        )
    }

    fun fromAnthropicResponse(response: AnthropicMessagesResponse): ChatMessage {
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        for (block in response.content) {
            when (block) {
                is AnthropicContentBlock.Text -> {
                    if (block.text.isNotBlank()) textParts += block.text
                }
                is AnthropicContentBlock.ToolUse -> {
                    toolCalls += ToolCall(
                        id = block.id,
                        type = "function",
                        function = ToolFunctionCall(
                            name = block.name,
                            arguments = json.encodeToString(JsonElement.serializer(), block.input),
                        ),
                    )
                }
                else -> Unit
            }
        }
        return ChatMessage.assistant(
            text = textParts.joinToString("\n").ifBlank { null },
            toolCalls = toolCalls.ifEmpty { null },
        )
    }

    private fun mergeOrAdd(
        out: MutableList<AnthropicMessage>,
        role: String,
        blocks: List<AnthropicContentBlock>,
    ) {
        val last = out.lastOrNull()
        if (last != null && last.role == role) {
            out[out.lastIndex] = AnthropicMessage(role, last.content + blocks)
        } else {
            out += AnthropicMessage(role, blocks)
        }
    }

    private fun userBlocks(msg: ChatMessage): List<AnthropicContentBlock> {
        return when (val content = msg.content) {
            is MessageContent.Text -> listOf(AnthropicContentBlock.Text(content.text))
            is MessageContent.Parts -> content.parts.mapNotNull { part ->
                when (part) {
                    is ContentPart.Text -> AnthropicContentBlock.Text(part.text)
                    is ContentPart.ImageUrl -> {
                        val img = imageBlockFromDataUrl(part.imageUrl.url)
                        if (img != null) {
                            RingBufferLogger.log(
                                "vlm",
                                "Anthropic image block mediaType=${img.source.mediaType} " +
                                    "dataLen=${img.source.data.length}",
                            )
                        } else {
                            RingBufferLogger.log("vlm", "Anthropic image parse failed")
                        }
                        img
                    }
                }
            }
            null -> emptyList()
        }
    }

    private fun assistantBlocks(msg: ChatMessage): List<AnthropicContentBlock> {
        val blocks = mutableListOf<AnthropicContentBlock>()
        textOf(msg)?.takeIf { it.isNotBlank() }?.let {
            blocks += AnthropicContentBlock.Text(it)
        }
        for (call in msg.toolCalls.orEmpty()) {
            val input: JsonElement = runCatching {
                json.parseToJsonElement(call.function.arguments.ifBlank { "{}" })
            }.getOrDefault(JsonObject(emptyMap()))
            blocks += AnthropicContentBlock.ToolUse(
                id = call.id,
                name = call.function.name,
                input = input,
            )
        }
        return blocks
    }

    private fun imageBlockFromDataUrl(url: String): AnthropicContentBlock.Image? {
        // data:image/jpeg;base64,<payload>
        val marker = ";base64,"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val header = url.substring(0, idx)
        val data = url.substring(idx + marker.length)
        if (data.isBlank()) return null
        val mediaType = header.removePrefix("data:").ifBlank { "image/jpeg" }
        return AnthropicContentBlock.Image(
            AnthropicImageSource(
                type = "base64",
                mediaType = mediaType,
                data = data,
            ),
        )
    }

    private fun textOf(msg: ChatMessage): String? = when (val c = msg.content) {
        is MessageContent.Text -> c.text
        is MessageContent.Parts -> c.parts.filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
        null -> null
    }
}
