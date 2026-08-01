package com.drishti.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Shared chat types used by all LLM backends. */
object ChatModels {
    data class Message(
        val role: String,
        val content: Any, // String or List<ContentBlock>
    )

    sealed class ContentBlock {
        data class Text(val text: String) : ContentBlock()
        data class Image(val base64: String, val mediaType: String = "image/png") : ContentBlock()
        data class ToolUse(
            val id: String,
            val name: String,
            val input: JsonObject,
        ) : ContentBlock()
        data class ToolResult(
            val toolUseId: String,
            val content: String,
            val isError: Boolean = false,
        ) : ContentBlock()
    }

    @Serializable
    data class Response(
        val id: String? = null,
        val content: List<ResponseBlock> = emptyList(),
        @SerialName("stop_reason") val stopReason: String? = null,
        val provider: String? = null,
    )

    @Serializable
    data class ResponseBlock(
        val type: String,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
    )
}

interface ChatBackend {
    val providerId: String
    fun createMessage(
        system: String,
        messages: List<ChatModels.Message>,
        tools: List<JsonObject>,
        maxTokens: Int = 1024,
    ): ChatModels.Response
}
