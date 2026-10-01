package com.drishti.core.eval

import com.drishti.core.agent.GuideConfig
import com.drishti.core.agent.GuideSession
import com.drishti.core.agent.Language
import com.drishti.core.agent.LlmPlanner
import com.drishti.core.agent.ModelConfig
import com.drishti.core.agent.Outcome
import com.drishti.core.agent.SessionResult
import com.drishti.core.llm.OpenAiCompatClient
import com.drishti.core.screen.ScreenEncoder
import com.drishti.core.sim.GoldenTasks
import com.drishti.core.sim.PixelWorld
import com.drishti.core.sim.RecordingVoice
import com.drishti.core.sim.SimUser
import com.drishti.core.speech.SarvamRealtimeStt
import com.drishti.core.speech.SarvamTts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Runs the real guide agent, with a real model, against the simulated Pixel and prints a
 * scorecard. This is the number to move: change a prompt, a model or the encoder, run it.
 *
 *   ./gradlew :core:eval
 *   ./gradlew :core:eval --args="--models google/gemini-2.5-flash,openai/gpt-4.1-mini --runs 2"
 *   ./gradlew :core:eval --args="--tasks bluetooth_on,wa_video_amma --language Hindi"
 *   ./gradlew :core:eval --args="--speech"      # Sarvam TTS → STT round trip
 *   ./gradlew :core:eval --args="--dump"        # print every simulated screen as the model sees it
 *   ./gradlew :core:eval --args="--list-models" # OpenRouter models that take tools and images
 */
fun main(args: Array<String>) = runBlocking {
    val opts = parse(args)
    if ("dump" in opts) return@runBlocking dumpScreens()
    if ("list-models" in opts) return@runBlocking listModels()
    if ("speech" in opts) return@runBlocking speechRoundTrip(opts["language"]?.let { Language.valueOf(it) } ?: Language.Hindi)

    val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
    require(key.isNotBlank()) { "Set OPENROUTER_API_KEY (env or local.properties)." }
    val models = (opts["models"] ?: System.getenv("OPENROUTER_MODEL") ?: DEFAULT_FAST).split(',').map { it.trim() }
    val smart = opts["smart"] ?: System.getenv("OPENROUTER_SMART_MODEL") ?: DEFAULT_SMART
    val runs = opts["runs"]?.toInt() ?: 1
    val language = opts["language"]?.let { Language.valueOf(it) } ?: Language.English
    val tasks = opts["tasks"]?.split(',')?.map { GoldenTasks.byId(it.trim()) } ?: GoldenTasks.all

    val client = OpenAiCompatClient(
        id = "openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = { key },
        extraHeaders = mapOf("HTTP-Referer" to "https://github.com/dupenodi/aura", "X-Title" to "Aura eval"),
    )
    client.warmUp()

    val config = GuideConfig(repeatAfterMs = 6_000, emphasizeAfterMs = 10_000, pauseAfterMs = 15_000, pausedGiveUpMs = 1)
    val reports = mutableListOf<RunReport>()
    for (model in models) {
        println("\n=== $model (smart: $smart, ${language.label}) ===")
        val planner = LlmPlanner(client, ModelConfig(fast = model, smart = smart))
        for (task in tasks) repeat(runs) { run ->
            val phone = GoldenTasks.prepare(task)
            val user = SimUser(phone, this, SimUser.Behavior(reactionMs = 400, typeDelayMs = 200))
            val voice = RecordingVoice()
            val transcript = StringBuilder()
            val started = System.currentTimeMillis()
            val result: SessionResult = runCatching {
                GuideSession(task.prompt, language, phone, planner, user, voice, config) { kind, data ->
                    when (kind) {
                        "plan" -> transcript.appendLine("  · ${data["model"]} ${data["ms"]}ms in=${data["in"]} cached=${data["cached"]} → ${data["step"]}")
                        "outcome" -> transcript.appendLine("    ↳ ${data["result"]}")
                        "llm_error", "llm_retry" -> transcript.appendLine("  ! $kind $data")
                    }
                }.run()
            }.getOrElse { e ->
                transcript.appendLine("  ! crashed: $e")
                SessionResult(Outcome.Stopped, 0, e.message, emptyList(), emptyList())
            }
            val ok = task.succeeded(phone, result)
            val r = RunReport(model, task.id, run, ok, result, task.optimalSteps, System.currentTimeMillis() - started, transcript.toString(), voice.said.toList())
            reports += r
            println(r.line())
            if (!ok || "verbose" in opts) print(transcript)
        }
    }
    printSummary(reports)
    writeReport(reports)
}

private const val DEFAULT_FAST = "google/gemini-2.5-flash"
private const val DEFAULT_SMART = "anthropic/claude-sonnet-4.5"

private class RunReport(
    val model: String,
    val task: String,
    val run: Int,
    val ok: Boolean,
    val result: SessionResult,
    val optimal: Int,
    val wallMs: Long,
    val transcript: String,
    val said: List<String>,
) {
    fun line(): String {
        val plan = result.metrics.map { it.planMs }
        return "%-4s %-18s steps %2d/%-2d  plan p50 %5dms  %s".format(
            if (ok) "PASS" else "FAIL", task, result.steps, optimal, percentile(plan, 50), result.message ?: "",
        )
    }
}

