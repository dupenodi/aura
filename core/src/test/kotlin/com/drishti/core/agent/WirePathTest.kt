package com.drishti.core.agent

import com.drishti.core.llm.OpenAiCompatClient
import com.drishti.core.sim.GoldenTask
import com.drishti.core.sim.GoldenTasks
import com.drishti.core.sim.PathItem
import com.drishti.core.sim.RecordingVoice
import com.drishti.core.sim.SimPhone
import com.drishti.core.sim.SimUser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole path a real step takes — prompt text, HTTP, JSON tool call, parsing, resolving
 * the reference back to pixels — with a fake model that can only see what a real one sees:
 * the prompt. If the encoder or prompt lose the information needed, these tasks fail.
 */
class WirePathTest {

    private class TextOracle(private val task: GoldenTask, private val phone: SimPhone) : Dispatcher() {
        val prompts = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method != "POST") return MockResponse()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
            prompts += user
            return MockResponse().setBody(toolCall(decide(user)))
        }

        // `[3] item "Battery" (86%)` or, for lists, `[2] list (scroll down for more)`.
        private val line = Regex("""^\[(\d+)] (\w+)(?: "([^"]+)")?(.*)$""")

        private fun decide(prompt: String): JsonObject {
            if (task.succeeded(phone, SessionResult(Outcome.Completed, 0, null, emptyList(), emptyList()))) {
                return step("done", "All done")
            }
            val screen = prompt.substringAfter("Screen now:\n").lines()
            val items = screen.mapNotNull { line.matchEntire(it)?.destructured?.let { (n, role, label, rest) -> Item(n.toInt(), role, label, rest) } }
            for (p in task.path.asReversed()) {
                when (p) {
                    is PathItem.Press -> items.firstOrNull { it.label.replace("‑", "-").equals(p.label.replace("‑", "-"), true) }
                        ?.let { return step("tap", "Tap ${p.label}", target = it.n) }
                    is PathItem.Type -> items.firstOrNull { it.role == "field" && it.label.equals(p.field, true) }?.let {
                        if (!it.rest.contains("= \"${p.text}\"", ignoreCase = true)) return step("type", "Type it", target = it.n, text = p.text)
                    }
                }
            }
            if (task.app != null && !screen.first().contains(task.app) && "open " !in prompt.substringBefore("Screen now:")) {
                return step("open_app", "Open the app", app = task.app)
            }
            items.firstOrNull { it.role == "list" && it.rest.contains("scroll down") }?.let { return step("scroll", "Scroll down", target = it.n, direction = "down") }
            return step("back", "Go back")
        }

        private data class Item(val n: Int, val role: String, val label: String, val rest: String)

        private fun step(action: String, say: String, target: Int? = null, text: String? = null, app: String? = null, direction: String? = null) =
            buildJsonObject {
                put("progress", "working on it")
                put("action", action)
                put("say", say)
                target?.let { put("target", it) }
                text?.let { put("text", it) }
                app?.let { put("app", it) }
                direction?.let { put("direction", it) }
            }

        private fun toolCall(args: JsonObject) = buildJsonObject {
            put("model", "fake/model")
            put(
                "choices",
                Json.parseToJsonElement(
                    """[{"message":{"role":"assistant","tool_calls":[{"id":"1","type":"function","function":{"name":"next_step","arguments":${JsonPrimitive(args.toString())}}}]}}]""",
                ),
            )
            put("usage", Json.parseToJsonElement("""{"prompt_tokens":1000,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":800}}"""))
        }.toString()
    }

    @Test
    fun goldenTasksSucceedThroughTheRealWire() = runBlocking {
        val failures = mutableListOf<String>()
        val ids = (System.getProperty("wire.ids") ?: "bluetooth_on,font_bigger,wa_message_rahul,yt_search,android_version,wifi_from_chat,font_via_search").split(",")
        for (id in ids) {
            val task = GoldenTasks.byId(id)
            val phone = GoldenTasks.prepare(task)
            val server = MockWebServer()
            val oracle = TextOracle(task, phone)
            server.dispatcher = oracle
            server.start()
            val client = OpenAiCompatClient("fake", server.url("/v1").toString(), { "k" }, openRouter = true, http = OkHttpClient())
            val planner = LlmPlanner(client, ModelConfig(fast = "fast", smart = "smart"))
            val user = SimUser(phone, this, SimUser.Behavior(reactionMs = 30, typeDelayMs = 10))
            val config = GuideConfig(
                settleQuietMs = 30, settleMaxMs = 200, pollMs = 100,
                repeatAfterMs = 1_000, emphasizeAfterMs = 1_500, pauseAfterMs = 2_000, pausedGiveUpMs = 1,
            )
            val trace = StringBuilder()
            val result = withTimeoutOrNull(30_000) {
                GuideSession(task.prompt, Language.English, phone, planner, user, RecordingVoice(), config) { k, d -> trace.appendLine("${System.currentTimeMillis() % 100000} $k ${d["result"] ?: d["step"] ?: ""}") }.run()
            }
            server.shutdown()
            if (result == null) {
                failures += "$id: timed out\n$trace"
                continue
            }
            if (!task.succeeded(phone, result)) failures += "$id: ${result.outcome} ${result.history}\n${oracle.prompts.lastOrNull()}"
            assertTrue(result.metrics.all { it.cachedTokens == 800 }, "usage flows through")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
    }
}
