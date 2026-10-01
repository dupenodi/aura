package com.drishti.core.llm

import com.drishti.core.agent.Language
import com.drishti.core.agent.Step
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicClientTest {
    @Test
    fun forcesTheToolCachesTheSystemPromptAndParsesUsage() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(
            MockResponse().setBody(
                """{"model":"claude-haiku-4-5","content":[{"type":"tool_use","id":"t","name":"next_step",
                   "input":{"action":"home","say":"Go home","progress":"wrong app"}}],
                   "usage":{"input_tokens":100,"cache_read_input_tokens":800,"output_tokens":30}}""",
            ),
        )
        val client = AnthropicClient({ "key" }, baseUrl = server.url("/").toString())
        val result = client.call(
            ToolCallRequest("SYS", "USER", ImagePart(byteArrayOf(9)), Step.TOOL_NAME, "d", Step.toolSchema(Language.English), "claude-haiku-4-5"),
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("tool", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("home", Step.parse(result.args!!)!!.action.wire)
        assertEquals(800, result.usage.cachedTokens)
        assertEquals(900, result.usage.inputTokens)
        server.shutdown()
    }
}