private fun printSummary(reports: List<RunReport>) {
    println("\n=== Scorecard ===")
    println("%-34s %8s %10s %10s %10s %9s %9s %10s".format("model", "success", "steps/opt", "plan p50", "plan p95", "in tok", "cached", "cost $"))
    reports.groupBy { it.model }.forEach { (model, rs) ->
        val metrics = rs.flatMap { it.result.metrics }
        val plan = metrics.map { it.planMs }
        val ok = rs.count { it.ok }
        val stepRatio = rs.filter { it.ok && it.optimal > 0 }.map { it.result.steps.toDouble() / it.optimal }.average()
        val inTok = metrics.map { it.inputTokens }.average()
        val cached = metrics.sumOf { it.cachedTokens }.toDouble() / metrics.sumOf { it.inputTokens }.coerceAtLeast(1)
        println(
            "%-34s %7.0f%% %10.2f %8dms %8dms %9.0f %8.0f%% %10s".format(
                model.take(34), 100.0 * ok / rs.size, stepRatio, percentile(plan, 50), percentile(plan, 95),
                inTok, cached * 100, "-",
            ),
        )
    }
}

private fun writeReport(reports: List<RunReport>) {
    val dir = File("core/build/eval").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val file = File(dir, "eval-$stamp.md")
    file.writeText(
        buildString {
            appendLine("# Aura eval $stamp\n")
            reports.forEach { r ->
                appendLine("## ${if (r.ok) "✅" else "❌"} ${r.task} — ${r.model} (run ${r.run})")
                appendLine("steps ${r.result.steps}/${r.optimal}, outcome ${r.result.outcome}, ${r.wallMs}ms wall\n")
                appendLine("```")
                append(r.transcript)
                appendLine("said: ${r.said}")
                appendLine("```\n")
            }
        },
    )
    println("\nTranscripts: ${file.path}")
}

private fun percentile(xs: List<Long>, p: Int): Long {
    if (xs.isEmpty()) return 0
    val s = xs.sorted()
    return s[((p / 100.0) * (s.size - 1)).toInt()]
}

private fun parse(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i].removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
            out[a] = args[i + 1]
            i += 2
        } else {
            out[a] = "true"
            i++
        }
    }
    return out
}

private fun dumpScreens() {
    val phone = PixelWorld.phone()
    val names = listOf("launcher", "app_drawer", "settings", "network", "internet", "display", "display_size", "wa_chats", "wa_chat_amma", "yt_home", "dialer", "clock_alarms")
    names.forEach { name ->
        phone.startAt(*if (name == "launcher") emptyArray() else arrayOf(name))
        val enc = ScreenEncoder.encode(phone.render())
        println("----- $name (${enc.text.length} chars ≈ ${enc.text.length / 4} tokens, ${phone.render().nodeCount()} nodes)")
        println(enc.text)
    }
}

/** Speaks a request with Bulbul, feeds the audio to the realtime recogniser, compares. */
private suspend fun speechRoundTrip(language: Language) {
    val key = System.getenv("SARVAM_API_KEY").orEmpty()
    require(key.isNotBlank()) { "Set SARVAM_API_KEY." }
    val phrase = when (language) {
        Language.Hindi -> "WhatsApp पर अम्मा को वीडियो कॉल करो"
        Language.Tamil -> "ப்ளூடூத்தை ஆன் செய்"
        else -> "Turn on Bluetooth please"
    }
    val tts = SarvamTts({ key })
    val pcm = java.io.ByteArrayOutputStream()
    val t0 = System.currentTimeMillis()
    var firstChunkMs = -1L
    val rate = tts.speak(phrase, language, SarvamTts.VoiceConfig(sampleRate = 16_000)) {
        if (firstChunkMs < 0) firstChunkMs = System.currentTimeMillis() - t0
        pcm.write(it)
    }
    println("TTS: ${pcm.size()} bytes @${rate}Hz, first audio after ${firstChunkMs}ms, total ${System.currentTimeMillis() - t0}ms")

    val bytes = pcm.toByteArray()
    val stt = SarvamRealtimeStt({ key })
    val result = stt.transcribe(
        flow {
            // Real time pacing, 100ms chunks, as the microphone would deliver it.
            val chunk = rate / 10 * 2
            var i = 0
            while (i < bytes.size) {
                emit(bytes.copyOfRange(i, minOf(bytes.size, i + chunk)))
                i += chunk
                delay(100)
            }
        },
        SarvamRealtimeStt.Config(language, keyterms = listOf("WhatsApp", "Bluetooth"), sampleRate = rate),
        onPartial = { println("  partial: $it") },
    )
    println("STT final: \"${result.text}\" (${result.finalLatencyMs}ms after speech end)")
    println("Said:      \"$phrase\"")
}

/** OpenRouter's current models that can call tools and read images, cheapest first. */
private fun listModels() {
    val request = okhttp3.Request.Builder().url("https://openrouter.ai/api/v1/models").build()
    val raw = com.drishti.core.llm.Http.shared.newCall(request).execute().use { it.body?.string().orEmpty() }
    val data = kotlinx.serialization.json.Json.parseToJsonElement(raw).let { (it as kotlinx.serialization.json.JsonObject)["data"] as kotlinx.serialization.json.JsonArray }
    data.map { it as kotlinx.serialization.json.JsonObject }
        .filter { m ->
            val params = m["supported_parameters"]?.toString().orEmpty()
            val modality = (m["architecture"] as? kotlinx.serialization.json.JsonObject)?.get("input_modalities")?.toString().orEmpty()
            params.contains("tool_choice") && modality.contains("image")
        }
        .map { m ->
            val id = (m["id"] as kotlinx.serialization.json.JsonPrimitive).content
            val prompt = ((m["pricing"] as? kotlinx.serialization.json.JsonObject)?.get("prompt") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            id to prompt * 1_000_000
        }
        .sortedBy { it.second }
        .forEach { (id, perM) -> println("%-55s $%.2f / M input tokens".format(id, perM)) }
}
