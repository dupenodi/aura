package com.drishti.agent

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.drishti.DrishtiApp
import com.drishti.accessibility.DrishtiAccessibilityService
import com.drishti.ai.ChatMessage
import com.drishti.ai.LlmProvider
import com.drishti.ai.LlmRouter
import com.drishti.ai.MessageContent
import com.drishti.ai.OpenAiClient
import com.drishti.ai.Speaker
import com.drishti.ai.ToolCall
import com.drishti.debug.RingBufferLogger
import com.drishti.overlay.AgentMode
import com.drishti.overlay.OverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool-calling agent loop (OpenAI primary, Anthropic fallback via [LlmRouter]).
 *
 * Flow: user text → system + on-device session memory + fresh tree observe → model →
 * execute tool → settle + auto-observe after state changes (vision once after type_text /
 * toggle taps) → return to model before the next tap. Images attach only at smart moments.
 *
 * Session memory is compact user/assistant summaries across runs (not Supabase/RAG).
 * User preferences (cab_app, food_app, …) live in [PreferenceStore] (SharedPreferences).
 * Successful multi-step paths are cached in [RecipeStore] for faster later runs.
 * Every run is also appended to [FlowHistoryStore] (finish / pause-at-cap / cancel).
 * Hard timeouts prevent "still processing" hangs: LLM / tool / overall turn.
 * Budgets raised for long multi-app demos (cab/food flows): turn 300s, LLM 90s, tool 45s.
 *
 * Demo strategy: gpt-5.6 default (+ gpt-4.1 auto-fallback) with a higher iteration cap.
 * Do NOT add a second parallel planner call path — latency and cost hurt live demos.
 * Replay uses [replayRecipe] FAST_DETERMINISTIC (no LLM, coords, 150–350ms settles;
 * one speak at start; optional tree check after open_app; LLM fallback on tap fail).
 */
