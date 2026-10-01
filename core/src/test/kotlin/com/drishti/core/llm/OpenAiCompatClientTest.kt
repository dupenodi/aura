package com.drishti.core.llm

import com.drishti.core.agent.Language
import com.drishti.core.agent.Step
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().build()

    @BeforeTest
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun client(openRouter: Boolean = true, timeoutMs: Long = 5_000) = OpenAiCompatClient(
        id = "openrouter",
        baseUrl = server.url("/api/v1").toString(),
        apiKey = { "sk-test" },
        openRouter = openRouter,
        http = http,
        timeoutMs = timeoutMs,
    )

    private fun request(image: Boolean = false) = ToolCallRequest(
        system = "SYSTEM",
        user = "USER",
        image = if (image) ImagePart(byteArrayOf(1, 2, 3)) else null,
        toolName = Step.TOOL_NAME,
        toolDescription = "desc",
        toolSchema = Step.toolSchema(Language.Hindi),
        model = "google/gemini-2.5-flash",
        fallbackModels = listOf("anthropic/claude-haiku-4.5"),
    )

    private val toolCallResponse = """
        {"id":"x","model":"google/gemini-2.5-flash","choices":[{"message":{"role":"assistant","content":null,
        "tool_calls":[{"id":"c1","type":"function","function":{"name":"next_step",
        "arguments":"{\"progress\":\"on settings home\",\"action\":\"tap\",\"target\":7,\"say\":\"Battery दबाइए\",\"expect\":\"battery page\"}"}}]}}],
        "usage":{"prompt_tokens":1200,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":900},"cost":0.00021}}
    """.trimIndent()

    @Test
    fun sendsOneForcedToolCallWithFallbacksCachingAndNoReasoning() = runTest {
        server.enqueue(MockResponse().setBody(toolCallResponse))
        client().call(request(image = true))
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("next_step", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("google/gemini-2.5-flash", "anthropic/claude-haiku-4.5"), body["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("latency", body["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
        assertEquals("false", body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        val system = messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val userParts = messages[1].jsonObject["content"] as JsonArray
        val imageUrl = userParts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(imageUrl.startsWith("data:image/jpeg;base64,AQID"))

        val schema = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(listOf("progress", "action", "say"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(schema.toString().contains("Hindi"))
    }

    @Test
    fun parsesTheStepAndUsage() = runTest {
        server.enqueue(MockResponse().setBody(toolCallResponse))
        val result = client().call(request())
        val step = assertNotNull(result.args?.let(Step::parse))
        assertEquals(7, step.target)
        assertEquals("Battery दबाइए", step.say)
        assertEquals(900, result.usage.cachedTokens)
        assertEquals(1200, result.usage.inputTokens)
        assertEquals(0.00021, result.usage.costUsd)
    }

    @Test
    fun plainProviderGetsPlainSystemStringAndNoOpenRouterFields() = runTest {
        server.enqueue(MockResponse().setBody(toolCallResponse))
        client(openRouter = false).call(request())
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("SYSTEM", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertNull(body["models"])
        assertNull(body["provider"])
        assertNull(body["reasoning"])
    }

    @Test
    fun argumentsGivenInProseAreStillRecovered() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"Sure! {\"action\":\"back\",\"say\":\"Go back\",\"progress\":\"x\"}"}}]}""",
            ),
        )
        val step = client().call(request()).args?.let(Step::parse)
        assertEquals("back", step?.action?.wire)
    }

    @Test
    fun httpErrorsAreClassified() = runTest {
        val cases = mapOf(401 to LlmException.Kind.Auth, 402 to LlmException.Kind.Credit, 429 to LlmException.Kind.RateLimit, 503 to LlmException.Kind.Server)
        for ((code, kind) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"nope"}}"""))
            val e = assertFailsWith<LlmException> { client().call(request()) }
            assertEquals(kind, e.kind, "HTTP $code")
        }
    }

    @Test
    fun errorsInsideA200AreErrorsToo() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":{"code":429,"message":"Provider overloaded"}}"""))
        val e = assertFailsWith<LlmException> { client().call(request()) }
        assertEquals(LlmException.Kind.RateLimit, e.kind)
    }

    @Test
    fun slowResponsesTimeOut() = runTest {
        server.enqueue(MockResponse().setBody(toolCallResponse).setHeadersDelay(2, TimeUnit.SECONDS))
        val e = assertFailsWith<LlmException> { client(timeoutMs = 300).call(request()) }
        assertEquals(LlmException.Kind.Timeout, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun aModelThatMustReasonIsRetriedWithoutTheSetting() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Reasoning is mandatory for this endpoint"}}"""))
        server.enqueue(MockResponse().setBody(toolCallResponse))
        val c = client()
        c.call(request())
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(second["reasoning"])
        // And it stays off for the rest of the session.
        server.enqueue(MockResponse().setBody(toolCallResponse))
        c.call(request())
        assertNull((Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject)["reasoning"])
    }

    @Test
    fun anUnknownModelIdFallsThroughToTheNextModel() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"google/gemini-2.5-flash is not a valid model ID"}}"""))
        server.enqueue(MockResponse().setBody(toolCallResponse))
        client().call(request())
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("anthropic/claude-haiku-4.5", second["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnknownModelWithNothingLeftToTryFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"model not found"}}"""))
        val e = assertFailsWith<LlmException> { client().call(request().copy(fallbackModels = emptyList())) }
        assertEquals(400, e.httpCode)
    }
}
