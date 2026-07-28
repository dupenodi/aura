package com.clicky.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = false,
    @SerialName("tool_choice")
    val toolChoice: String? = "auto",
    /**
     * GPT-4 family output cap. Omitted for GPT-5 (use [maxCompletionTokens]).
     */
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    /**
     * GPT-5 rejects [maxTokens]; use this instead when capping output.
     * Omitted for GPT-4 and other models that use [maxTokens].
     */
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val error: OpenAiErrorBody? = null,
)

@Serializable
data class OpenAiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    @Serializable(with = MessageContentSerializer::class)
    val content: MessageContent? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(role = "system", content = MessageContent.Text(text))

        fun user(text: String) = ChatMessage(role = "user", content = MessageContent.Text(text))

        /**
         * @param detail OpenAI image detail. Default `low` for speed; use `high` only on the
         * first ambiguous screen of a turn when the tree alone is insufficient.
         */
        fun userWithImage(
            text: String,
            base64Jpeg: String,
            detail: String = "low",
        ) = ChatMessage(
            role = "user",
            content = MessageContent.Parts(
                listOf(
                    ContentPart.Text(text),
                    ContentPart.ImageUrl(
                        ImageUrl("data:image/jpeg;base64,$base64Jpeg", detail = detail),
                    ),
                ),
            ),
        )

        fun assistant(
            text: String? = null,
            toolCalls: List<ToolCall>? = null,
        ) = ChatMessage(
            role = "assistant",
            content = text?.let { MessageContent.Text(it) },
            toolCalls = toolCalls,
        )

        fun tool(toolCallId: String, result: String) = ChatMessage(
            role = "tool",
            toolCallId = toolCallId,
            content = MessageContent.Text(result),
        )
    }
}

@Serializable
sealed class MessageContent {
    @Serializable
    data class Text(val text: String) : MessageContent()

    @Serializable
    data class Parts(val parts: List<ContentPart>) : MessageContent()
}

@Serializable
sealed class ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart()

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        @SerialName("image_url")
        val imageUrl: com.clicky.ai.ImageUrl,
    ) : ContentPart()
}

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = "low",
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionCall,
)

@Serializable
data class ToolFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean = true,
)

object MessageContentSerializer : KSerializer<MessageContent?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("MessageContent")

    override fun serialize(encoder: Encoder, value: MessageContent?) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("MessageContent only supports JSON")
        when (value) {
            null -> jsonEncoder.encodeJsonElement(JsonNull)
            is MessageContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is MessageContent.Parts -> {
                val arr = buildJsonArray {
                    for (part in value.parts) {
                        add(
                            when (part) {
                                is ContentPart.Text -> buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                }
                                is ContentPart.ImageUrl -> buildJsonObject {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put("url", part.imageUrl.url)
                                            part.imageUrl.detail?.let { put("detail", it) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                jsonEncoder.encodeJsonElement(arr)
            }
        }
    }

    override fun deserialize(decoder: Decoder): MessageContent? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("MessageContent only supports JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> MessageContent.Text(element.contentOrNull.orEmpty())
            is JsonArray -> {
                val parts = element.mapNotNull { parsePart(it) }
                MessageContent.Parts(parts)
            }
            is JsonObject -> {
                // Some responses wrap unexpectedly; treat as text dump.
                MessageContent.Text(element.toString())
            }
            else -> MessageContent.Text(element.toString())
        }
    }

    private fun parsePart(element: JsonElement): ContentPart? {
        val obj = element as? JsonObject ?: return null
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> ContentPart.Text(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "image_url" -> {
                val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: return null
                ContentPart.ImageUrl(ImageUrl(url))
            }
            else -> null
        }
    }
}
