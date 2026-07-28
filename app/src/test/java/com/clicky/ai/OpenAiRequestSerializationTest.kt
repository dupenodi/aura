package com.clicky.ai

import com.clicky.agent.ToolDefinitions
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestSerializationTest {
    private val json = OpenAiClient.createJson()

    @Test
    fun serializeChatRequestLooksValid() {
        val req = ChatCompletionRequest(
            model = OpenAiClient.MODEL_PLANNING,
            messages = listOf(
                ChatMessage.system("You are Clicky."),
                ChatMessage.user("Open Instagram"),
                ChatMessage.assistant(
                    text = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            function = ToolFunctionCall(
                                name = "open_app",
                                arguments = """{"query":"Instagram"}""",
                            ),
                        ),
                    ),
                ),
                ChatMessage.tool("call_1", "opened com.instagram.android"),
            ),
            tools = ToolDefinitions.all,
            parallelToolCalls = false,
            toolChoice = "auto",
            maxTokens = OpenAiClient.maxTokensFor(OpenAiClient.MODEL_PLANNING),
            maxCompletionTokens = OpenAiClient.maxCompletionTokensFor(OpenAiClient.MODEL_PLANNING),
        )
        val encoded = json.encodeToString(req)
        assertEquals("gpt-5.6", OpenAiClient.MODEL_PLANNING)
        assertEquals("gpt-5.6", OpenAiClient.MODEL_VISION)
        assertEquals("gpt-5.6", OpenAiClient.MODEL_ESCALATED)
        assertEquals("gpt-4.1", OpenAiClient.MODEL_FALLBACK)
        assertEquals("gpt-5", OpenAiClient.MODEL_GPT5)
        assertTrue(encoded.contains("\"model\":\"gpt-5.6\""))
        assertTrue(encoded.contains("\"max_completion_tokens\":8192"))
        assertFalse(encoded.contains("\"max_tokens\""))
        assertTrue(encoded.contains("\"tools\""))
        assertTrue(encoded.contains("observe_screen"))
        assertTrue(encoded.contains("node_id"))
        assertTrue(encoded.contains("\"strict\":true"))
        assertTrue(!encoded.contains("\"content\":\"\""))
    }

    @Test
    fun tokenCapFieldIsModelAware() {
        assertEquals(8192, OpenAiClient.maxCompletionTokensFor("gpt-5"))
        assertEquals(8192, OpenAiClient.maxCompletionTokensFor("gpt-5.6"))
        assertEquals(8192, OpenAiClient.maxCompletionTokensFor("gpt-5-mini"))
        assertNull(OpenAiClient.maxCompletionTokensFor("gpt-4.1"))
        assertEquals(8192, OpenAiClient.maxTokensFor("gpt-4.1"))
        assertEquals(8192, OpenAiClient.maxTokensFor("gpt-4.1-mini"))
        assertNull(OpenAiClient.maxTokensFor("gpt-5"))
        assertNull(OpenAiClient.maxTokensFor("gpt-5.6"))

        val gpt5Req = ChatCompletionRequest(
            model = OpenAiClient.MODEL_PLANNING,
            messages = listOf(ChatMessage.user("hi")),
            maxTokens = OpenAiClient.maxTokensFor(OpenAiClient.MODEL_PLANNING),
            maxCompletionTokens = OpenAiClient.maxCompletionTokensFor(OpenAiClient.MODEL_PLANNING),
        )
        val gpt5Encoded = json.encodeToString(gpt5Req)
        assertTrue(gpt5Encoded.contains("\"max_completion_tokens\":8192"))
        assertFalse(gpt5Encoded.contains("\"max_tokens\""))

        val gpt4Req = ChatCompletionRequest(
            model = OpenAiClient.MODEL_FALLBACK,
            messages = listOf(ChatMessage.user("hi")),
            maxTokens = OpenAiClient.maxTokensFor(OpenAiClient.MODEL_FALLBACK),
            maxCompletionTokens = OpenAiClient.maxCompletionTokensFor(OpenAiClient.MODEL_FALLBACK),
        )
        val gpt4Encoded = json.encodeToString(gpt4Req)
        assertTrue(gpt4Encoded.contains("\"max_tokens\":8192"))
        assertFalse(gpt4Encoded.contains("\"max_completion_tokens\""))
    }

    @Test
    fun isModelUnavailableFailureDetectsMissingModel() {
        assertTrue(
            OpenAiClient.isModelUnavailableFailure(
                IllegalStateException(
                    """OpenAI HTTP 404: {"error":{"message":"The model `gpt-5` has been deprecated","code":"model_not_found"}}""",
                ),
            ),
        )
        assertTrue(
            OpenAiClient.isModelUnavailableFailure(
                IllegalStateException("OpenAI HTTP 404: not found"),
            ),
        )
        assertFalse(
            OpenAiClient.isModelUnavailableFailure(
                IllegalStateException(
                    "OpenAI HTTP 400: Unsupported parameter: 'max_tokens'",
                ),
            ),
        )
    }

    @Test
    fun selectModelUsesPlanningByDefaultAndVisionWhenImagesPresent() {
        val textOnly = listOf(ChatMessage.user("hi"))
        assertEquals(OpenAiClient.MODEL_PLANNING, OpenAiClient.selectModel(textOnly))
        val withImage = listOf(
            ChatMessage.userWithImage("screen", "abc123"),
        )
        assertEquals(OpenAiClient.MODEL_VISION, OpenAiClient.selectModel(withImage))
        assertEquals(OpenAiClient.MODEL_ESCALATED, OpenAiClient.selectModel(textOnly, escalate = true))
    }
}
