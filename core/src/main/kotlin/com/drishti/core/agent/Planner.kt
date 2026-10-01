package com.drishti.core.agent

import com.drishti.core.llm.ImagePart
import com.drishti.core.llm.LlmClient
import com.drishti.core.llm.LlmException
import com.drishti.core.llm.ToolCallRequest
import com.drishti.core.llm.Usage
import com.drishti.core.screen.EncodedScreen

data class PlanInput(
    val task: String,
    val language: Language,
    val screen: EncodedScreen,
    val history: List<HistoryEntry>,
    val lastProgress: String?,
    val note: String?,
    val screenshot: Screenshot?,
    val apps: List<AppInfo>,
    val device: DeviceInfo,
    /** Something went wrong last time: use the stronger model. */
    val hard: Boolean,
)

data class Planned(
    /** Null when the model answered without a usable step. */
    val step: Step?,
    val model: String,
    val latencyMs: Long,
    val usage: Usage = Usage(),
    val raw: String? = null,
)

/** Decides the next step. The real one asks a model; tests use a scripted oracle. */
interface StepPlanner {
    suspend fun plan(input: PlanInput): Planned

    suspend fun warmUp() {}
}

data class ModelConfig(
    /** Every step, unless something went wrong. */
    val fast: String,
    /** After a bad step, a repeat, or a confused screen. */
    val smart: String,
    /** Provider-side fallbacks if the chosen model is unavailable (OpenRouter `models`). */
    val fallbacks: List<String> = emptyList(),
    val maxTokens: Int = 400,
    val temperature: Double = 0.2,
)

class LlmPlanner(
    private val client: LlmClient,
    private val models: ModelConfig,
) : StepPlanner {

    // The system prompt is rebuilt only when its inputs change, so it stays byte-identical
    // across a session and the provider can serve it from cache.
    private var cachedKey: Any? = null
    private var cachedSystem: String = ""

    override suspend fun warmUp() = client.warmUp()

    override suspend fun plan(input: PlanInput): Planned {
        val key = Triple(input.language, input.device, input.apps)
        if (key != cachedKey) {
            cachedSystem = Prompts.system(input.language, input.device, input.apps)
            cachedKey = key
        }
        val model = if (input.hard) models.smart else models.fast
        val request = ToolCallRequest(
            system = cachedSystem,
            user = Prompts.user(
                task = input.task,
                screen = input.screen,
                history = input.history,
                lastProgress = input.lastProgress,
                note = input.note,
                hasScreenshot = input.screenshot != null,
            ),
            image = input.screenshot?.let { ImagePart(it.jpeg) },
            toolName = Step.TOOL_NAME,
            toolDescription = Prompts.TOOL_DESCRIPTION,
            toolSchema = Step.toolSchema(input.language),
            model = model,
            fallbackModels = (listOf(if (input.hard) models.fast else models.smart) + models.fallbacks).distinct(),
            maxTokens = models.maxTokens,
            temperature = models.temperature,
        )
        val result = client.call(request)
        val step = result.args?.let(Step::parse)
        return Planned(step, result.model, result.latencyMs, result.usage, result.args?.toString() ?: result.text)
    }
}

/** Which fixed phrase explains a failed model call. */
fun LlmException.phrase(): Phrase = when (kind) {
    LlmException.Kind.Auth, LlmException.Kind.NotConfigured -> Phrase.NoModel
    LlmException.Kind.Network -> Phrase.Offline
    else -> Phrase.ModelError
}
