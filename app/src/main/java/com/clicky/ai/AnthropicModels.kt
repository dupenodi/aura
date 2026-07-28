package com.clicky.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicToolSpec>? = null,
    @SerialName("tool_choice")
    val toolChoice: AnthropicToolChoice? = AnthropicToolChoice(type = "auto"),
)

@Serializable
data class AnthropicToolChoice(
    val type: String = "auto",
)

@Serializable
data class AnthropicToolSpec(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonObject,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>,
)

@Serializable
sealed class AnthropicContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AnthropicContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(val source: AnthropicImageSource) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement = JsonObject(emptyMap()),
    ) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: String,
        @SerialName("is_error")
        val isError: Boolean? = null,
    ) : AnthropicContentBlock()
}

@Serializable
data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type")
    val mediaType: String = "image/jpeg",
    val data: String,
)

@Serializable
data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    val error: AnthropicErrorBody? = null,
)

@Serializable
data class AnthropicErrorBody(
    val type: String? = null,
    val message: String? = null,
)