@Singleton
class AgentLoop @Inject constructor(
    private val llm: LlmRouter,
    private val tools: ToolRegistry,
    private val state: AgentState,
    private val speaker: Speaker,
    private val preferenceStore: PreferenceStore,
    private val recipeStore: RecipeStore,
    private val flowHistoryStore: FlowHistoryStore,
) {
    private val mutex = Mutex()
    private val activeJob = AtomicReference<Job?>(null)
    private val cancelRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Owns replay jobs so Activity/composition scopes cannot cancel them accidentally. */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ownedReplayJob = AtomicReference<Job?>(null)

    val isBusy: Boolean
        get() = activeJob.get()?.isActive == true ||
            ownedReplayJob.get()?.isActive == true ||
            state.ui.value.status == AgentRunStatus.Running

    /** Cancel the in-flight agent turn (bubble re-tap while busy). */
    fun cancelCurrent() {
        cancelRequested.set(true)
        ownedReplayJob.getAndSet(null)?.let { job ->
            RingBufferLogger.log("agent", "cancelCurrent (replay job)")
            job.cancel(CancellationException("Cancelled by user"))
        }
        val job = activeJob.getAndSet(null)
        if (job != null) {
            RingBufferLogger.log("agent", "cancelCurrent")
            speaker.interrupt()
            job.cancel(CancellationException("Cancelled by user"))
        } else {
            RingBufferLogger.log("agent", "cancelCurrent (no active job)")
            speaker.interrupt()
        }
    }

    /**
     * Launch deterministic recipe replay on an app-owned job (not Activity scope).
     * Returns false if the agent is already busy.
     */
    fun launchReplay(recipe: StoredRecipe): Boolean {
        if (isBusy) {
            RingBufferLogger.log("replay", "launchReplay refused — agent busy")
            return false
        }
        ownedReplayJob.getAndSet(null)?.cancel()
        RingBufferLogger.log(
            "replay",
            "launchReplay key=${recipe.key} steps=${recipe.steps.size}",
        )
        lateinit var job: Job
        job = ownScope.launch {
            try {
                replayRecipe(recipe)
            } finally {
                ownedReplayJob.compareAndSet(job, null)
            }
        }
        ownedReplayJob.set(job)
        return true
    }

    /**
     * Promote a specific recent flow (or the best promotable one) into [RecipeStore].
     * Used by voice "save the flow" and the home **Save to learned** button.
     */
    fun promoteFlowToLearned(flowId: String? = null): PromoteResult {
        val flow = if (flowId != null) {
            flowHistoryStore.get(flowId)
        } else {
            flowHistoryStore.findPromotable()
        }
        if (flow == null) {
            RingBufferLogger.log("recipe", "promote failed: no promotable flow")
            return PromoteResult.NoFlow
        }
        if (!FlowHistoryStore.hasMeaningfulActions(flow)) {
            RingBufferLogger.log(
                "recipe",
                "promote failed: flow ${flow.id.take(8)} lacks real actions",
            )
            return PromoteResult.NotMeaningful
        }
        val preferredApp = preferenceStore.get("food_app")
            ?: preferenceStore.get("cab_app")
        val saved = recipeStore.saveFromFlow(flow, preferredApp)
        if (!saved) {
            RingBufferLogger.log("recipe", "promote failed: RecipeStore rejected")
            return PromoteResult.Rejected
        }
        flowHistoryStore.markPromoted(flow.id, "Saved to learned flows by user")
        RingBufferLogger.log(
            "recipe",
            "promoted flow=${flow.id.take(8)} steps=${FlowHistoryStore.actionStepCount(flow)} " +
                "utterance=${flow.userUtterance.take(80)}",
        )
        return PromoteResult.Saved(flow)
    }

    suspend fun run(utterance: String): Result<String> {
        val job = currentCoroutineContext()[Job]
        activeJob.set(job)
        cancelRequested.set(false)
        return try {
            mutex.withLock {
                withTimeout(TURN_TIMEOUT_MS) {
                    if (SaveFlowIntent.matches(utterance)) {
                        handleSaveFlowRequest(utterance)
                    } else {
                        runLocked(utterance)
                    }
                }
            }
        } catch (ce: TimeoutCancellationException) {
            val msg = "Turn timed out after ${TURN_TIMEOUT_MS / 1000}s"
            RingBufferLogger.log("agent", msg)
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg)
            toast(msg)
            Result.failure(ce)
        } catch (ce: CancellationException) {
            RingBufferLogger.log("agent", "cancelled (outer)")
            state.updateStatus(
                status = AgentRunStatus.Idle,
                statusLine = "Cancelled",
                lastError = null,
            )
            throw ce
        } finally {
            activeJob.compareAndSet(job, null)
            cancelRequested.set(false)
            OverlayService.instance?.setThinking(false)
            OverlayService.instance?.setProcessing(false)
            OverlayService.instance?.cursorHide()
        }
    }

    /**
     * Fast deterministic replay of a learned recipe (no planning LLM).
     * On tap/type failure, falls back to the normal agent loop with the recipe injected.
     */
    suspend fun replayRecipe(recipe: StoredRecipe): Result<String> {
        val job = currentCoroutineContext()[Job]
        activeJob.set(job)
        cancelRequested.set(false)
        return try {
            mutex.withLock {
                withTimeout(TURN_TIMEOUT_MS) {
                    replayLocked(recipe)
                }
            }
        } catch (ce: TimeoutCancellationException) {
            val msg = "Replay timed out after ${TURN_TIMEOUT_MS / 1000}s"
            RingBufferLogger.log("replay", msg)
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg)
            toast(msg)
            Result.failure(ce)
        } catch (ce: CancellationException) {
            RingBufferLogger.log("replay", "cancelled (outer)")
            state.updateStatus(
                status = AgentRunStatus.Idle,
                statusLine = "Cancelled",
                lastError = null,
            )
            throw ce
        } finally {
            activeJob.compareAndSet(job, null)
            cancelRequested.set(false)
            OverlayService.instance?.setThinking(false)
            OverlayService.instance?.setProcessing(false)
            OverlayService.instance?.cursorHide()
        }
    }

    /**
     * Short-circuit: promote prior substantial run into Learned flows — do not plan/finish.
     */
    private suspend fun handleSaveFlowRequest(utterance: String): Result<String> {
        RingBufferLogger.log("agent", "save-flow intent detected — promoting prior run")
        state.beginTurn(utterance.trim().take(200))
        state.updateStatus(
            status = AgentRunStatus.Running,
            statusLine = "Saving learned flow…",
            iteration = 0,
            model = "save",
            provider = "local",
            lastError = null,
        )
        OverlayService.instance?.setThinking(true)

        val result = promoteFlowToLearned(flowId = null)
        val speechLang = state.lastDetectedLanguage
            ?: preferenceStore.get("language")?.let {
                com.drishti.ai.SarvamClient.normalizeTtsLanguage(it)
            }
            ?: "en-IN"
        val isKannada = speechLang.startsWith("kn", ignoreCase = true)

        return when (result) {
            is PromoteResult.Saved -> {
                val summary =
                    "Saved prior flow to learned flows: ${result.flow.userUtterance.take(120)}"
                flowHistoryStore.save(
                    FlowRecord(
                        userUtterance = utterance.trim().take(240),
                        mode = resolveMode().name,
                        summary = summary,
                        steps = emptyList(),
                        packageName = result.flow.packageName,
                        durationMs = 0L,
                        completed = true,
                    ),
                )
                state.appendTurn(
                    AgentTurn(
                        userText = utterance.trim().take(200),
                        messages = listOf(ChatMessage.user(utterance.trim().take(200))),
                        summary = summary,
                    ),
                )
                state.updateStatus(
                    status = AgentRunStatus.Finished,
                    statusLine = "Saved to learned flows",
                    lastSummary = summary,
                    provider = "local",
                    model = "save",
                )
                val spoken = if (isKannada) {
                    "ಫ್ಲೋ learned flows ಗೆ ಸೇವ್ ಆಯ್ತು"
                } else {
                    "Saved that flow to learned flows"
                }
                speaker.speakAsync(spoken, speechLang)
                toast("Saved to learned flows")
                RingBufferLogger.log("agent", "save-flow ok: $summary")
                Result.success(summary)
            }
            PromoteResult.NoFlow -> {
                val msg = "No recent flow to save — run a multi-step task first"
                finishSaveFlowFailure(utterance, msg, speechLang, isKannada)
                Result.failure(IllegalStateException(msg))
            }
            PromoteResult.NotMeaningful, PromoteResult.Rejected -> {
                val msg = "That run has no real UI actions to learn"
                finishSaveFlowFailure(utterance, msg, speechLang, isKannada)
                Result.failure(IllegalStateException(msg))
            }
        }
    }

    private suspend fun finishSaveFlowFailure(
        utterance: String,
        msg: String,
        speechLang: String,
        isKannada: Boolean,
    ) {
        flowHistoryStore.save(
            FlowRecord(
                userUtterance = utterance.trim().take(240),
                mode = resolveMode().name,
                summary = msg,
                steps = emptyList(),
                completed = false,
            ),
        )
        state.updateStatus(
            status = AgentRunStatus.Error,
            statusLine = msg,
            lastError = msg,
            provider = "local",
            model = "save",
        )
        val spoken = if (isKannada) {
            "ಸೇವ್ ಮಾಡಲು ಹಿಂದಿನ ಫ್ಲೋ ಸಿಗಲಿಲ್ಲ"
        } else {
            msg
        }
        speaker.speakAsync(spoken, speechLang)
        toast(msg)
        RingBufferLogger.log("agent", "save-flow failed: $msg")
    }

    sealed class PromoteResult {
        data class Saved(val flow: FlowRecord) : PromoteResult()
        data object NoFlow : PromoteResult()
        data object NotMeaningful : PromoteResult()
        data object Rejected : PromoteResult()
    }

    private suspend fun runLocked(utterance: String): Result<String> {
        val text = utterance.trim()
        if (text.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty command"))
        }
        if (!llm.hasAnyKey()) {
            val msg = llm.missingKeysMessage()
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg, provider = "")
            toast(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (DrishtiAccessibilityService.instance == null) {
            DrishtiAccessibilityService.showEnableToast()
            val msg = "Enable Drishti in Accessibility settings"
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg)
            toast(msg)
            return Result.failure(IllegalStateException(msg))
        }

        ensureOverlay()

        val mode = resolveMode()
        state.setMode(mode)
        state.beginTurn(text)
        OverlayService.instance?.setMode(mode)
        val startedAt = System.currentTimeMillis()
        val historySteps = mutableListOf<FlowHistoryStep>()
        var lastPackageName: String? = null

        val (screenW, screenH) = screenSize()
        val prefsBlock = preferenceStore.formatForPrompt()
        val speechLang = state.lastDetectedLanguage
            ?: preferenceStore.get("language")?.let {
                com.drishti.ai.SarvamClient.normalizeTtsLanguage(it)
            }
        val system = ChatMessage.system(
            SystemPrompt.build(
                screenW = screenW,
                screenH = screenH,
                mode = mode,
                preferencesBlock = prefsBlock,
                speechLanguageHint = speechLang,
            ),
        )

        val messages = mutableListOf<ChatMessage>()
        messages += system
        // Compact on-device session memory across runs (user goals + finish summaries).
        // Full prior tool traces are not replayed — keeps context small and goal-focused.
        // Cross-device long-term memory can be Supabase later.
        appendSessionMemory(messages)
        appendRecipeHint(messages, text)
        messages += ChatMessage.user(text)

        var preferAnthropic = !llm.hasOpenAiKey()
        var providerLabel = if (preferAnthropic) "Anthropic" else "OpenAI"
        // gpt-5.6 (+ gpt-4.1 fallback) with higher MAX_ITERATIONS is the demo strategy.
        // Do NOT spin up a parallel planner alongside this loop.
        var model = if (preferAnthropic) {
            com.drishti.ai.AnthropicClient.MODEL
        } else {
            OpenAiClient.selectModel(messages, escalate = false)
        }
        var escalateOpenAi = false

        // Observe-before-act: fresh tree BEFORE the first LLM call so the model knows
        // the real foreground package (prevents "Instagram not open" after it already opened).
        state.updateStatus(
            status = AgentRunStatus.Running,
            statusLine = "Observing screen…",
            iteration = 0,
            model = model,
            provider = providerLabel,
            lastError = null,
        )
        OverlayService.instance?.setThinking(true)
        OverlayService.instance?.cursorShow()

        val initialObs = executeToolTimed(
            name = "observe_screen",
            argumentsJson = """{"include_image":false}""",
            mode = mode,
        )
        historySteps += FlowHistoryStep(
            name = "observe_screen",
            args = """{"include_image":false}""",
            success = initialObs.success,
        )
        extractPackageName(initialObs.text)?.let { lastPackageName = it }
        appendObservation(
            messages,
            initialObs,
            label = "Current screen BEFORE acting — trust packageName; do not claim an app is closed",
        )
        messages += ChatMessage.user(instagramSearchHint(text, initialObs.text))
        messages += ChatMessage.user(foodDeliverySearchHint(text, initialObs.text))
        messages += ChatMessage.user(cabBookingHint(text, initialObs.text))
        groceryCheckoutHint(text, initialObs.text)?.let { messages += ChatMessage.user(it) }

        var toolFailures = 0
        var finishedSummary: String? = null
        var hitIterationCap = false
        var iteration = 0
        val turnMessages = mutableListOf<ChatMessage>()
        turnMessages += ChatMessage.user(text)

        // Stuck detection: same observe/tool signature repeating without progress.
        var lastSignature: String? = null
        var sameSignatureCount = 0
        // At most one auto vision observe per turn (after type_text / search).
        var autoVisionUsed = false
        // First vision of the turn may use detail=high; later images stay detail=low for speed.
        var highDetailVisionUsed = false
        // Successful action tools for RecipeStore (replay cache after finish).
        val recipeSteps = mutableListOf<RecipeStep>()

        fun persistFlow(summary: String, completed: Boolean) {
            flowHistoryStore.save(
                FlowRecord(
                    userUtterance = text,
                    mode = mode.name,
                    summary = summary.take(500),
                    steps = historySteps.toList(),
                    packageName = lastPackageName,
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                    completed = completed,
                ),
            )
        }

        try {
            while (iteration < MAX_ITERATIONS && finishedSummary == null) {
                checkCancel()
                iteration++
                state.updateStatus(
                    status = AgentRunStatus.Running,
                    statusLine = "Planning (iter $iteration)…",
                    iteration = iteration,
                    model = model,
                    provider = providerLabel,
                )
                RingBufferLogger.log(
                    "agent",
                    "iter=$iteration provider=$providerLabel model=$model",
                )

                if (!preferAnthropic) {
                    model = OpenAiClient.selectModel(messages, escalate = escalateOpenAi)
                }
                val chat = withTimeout(LLM_TIMEOUT_MS) {
                    llm.chat(
                        messages = messages,
                        tools = ToolDefinitions.all,
                        openAiModel = model,
                        preferAnthropic = preferAnthropic,
                    )
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    val msg = e.message ?: "LLM request failed"
                    RingBufferLogger.log("agent", "llm error=$msg")
                    state.updateStatus(
                        AgentRunStatus.Error,
                        msg,
                        lastError = msg,
                        provider = providerLabel,
                    )
                    toast(msg)
                    return Result.failure(e)
                }

                if (chat.provider == LlmProvider.Anthropic) {
                    preferAnthropic = true
                }
                providerLabel = chat.provider.name
                model = chat.model

                val assistantMsg = chat.message
                messages += assistantMsg
                turnMessages += assistantMsg

                val toolCalls = assistantMsg.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    val content = when (val c = assistantMsg.content) {
                        is com.drishti.ai.MessageContent.Text -> c.text
                        else -> chat.finishReason ?: "no tool call"
                    }
                    finishedSummary = content.ifBlank { "Stopped without finish()" }
                    break
                }

                for (call in toolCalls) {
                    checkCancel()
                    val signature = "${call.function.name}:${call.function.arguments.take(120)}"
                    if (signature == lastSignature) {
                        sameSignatureCount++
                        if (sameSignatureCount >= STUCK_SAME_TOOL_LIMIT) {
                            val msg =
                                "Stuck repeating ${call.function.name} — stopping turn"
                            RingBufferLogger.log("agent", msg)
                            finishedSummary = msg
                            toast(msg)
                            break
                        }
                    } else {
                        lastSignature = signature
                        sameSignatureCount = 1
                    }

                    state.updateStatus(
                        status = AgentRunStatus.Running,
                        statusLine = "Tool: ${call.function.name}",
                        iteration = iteration,
                        model = model,
                        provider = providerLabel,
                        lastToolName = call.function.name,
                    )

                    val result = executeOne(call, mode)
                    val toolMsg = ChatMessage.tool(call.id, result.text)
                    messages += toolMsg
                    turnMessages += toolMsg

                    val historyArgs = if (
                        result.success &&
                        (call.function.name == "tap" || call.function.name == "tap_node") &&
                        result.tapX != null &&
                        result.tapY != null
                    ) {
                        recipeStore.mergeCoordsIntoArgs(
                            call.function.arguments,
                            result.tapX,
                            result.tapY,
                        ).take(220)
                    } else {
                        call.function.arguments.take(220)
                    }
                    historySteps += FlowHistoryStep(
                        name = call.function.name,
                        args = historyArgs,
                        success = result.success,
                        resultText = result.text.take(160),
                    )
                    extractPackageName(result.text)?.let { lastPackageName = it }

                    if (result.success && call.function.name in RecipeStore.ACTION_TOOLS) {
                        recipeSteps += recipeStore.stepFromToolCall(
                            call.function.name,
                            call.function.arguments,
                            tapX = result.tapX,
                            tapY = result.tapY,
                            resultText = result.text,
                        )
                    }

                    // Duplicate tap blocked — steer model to observe / finish, not re-tap.
                    if (result.text.startsWith("blocked: already tapped")) {
                        messages += ChatMessage.user(
                            "Tap was blocked (already acted on this control/node). " +
                                "Observe the screen (tree-only first; include_image once if ambiguous). " +
                                "Do NOT tap the same control again unless observe shows it failed. " +
                                "If a toggle label already matches the goal, call finish(summary).",
                        )
                        break
                    }

                    // Checkout finish blocked — cart/address is not done; continue to pay.
                    if (result.text.startsWith("blocked: checkout incomplete")) {
                        messages += ChatMessage.user(
                            "Checkout incomplete: cart/address is NOT done. " +
                                "speak() a short status, then select/confirm address and tap " +
                                "Pay / Place order / Proceed to pay. If Pay controls look ambiguous, " +
                                "observe_screen(include_image=true) once before the risky tap. " +
                                "Only finish() on the payment screen or when blocked on UPI/password/OTP.",
                        )
                        break
                    }

                    // Model-requested observe with image: tool role is text-only; attach vision
                    // as a follow-up user message so OpenAI + Anthropic adapters both see it.
                    if (call.function.name == "observe_screen" && result.imageBase64 != null) {
                        val detail = nextImageDetail(highDetailVisionUsed).also {
                            if (it == "high") highDetailVisionUsed = true
                        }
                        appendObservation(
                            messages,
                            result,
                            label = "Screen image (model requested include_image)",
                            imageDetail = detail,
                        )
                        turnMessages += ChatMessage.userWithImage(
                            "[observe_screen image]\n${result.text.take(2_000)}",
                            result.imageBase64,
                            detail = detail,
                        )
                        RingBufferLogger.log(
                            "vlm",
                            "attached image to LLM messages (model request detail=$detail)",
                        )
                    }

                    state.updateStatus(
                        status = AgentRunStatus.Running,
                        statusLine = "Tool: ${call.function.name}",
                        iteration = iteration,
                        model = model,
                        provider = providerLabel,
                        lastToolName = call.function.name,
                        lastToolResult = result.text.take(300),
                    )

                    if (!result.success) {
                        toolFailures++
                        if (!preferAnthropic && toolFailures >= 2 && !escalateOpenAi) {
                            escalateOpenAi = true
                            model = OpenAiClient.MODEL_ESCALATED
                            RingBufferLogger.log(
                                "agent",
                                "escalating to $model after $toolFailures tool failures",
                            )
                        }
                    }

                    if (result.finished) {
                        finishedSummary = result.summary ?: result.text
                        break
                    }

                    // After state changes: settle, auto-observe, then return to the model
                    // before any further taps in this batch (especially after type_text).
                    if (result.stateChanging) {
                        checkCancel()
                        // Allow up to ~1.5s settle for slow apps (Instagram after open_app).
                        val settle = result.settleMs.coerceIn(0L, 1_500L)
                        if (settle > 0L) {
                            RingBufferLogger.log("agent", "settle ${settle}ms after ${call.function.name}")
                            delay(settle)
                        }
                        checkCancel()
                        // Vision after open_app / type_text (once), and always after toggle taps
                        // so Follow/Unfollow label flips are visible before the model acts again.
                        val afterToggle = state.lastTapWasToggle &&
                            (call.function.name == "tap" || call.function.name == "tap_node")
                        // Vision only at smart moments (open_app / type_text / toggle); else tree-only.
                        val useVision = result.preferVisionObserve && (!autoVisionUsed || afterToggle)
                        if (useVision && !afterToggle) autoVisionUsed = true
                        val fresh = withTimeout(TOOL_TIMEOUT_MS) {
                            if (useVision) {
                                tools.observeScreenWithImage()
                            } else {
                                tools.observeScreenTreeOnly()
                            }
                        }
                        val imageDetail = if (useVision && fresh.imageBase64 != null) {
                            nextImageDetail(highDetailVisionUsed).also {
                                if (it == "high") highDetailVisionUsed = true
                            }
                        } else {
                            "low"
                        }
                        val label = when {
                            useVision && fresh.imageBase64 != null && afterToggle ->
                                "Updated screen after toggle tap (tree + image). " +
                                    "If Follow/Following/Unfollow/Like/Save already matches the goal, " +
                                    "finish() — do NOT tap the same toggle again."
                            useVision && fresh.imageBase64 != null ->
                                "Updated screen observation (tree + image after ${call.function.name})"
                            afterToggle ->
                                "Updated screen after toggle tap (tree). " +
                                    "If the toggle label already matches the goal, finish() — do not re-tap."
                            else ->
                                "Updated screen observation (tree after ${call.function.name})"
                        }
                        appendObservation(
                            messages,
                            fresh,
                            label = label,
                            imageDetail = imageDetail,
                        )
                        if (fresh.imageBase64 != null) {
                            turnMessages += ChatMessage.userWithImage(
                                "[observe_screen auto]\n${fresh.text.take(4_000)}",
                                fresh.imageBase64,
                                detail = imageDetail,
                            )
                            RingBufferLogger.log(
                                "vlm",
                                "attached image to LLM messages " +
                                    "(auto after ${call.function.name} detail=$imageDetail)",
                            )
                        } else {
                            turnMessages += ChatMessage.user(
                                "[observe_screen auto]\n${fresh.text.take(4_000)}",
                            )
                        }
                        // After typing a cab destination, remind the model not to finish early.
                        if (call.function.name == "type_text" && isCabOrBookingGoal(text)) {
                            messages += ChatMessage.user(
                                "Booking reminder: after typing a destination/search you MUST " +
                                    "tap a suggestion/result, then select a ride (or confirm the " +
                                    "order). Do NOT call finish() until the cab/order step is done " +
                                    "or you are blocked on payment/login.",
                            )
                        }
                        // Grocery/food: after type_text / cart taps, keep going to address + pay.
                        groceryCheckoutPostActionHint(
                            userText = text,
                            toolName = call.function.name,
                            toolResultText = result.text,
                            obsText = fresh.text,
                        )?.let { messages += ChatMessage.user(it) }
                        // Soft narration nudge when model skipped speak for 2+ actions.
                        if (state.needsNarrationNudge()) {
                            messages += ChatMessage.user(
                                "Narrate with speak() before next action " +
                                    "(user language; keep search type_text in English).",
                            )
                        }
                        // After open_app on Swiggy/Zomato: inject search node ids; vision already preferred.
                        if (call.function.name == "open_app") {
                            val openHint = foodAppPostOpenSearchHint(text, result.text, fresh.text)
                            if (openHint != null) {
                                messages += ChatMessage.user(openHint)
                            }
                        }
                        // Stop processing further tool calls so the model must see
                        // results before tapping (critical after search typing).
                        break
                    }
                }
            }

            if (finishedSummary == null) {
                hitIterationCap = true
                finishedSummary =
                    "INCOMPLETE: reached max iterations ($MAX_ITERATIONS). " +
                        "Unfinished goal: ${text.take(240)}. " +
                        "Say continue / next / keep going to resume this task."
            }
            val summary = finishedSummary
                ?: "Stopped without finish()"

            state.appendTurn(
                AgentTurn(
                    userText = text,
                    messages = turnMessages.toList(),
                    summary = summary,
                ),
            )
            val completedClean = !hitIterationCap &&
                !summary.startsWith("INCOMPLETE", ignoreCase = true) &&
                !summary.startsWith("Stuck", ignoreCase = true)
            val finishAsksSave = SaveFlowIntent.matchesFinishSummary(summary) ||
                SaveFlowIntent.matches(text)
            // If finish() / utterance is a save request, promote the PREVIOUS substantial run.
            // Never recipe-save a meta observe/speak/finish-only turn.
            if (finishAsksSave) {
                when (val promoted = promoteFlowToLearned(flowId = null)) {
                    is PromoteResult.Saved -> {
                        RingBufferLogger.log(
                            "recipe",
                            "promoted prior run via finish/save summary",
                        )
                        toast("Saved to learned flows")
                    }
                    else -> {
                        RingBufferLogger.log(
                            "recipe",
                            "finish asked save but promote failed: $promoted",
                        )
                    }
                }
            } else if (completedClean && recipeStore.hasRealUiActions(recipeSteps)) {
                val preferredApp = preferenceStore.get("food_app")
                    ?: preferenceStore.get("cab_app")
                if (recipeStore.save(text, recipeSteps.toList(), preferredApp)) {
                    RingBufferLogger.log("recipe", "cached after finish steps=${recipeSteps.size}")
                }
            } else if (completedClean) {
                RingBufferLogger.log(
                    "recipe",
                    "skip cache: no real UI actions (meta-only run)",
                )
            }
            persistFlow(summary, completed = completedClean && !finishAsksSave)
            state.updateStatus(
                status = AgentRunStatus.Finished,
                statusLine = if (hitIterationCap) "Paused — say continue" else "Done",
                iteration = iteration,
                model = model,
                provider = providerLabel,
                lastSummary = summary,
            )
            if (hitIterationCap) {
                val continueHint = "Say continue to resume"
                toast(continueHint)
                // Async TTS — don't block turn completion on narration.
                speaker.speakAsync(continueHint, speechLang ?: "en-IN").onFailure {
                    RingBufferLogger.log("agent", "cap speak failed: ${it.message}")
                }
            }
            RingBufferLogger.log("agent", "finish provider=$providerLabel: $summary")
            return Result.success(summary)
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) throw ce
            RingBufferLogger.log("agent", "cancelled")
            persistFlow("Cancelled", completed = false)
            state.updateStatus(
                status = AgentRunStatus.Idle,
                statusLine = "Cancelled",
                lastError = null,
                provider = providerLabel,
            )
            toast("Cancelled")
            throw ce
        } catch (t: Throwable) {
            val msg = t.message ?: "Agent loop failed"
            RingBufferLogger.log("agent", "error=$msg")
            persistFlow(msg, completed = false)
            state.updateStatus(
                AgentRunStatus.Error,
                msg,
                lastError = msg,
                provider = providerLabel,
            )
            toast(msg)
            return Result.failure(t)
        }
    }

    /**
     * FAST_DETERMINISTIC replay: no LLM, no per-step speak, coordinate taps, short settles.
     * Optional tree check after open_app; on critical tap fail → one LLM fallback.
     */
    private suspend fun replayLocked(recipe: StoredRecipe): Result<String> {
        if (DrishtiAccessibilityService.instance == null) {
            DrishtiAccessibilityService.showEnableToast()
            val msg = "Enable Drishti in Accessibility settings"
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg)
            toast(msg)
            return Result.failure(IllegalStateException(msg))
        }
        ensureOverlay()
        val mode = resolveMode()
        state.setMode(mode)
        val utterance = recipe.sourceUtterance.ifBlank { recipe.intentLabel }
        state.beginTurn("Replay: $utterance")
        OverlayService.instance?.setMode(mode)
        OverlayService.instance?.setThinking(true)
        OverlayService.instance?.cursorShow()

        val startedAt = System.currentTimeMillis()
        val historySteps = mutableListOf<FlowHistoryStep>()
        var lastPackageName: String? = null
        val speechLang = state.lastDetectedLanguage
            ?: preferenceStore.get("language")?.let {
                com.drishti.ai.SarvamClient.normalizeTtsLanguage(it)
            }
        var observedAfterOpen = false

        state.updateStatus(
            status = AgentRunStatus.Running,
            statusLine = "Fast replay…",
            iteration = 0,
            model = "replay",
            provider = "cache",
            lastError = null,
        )
        recipeStore.markUsed(recipe.key)
        RingBufferLogger.log(
            "replay",
            "start FAST_DETERMINISTIC key=${recipe.key} steps=${recipe.steps.size}",
        )
        toast("Replaying ${recipe.intentLabel}")

        try {
            // One short speak at start only — skip speak during step execution.
            speaker.speakAsync("Replaying", speechLang ?: "en-IN")

            for ((index, step) in recipe.steps.withIndex()) {
                checkCancel()
                val (toolName, args) = recipeStore.replayInvocation(step)
                state.updateStatus(
                    status = AgentRunStatus.Running,
                    statusLine = "Replay ${index + 1}/${recipe.steps.size}: $toolName",
                    iteration = index + 1,
                    model = "replay",
                    provider = "cache",
                    lastToolName = toolName,
                )
                var result = executeToolTimed(toolName, args, mode)
                // If preferred xy tap failed and we still have node_id, try tap_node once.
                if (!result.success &&
                    toolName == "tap" &&
                    step.name == "tap_node" &&
                    step.nodeId != null
                ) {
                    val nodeArgs = """{"node_id":${step.nodeId}}"""
                    RingBufferLogger.log("replay", "tap xy failed → try tap_node id=${step.nodeId}")
                    result = executeToolTimed("tap_node", nodeArgs, mode)
                    historySteps += FlowHistoryStep(
                        "tap",
                        args,
                        success = false,
                        resultText = "xy miss",
                    )
                    historySteps += FlowHistoryStep(
                        "tap_node",
                        nodeArgs,
                        success = result.success,
                        resultText = result.text.take(160),
                    )
                } else {
                    historySteps += FlowHistoryStep(
                        toolName,
                        args,
                        success = result.success,
                        resultText = result.text.take(160),
                    )
                }
                extractPackageName(result.text)?.let { lastPackageName = it }
                state.updateStatus(
                    status = AgentRunStatus.Running,
                    statusLine = "Replay ${index + 1}/${recipe.steps.size}: $toolName",
                    iteration = index + 1,
                    model = "replay",
                    provider = "cache",
                    lastToolName = toolName,
                    lastToolResult = result.text.take(300),
                )

                if (!result.success) {
                    RingBufferLogger.log(
                        "replay",
                        "step failed $toolName → LLM fallback once",
                    )
                    toast("Replay missed a step — finishing with AI")
                    flowHistoryStore.save(
                        FlowRecord(
                            userUtterance = "Replay: $utterance",
                            mode = mode.name,
                            summary = "Fast replay failed at $toolName; falling back to agent",
                            steps = historySteps.toList(),
                            packageName = lastPackageName,
                            durationMs = System.currentTimeMillis() - startedAt,
                            completed = false,
                        ),
                    )
                    // Normal loop injects the same recipe via appendRecipeHint.
                    return runLocked(utterance)
                }

                val settle = replaySettleMs(step.name)
                if (settle > 0L) delay(settle)

                // Optional single tree check after open_app (no LLM); skip all other observes.
                if (step.name == "open_app" && !observedAfterOpen) {
                    observedAfterOpen = true
                    checkCancel()
                    runCatching {
                        withTimeout(TOOL_TIMEOUT_MS) {
                            tools.observeScreenTreeOnly()
                        }
                    }
                    RingBufferLogger.log("replay", "post-open_app tree check (no LLM)")
                }
            }

            val summary = "Replayed ${recipe.steps.size} learned steps: ${recipe.intentLabel}"
            flowHistoryStore.save(
                FlowRecord(
                    userUtterance = "Replay: $utterance",
                    mode = mode.name,
                    summary = summary,
                    steps = historySteps.toList(),
                    packageName = lastPackageName,
                    durationMs = System.currentTimeMillis() - startedAt,
                    completed = true,
                ),
            )
            state.appendTurn(
                AgentTurn(
                    userText = "Replay: $utterance",
                    messages = listOf(ChatMessage.user("Replay: $utterance")),
                    summary = summary,
                ),
            )
            state.updateStatus(
                status = AgentRunStatus.Finished,
                statusLine = "Replay done",
                iteration = recipe.steps.size,
                model = "replay",
                provider = "cache",
                lastSummary = summary,
            )
            RingBufferLogger.log(
                "replay",
                "success FAST_DETERMINISTIC steps=${recipe.steps.size} " +
                    "ms=${System.currentTimeMillis() - startedAt}",
            )
            toast("Replay done")
            return Result.success(summary)
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) throw ce
            flowHistoryStore.save(
                FlowRecord(
                    userUtterance = "Replay: $utterance",
                    mode = mode.name,
                    summary = "Cancelled",
                    steps = historySteps.toList(),
                    packageName = lastPackageName,
                    durationMs = System.currentTimeMillis() - startedAt,
                    completed = false,
                ),
            )
            state.updateStatus(
                status = AgentRunStatus.Idle,
                statusLine = "Cancelled",
                lastError = null,
                provider = "cache",
            )
            toast("Cancelled")
            throw ce
        } catch (t: Throwable) {
            val msg = t.message ?: "Replay failed"
            RingBufferLogger.log("replay", "error=$msg")
            flowHistoryStore.save(
                FlowRecord(
                    userUtterance = "Replay: $utterance",
                    mode = mode.name,
                    summary = msg,
                    steps = historySteps.toList(),
                    packageName = lastPackageName,
                    durationMs = System.currentTimeMillis() - startedAt,
                    completed = false,
                ),
            )
            state.updateStatus(AgentRunStatus.Error, msg, lastError = msg, provider = "cache")
            toast(msg)
            return Result.failure(t)
        }
    }

    /** FAST_DETERMINISTIC settles — keep multi-step grocery paths in seconds. */
    private fun replaySettleMs(toolName: String): Long = when (toolName) {
        "open_app" -> 350L
        "type_text" -> 300L
        "scroll", "swipe" -> 250L
        else -> 180L // tap / tap_node / press_key / long_press
    }

    private fun extractPackageName(text: String): String? {
        return Regex("""packageName=([^\s]+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""package=([a-zA-Z0-9._]+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
    }

    private suspend fun executeOne(call: ToolCall, mode: AgentMode): ToolResult {
        val name = call.function.name
        val args = call.function.arguments.ifBlank { "{}" }
        RingBufferLogger.log("agent", "tool_call $name args=${args.take(400)}")
        return executeToolTimed(name, args, mode)
    }

    private suspend fun executeToolTimed(
        name: String,
        argumentsJson: String,
        mode: AgentMode,
    ): ToolResult {
        return try {
            withTimeout(TOOL_TIMEOUT_MS) {
                tools.execute(name, argumentsJson, mode)
            }
        } catch (ce: TimeoutCancellationException) {
            // Unblock TTS / OkHttp so the next tool (e.g. open_app) can run.
            if (name == "speak") {
                speaker.interrupt()
            }
            val msg = "error: tool $name timed out after ${TOOL_TIMEOUT_MS / 1000}s"
            RingBufferLogger.log("agent", msg)
            ToolResult(msg, success = false)
        }
    }

    private suspend fun checkCancel() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested.get()) {
            throw CancellationException("Cancelled by user")
        }
    }

    private fun appendObservation(
        messages: MutableList<ChatMessage>,
        obs: ToolResult,
        label: String,
        imageDetail: String = "low",
    ) {
        val text = "[$label]\n${obs.text}"
        val msg = if (obs.imageBase64 != null) {
            RingBufferLogger.log(
                "vlm",
                "user message includes image label=$label detail=$imageDetail " +
                    "b64Len=${obs.imageBase64.length}",
            )
            ChatMessage.userWithImage(text, obs.imageBase64, detail = imageDetail)
        } else {
            ChatMessage.user(text)
        }
        messages += msg
    }

    /** First vision of a turn may use high detail; later images stay low for speed. */
    private fun nextImageDetail(highDetailAlreadyUsed: Boolean): String =
        if (highDetailAlreadyUsed) "low" else "high"

    private fun appendRecipeHint(messages: MutableList<ChatMessage>, userText: String) {
        val preferredApp = preferenceStore.get("food_app")
            ?: preferenceStore.get("cab_app")
        val recipe = recipeStore.findMatch(userText, preferredApp) ?: return
        messages += ChatMessage.user(recipeStore.formatForPrompt(recipe))
        RingBufferLogger.log(
            "recipe",
            "injected key=${recipe.key} steps=${recipe.steps.size}",
        )
    }

    /**
     * Inject compact on-device conversation memory so the next utterance sees prior goals.
     * Stores user text + assistant finish summaries only (no screenshots / tool dumps).
     */
    private fun appendSessionMemory(messages: MutableList<ChatMessage>) {
        val prior = state.lastSessionMessages(AgentState.HISTORY_MESSAGES_IN_PROMPT)
        if (prior.isEmpty()) return
        val transcript = buildString {
            append("Conversation memory (this on-device session — refer to prior goals):\n")
            for (msg in prior) {
                val who = when (msg.role) {
                    "user" -> "User"
                    "assistant" -> "Assistant"
                    else -> msg.role
                }
                append(who)
                append(": ")
                append(msg.text.take(400))
                append('\n')
            }
        }
        messages += ChatMessage.user(transcript.trim())
        RingBufferLogger.log("memory", "injected ${prior.size} session messages into prompt")
    }

    private fun resolveMode(): AgentMode {
        return OverlayService.instance?.mode ?: state.mode()
    }

    private fun ensureOverlay() {
        val ctx = DrishtiApp.appContextOrNull() ?: return
        if (OverlayService.instance == null) {
            runCatching { OverlayService.start(ctx) }
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            runCatching {
                val ctx = DrishtiApp.appContextOrNull() ?: return@runCatching
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val service = DrishtiAccessibilityService.instance
        if (service != null) {
            val wm = service.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                return b.width() to b.height()
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        }
        val ctx = DrishtiApp.appContextOrNull()
        val dm = ctx?.resources?.displayMetrics
        return (dm?.widthPixels ?: 1080) to (dm?.heightPixels ?: 2400)
    }

    /**
     * Hint when Instagram is already foreground and the user asked to search —
     * steers the model toward Search nav instead of re-open_app.
     */
    private fun instagramSearchHint(userText: String, obsText: String): String {
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onInstagram = pkg.contains("instagram", ignoreCase = true) ||
            obsText.contains("com.instagram.android", ignoreCase = true)
        val wantsSearch = userText.contains("search", ignoreCase = true) ||
            userText.contains("find", ignoreCase = true) ||
            userText.contains("look up", ignoreCase = true)
        return buildString {
            append("Reminder: use packageName from the observation above as ground truth. ")
            append("If the target app's package already matches, skip open_app and continue.")
            if (onInstagram && wantsSearch) {
                append(" Instagram is already open — navigate to Search tab/icon or search field; ")
                append("confirm feed vs search vs reel via tree (+ image after open/type). Do NOT open_app again.")
            }
        }
    }

    /**
     * Steers food-delivery goals away from the top address chip toward Edit/Search,
     * with annotate → tap_node and vision after open.
     */
    private fun foodDeliverySearchHint(userText: String, obsText: String): String {
        val lower = userText.lowercase()
        val foodGoal = listOf(
            "swiggy", "zomato", "biryani", "restaurant", "food", "order", "book",
            "meghana", "deliver", "dish", "cuisine", "sweets",
            "zepto", "blinkit", "instamart", "grocery", "onion", "garlic",
        ).any { lower.contains(it) }
        if (!foodGoal) {
            return "For ambiguous search bars: annotate(node_id) then tap_node(same id)."
        }
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onFoodApp = isCheckoutAppPackage(pkg) || isCheckoutAppPackage(obsText)
        val searchIds = extractSearchFieldHints(obsText)
        return buildString {
            append("HARD Swiggy/food/grocery rule: NEVER tap \"Salarpuria Arena\" or any top address/locality chip. ")
            append("ALWAYS tap the bar \"Search for '…'\" / nodes tagged [SEARCH_FIELD]. ")
            append("type_text product names in English even if the user spoke Kannada; speak() in user language. ")
            append("Sequence: tree observe → if address vs search is ambiguous use include_image once → ")
            append("annotate(node_id=search) → tap_node(same id). Prefer tree-only between later actions.")
            if (searchIds.isNotEmpty()) {
                append(" Search candidates from latest tree: $searchIds.")
            }
            if (onFoodApp) {
                append(" Food app already open — skip open_app; observe search field (vision only if ambiguous) then highlight before tapping.")
            }
        }
    }

    /**
     * After open_app(swiggy/zomato): prefer vision (already set) and list Search node ids.
     */
    private fun foodAppPostOpenSearchHint(
        userText: String,
        openResultText: String,
        obsText: String,
    ): String? {
        val openedFood = openResultText.contains("swiggy", ignoreCase = true) ||
            openResultText.contains("zomato", ignoreCase = true) ||
            openResultText.contains("zepto", ignoreCase = true) ||
            openResultText.contains("blinkit", ignoreCase = true) ||
            FOOD_APP_PKG_HINTS.any { openResultText.contains(it, ignoreCase = true) }
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onFoodApp = openedFood || isCheckoutAppPackage(pkg) || isCheckoutAppPackage(obsText)
        if (!onFoodApp) return null
        val lower = userText.lowercase()
        val foodSearchGoal = listOf(
            "search", "biryani", "restaurant", "food", "order", "swiggy", "zomato",
            "meghana", "dish", "cuisine", "sweets", "deliver",
            "zepto", "blinkit", "instamart", "grocery", "onion", "garlic", "kg",
        ).any { lower.contains(it) }
        if (!foodSearchGoal) return null
        val searchIds = extractSearchFieldHints(obsText)
        return buildString {
            append("Swiggy/food home after open: use the fresh observation (vision may be attached once). ")
            append("NEVER tap the top address (e.g. Salarpuria Arena). ")
            append("Tap [SEARCH_FIELD] / \"Search for …\" only — annotate then tap_node. Tree-only after that.")
            if (searchIds.isNotEmpty()) {
                append(" Search node ids from this observation: $searchIds.")
            } else {
                append(" If no [SEARCH_FIELD] yet, re-observe; do not tap the address chip.")
            }
        }
    }

    /** Pull [SEARCH_FIELD] / Search-labeled lines from observe prompt text. */
    private fun extractSearchFieldHints(obsText: String): String {
        val lines = obsText.lineSequence()
            .filter { line ->
                line.contains("[SEARCH_FIELD]", ignoreCase = true) ||
                    (line.contains("Search", ignoreCase = true) &&
                        (line.contains("Edit/Search") || line.contains("[") && line.contains("]")))
            }
            .take(4)
            .map { it.trim().take(120) }
            .toList()
        return lines.joinToString(" | ")
    }

    /**
     * Steers Uber/Ola booking past type_text — must tap suggestion then select a ride.
     */
    private fun cabBookingHint(userText: String, obsText: String): String {
        if (!isCabOrBookingGoal(userText)) {
            return "If resuming an incomplete cab/food booking from memory, observe first and continue mid-flow."
        }
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onCabApp = pkg.contains("uber", ignoreCase = true) ||
            pkg.contains("olacabs", ignoreCase = true) ||
            pkg.contains("ola", ignoreCase = true) ||
            obsText.contains("uber", ignoreCase = true) ||
            obsText.contains("ola", ignoreCase = true)
        return buildString {
            append("Cab/ride booking goal: typing a destination is NOT enough. ")
            append("After type_text, observe → tap a suggestion → select a ride option. ")
            append("Do NOT finish() until a cab is selected or you are blocked on payment. ")
            append("Sequence: open cab app → destination field (annotate→tap_node) → type_text → ")
            append("tap suggestion → tap ride → finish only when selected/blocked.")
            if (onCabApp) {
                append(" Cab app already open — skip open_app; continue from the current screen.")
            }
        }
    }

    private fun isCabOrBookingGoal(userText: String): Boolean {
        val lower = userText.lowercase()
        return listOf(
            "uber", "ola", "cab", "ride", "taxi", "auto", "book a", "book me",
            "destination", "swiggy", "zomato", "order",
            "zepto", "blinkit", "instamart", "grocery",
        ).any { lower.contains(it) }
    }

    private fun isGroceryOrFoodCheckoutGoal(userText: String): Boolean {
        val lower = userText.lowercase()
        return listOf(
            "zepto", "blinkit", "instamart", "swiggy", "zomato",
            "grocery", "onion", "garlic", "vegetables", "order",
            "cart", "kg",
        ).any { lower.contains(it) }
    }

    private fun isCheckoutAppPackage(text: String): Boolean {
        val t = text.lowercase()
        return CHECKOUT_APP_PKG_HINTS.any { t.contains(it) }
    }

    /**
     * Hard reminder: grocery/food cart is mid-flow — continue to address + pay.
     */
    private fun groceryCheckoutHint(userText: String, obsText: String): String? {
        if (!isGroceryOrFoodCheckoutGoal(userText) && !isCheckoutAppPackage(obsText)) {
            return null
        }
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onCheckoutApp = isCheckoutAppPackage(pkg) || isCheckoutAppPackage(obsText)
        val lowerObs = obsText.lowercase()
        val nearCart = CART_RELATED_KEYWORDS.any { lowerObs.contains(it) }
        return buildString {
            append("Grocery/food checkout: cart is NOT done. ")
            append("Flow: search (English type_text) → add items → open cart → ")
            append("select/confirm address → tap Pay/Place order. ")
            append("speak() before each major step in the user's language. ")
            append("finish() only on payment screen or when blocked on UPI/OTP/password.")
            if (onCheckoutApp) append(" Checkout app is open — do not finish at cart.")
            if (nearCart) append(" Cart/address UI visible — continue to address and pay.")
        }
    }

    /**
     * After state-changing tools on Zepto/Blinkit/Swiggy when cart-related UI appears.
     */
    private fun groceryCheckoutPostActionHint(
        userText: String,
        toolName: String,
        toolResultText: String,
        obsText: String,
    ): String? {
        val pkg = Regex("""packageName=([^\s]+)""").find(obsText)?.groupValues?.getOrNull(1).orEmpty()
        val onCheckoutApp = isCheckoutAppPackage(pkg) ||
            isCheckoutAppPackage(obsText) ||
            isCheckoutAppPackage(toolResultText)
        if (!onCheckoutApp && !isGroceryOrFoodCheckoutGoal(userText)) return null
        val lowerObs = obsText.lowercase()
        val lowerTool = toolResultText.lowercase()
        val cartRelated = CART_RELATED_KEYWORDS.any { lowerObs.contains(it) || lowerTool.contains(it) }
        val afterMidStep = toolName == "type_text" ||
            toolName == "tap" ||
            toolName == "tap_node" ||
            toolName == "open_app"
        if (!afterMidStep) return null
        if (!cartRelated && toolName != "type_text") return null
        return buildString {
            append("Checkout reminder: do NOT finish() yet. ")
            when (toolName) {
                "type_text" -> append("After search typing, add items / open cart, then address → pay. ")
                else -> append("If cart/address is visible, select address then Pay/Place order. ")
            }
            append("speak() a short status before the next action.")
        }
    }

    companion object {
        /**
         * Hard step budget per turn. Raised 15 → 30 for Uber/Ola/Swiggy multi-step demos.
         * On cap: save unfinished goal to session memory and tell the user to say continue.
         */
        const val MAX_ITERATIONS = 30
        /**
         * Per-LLM-call budget (vision + tool schemas can be slow).
         * Raised 60s → 90s for long planning / vision turns.
         */
        const val LLM_TIMEOUT_MS = 90_000L
        /**
         * Per-tool budget (speak TTS + gestures + waits).
         * Raised 30s → 45s so Indic TTS + slow app launches don't abort mid-step.
         */
        const val TOOL_TIMEOUT_MS = 45_000L
        /**
         * Whole turn budget for multi-step demos (cab/food/Instagram flows).
         * Raised 180s → 300s for hackathon-length tasks.
         */
        const val TURN_TIMEOUT_MS = 300_000L
        const val STUCK_SAME_TOOL_LIMIT = 3

        private val FOOD_APP_PKG_HINTS = listOf(
            "in.swiggy", "application.zomato", "foodpanda", "ubereats",
            "zeptoconsumerapp", "grofers", "blinkit",
        )

        private val CHECKOUT_APP_PKG_HINTS = listOf(
            "zepto", "zeptoconsumerapp",
            "blinkit", "grofers",
            "swiggy", "in.swiggy", "instamart",
            "zomato", "application.zomato",
        )

        private val CART_RELATED_KEYWORDS = listOf(
            "cart", "basket", "address", "deliver to", "delivery to",
            "proceed to pay", "place order", "checkout", "pay",
        )
    }
}
