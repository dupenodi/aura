package com.drishti.agent

import android.content.Context
import android.util.Log
import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.accessibility.TreeJson
import com.drishti.ai.ChatBackend
import com.drishti.ai.ChatModels
import com.drishti.ai.LlmRouter
import com.drishti.data.AuraPrefs
import com.drishti.data.LockedToGuide
import com.drishti.data.Routine
import com.drishti.data.RoutineStore
import com.drishti.data.TaskHistory
import com.drishti.data.TaskOutcome
import com.drishti.data.TaskRecord
import com.drishti.eval.RunSession
import com.drishti.eval.RunStore
import com.drishti.overlay.ConfirmPromptOverlay
import com.drishti.overlay.PointerOverlay
import com.drishti.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class AgentOrchestrator(
    private val appContext: Context,
    private val pointerOverlay: PointerOverlay,
    private val confirmOverlay: ConfirmPromptOverlay,
    private val scope: CoroutineScope,
    private val client: ChatBackend = LlmRouter(),
) {
    private val speechOutput = SpeechOutput(appContext)
    private val runStore = RunStore.get(appContext)
    private val prefs = AuraPrefs.get(appContext)
    private val history = TaskHistory.get(appContext)
    private val routines = RoutineStore.get(appContext)
    private val promptImprover = PromptImprover(appContext)

    /** Steps taken in the current run, kept so a routine can learn its route. */
    private val routeTaken = mutableListOf<String>()
    private var job: Job? = null
    private var pendingAsk: CompletableDeferred<String>? = null

    /** Steps actually executed in the current run, for the history entry. */
    private var stepsTaken = 0

    /** Notifies the UI when a task starts/stops so the avatar can show activity. */
    var onRunStateChanged: ((running: Boolean) -> Unit)? = null

    /**
     * Reports the current step and what it is doing, in plain language, so the run
     * banner can keep the user informed rather than leaving them guessing.
     */
    var onProgress: ((step: Int, message: String) -> Unit)? = null

    fun cancel() {
        job?.cancel()
        pendingAsk?.complete("cancelled")
        pendingAsk = null
        pointerOverlay.showStatus("Cancelled")
    }

    fun submitUserReply(text: String) {
        pendingAsk?.complete(text)
        pendingAsk = null
    }

    fun runTask(rawTask: String, routineId: String? = null) {
        job?.cancel()
        // Agent loop must NOT run on Main — LLM + tree walks ANR otherwise.
        job = scope.launch(Dispatchers.Default) {
            stepsTaken = 0
            routeTaken.clear()
            // Voice settings can change between runs; apply them before we speak.
            speechOutput.enabled = prefs.speakAloud.value
            speechOutput.setLanguage(prefs.language.value)
            onRunStateChanged?.invoke(true)
            val mode = prefs.mode.value
            var outcome = TaskOutcome.Completed
            var detail: String? = null

            // Sharpen the request before spending a full agent turn discovering intent.
            pointerOverlay.showStatus("Thinking…")
            val improved = promptImprover.improve(rawTask)
            val task = resolveTask(improved, rawTask)
            if (task == null) {
                onRunStateChanged?.invoke(false)
                return@launch
            }

            val session = runStore.begin(task)
            try {
                runLoop(task, session, rawTask, routineId)
                if (runStore.readManifest(session.runId)?.status == "running") {
                    session.end("completed")
                }
                when (runStore.readManifest(session.runId)?.status) {
                    "stuck", "step_limit", "blocked", "llm_error" -> {
                        outcome = TaskOutcome.Stopped
                        detail = stoppedDetail(runStore.readManifest(session.runId)?.status)
                    }
                }
            } catch (e: CancellationException) {
                session.end("cancelled")
                outcome = TaskOutcome.Cancelled
                pointerOverlay.showStatus("Cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent failed", e)
                session.end("error", e.message)
                outcome = TaskOutcome.Stopped
                val human = AgentErrors.humanise(e.message)
                detail = human
                pointerOverlay.showStatus(human, 6000)
                speechOutput.speak(human)
            } finally {
                if (outcome == TaskOutcome.Completed) {
                    val routine = routineId?.let { routines.byId(it) }
                        ?: routines.matching(rawTask)
                    routine?.let { routines.recordRun(it.id, routeTaken.toList()) }
                }
                history.add(
                    TaskRecord(
                        id = session.runId,
                        task = rawTask.trim(),
                        mode = mode,
                        steps = stepsTaken,
                        outcome = outcome,
                        detail = detail,
                    ),
                )
                onRunStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Applies the improver's result: if it decided the request is genuinely ambiguous,
     * ask that question first and fold the answer back in rather than guessing.
     */
    private suspend fun resolveTask(improved: PromptImprover.Improved, rawTask: String): String? {
        val question = improved.clarifyingQuestion ?: return improved.task
        pointerOverlay.showStatus(question, 6000)
        speechOutput.speak(question)
        val answer = confirmOverlay.ask(question)
        if (answer.isBlank()) {
            pointerOverlay.showStatus("Cancelled")
            return null
        }
        return "${rawTask.trim()}\n\nUser clarified: $answer"
    }

    /** One short human sentence for what a tool call is doing, for the run banner. */
    private fun describeStep(name: String, input: JsonObject): String = when (name) {
        "tap", "tap_xy" -> "Tapping"
        "type" -> "Typing"
        "scroll" -> "Scrolling"
        "swipe" -> "Swiping"
        "open_app" -> "Opening an app"
        "back" -> "Going back"
        "home" -> "Going home"
        "recents" -> "Opening recents"
        "wait_for_change" -> "Waiting for the screen"
        "speak" -> "Explaining"
        "ask_user" -> "Waiting for your answer"
        "done" -> "Finishing up"
        else -> "Working"
    }

    /** The route a previous successful run of this task followed, if we have one. */
    private fun knownRoute(rawTask: String, routineId: String?): List<String>? {
        val routine = routineId?.let { routines.byId(it) } ?: routines.matching(rawTask)
        return routine?.learnedRoute?.takeIf { it.isNotEmpty() }
    }

    private fun stoppedDetail(status: String?): String = when (status) {
        "stuck" -> "Stopped — couldn't find the next step"
        "step_limit" -> "Stopped — took too many steps"
        "blocked" -> "Stopped — needs permission"
        "llm_error" -> "Stopped — couldn't reach the model"
        else -> "Stopped"
    }

    private suspend fun runLoop(
        task: String,
        session: RunSession,
        rawTask: String = task,
        routineId: String? = null,
    ) {
        ActionLog.clear()
        ActionLog.append("TASK: $task")
        ActionLog.append("RUN: ${session.runId}")
        if (client is LlmRouter) {
            ActionLog.append("LLM chain: ${client.selectedProviderIds().joinToString(" → ")}")
        } else {
            ActionLog.append("LLM: ${client.providerId}")
        }
        speechOutput.speak("On it")
        pointerOverlay.showStatus("Working…")
        onProgress?.invoke(0, "Looking at the screen")

        val executor = ToolExecutor(
            appContext = appContext,
            pointerOverlay = pointerOverlay,
            confirmOverlay = confirmOverlay,
            speechOutput = speechOutput,
            modeFor = { pkg -> prefs.effectiveMode(pkg) },
            autoSpeed = { prefs.autoSpeed.value },
            askUserHandler = { question ->
                val deferred = CompletableDeferred<String>()
                pendingAsk = deferred
                pointerOverlay.showStatus("Waiting for answer…")
                deferred.await()
            },
        )

        val messages = mutableListOf<ChatModels.Message>()
        messages.add(
            ChatModels.Message(
                role = "user",
                content = buildString {
                    appendLine("Task: $task")
                    appendLine()
                    appendLine("Mode: ${prefs.mode.value.name}")
                    knownRoute(rawTask, routineId)?.let { route ->
                        appendLine()
                        appendLine("This task has been done on this phone before. What worked:")
                        route.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
                        appendLine(
                            "Follow it when the screens match, but verify each step — " +
                                "apps change and a stale route is worse than none.",
                        )
                    }
                    appendLine()
                    // Sent once, not per turn: this is what stops the model inventing
                    // package names and then claiming an installed app is missing.
                    appendLine("Apps installed on this phone (name → package):")
                    appendLine(DeviceContext.installedAppsBlock(appContext))
                    appendLine()
                    appendLine(
                        "If an app the user names is in this list, it IS installed. " +
                            "Never tell the user to install something that is listed here.",
                    )
                },
            ),
        )

        var steps = 0
        var lastFp = ""
        val progress = ProgressGuard()
        var forcedToolRetry = false

        while (coroutineContext.isActive && steps < MAX_STEPS) {
            val service = ScreenAgentAccessibilityService.getInstance()
            if (service == null) {
                pointerOverlay.showStatus(
                    "I've lost permission to read the screen — turn Aura back on in Accessibility settings.",
                    6000,
                )
                speechOutput.speak("I've lost permission to read the screen")
                session.end("blocked", "accessibility_disabled")
                return
            }

            // Banking/health/password apps are never read. This is enforced here rather
            // than in settings, because the privacy screen states it as unconditional.
            if (LockedToGuide.isLocked(service.currentPackage())) {
                pointerOverlay.hide()
                pointerOverlay.showStatus(
                    "This looks like a banking app — I've stopped reading the screen.",
                    5000,
                )
                speechOutput.speak("I've stopped reading the screen here")
                session.end("blocked", "sensitive_app")
                return
            }

            val roots = withContext(Dispatchers.Default) { service.captureAgentTree() }
            val treeJson = TreeJson.compactTreeString(roots)
            val fingerprint = TreeJson.fingerprint(roots)
            val actionable = TreeJson.actionableCount(roots)
            val pkg = service.currentPackage()
            val screen = service.screenSize()
            val history = ActionLog.recent(8).joinToString("\n")

            val useVision = VisionFallback.shouldUseVision(pkg, roots)
            // Only capture when the vision fallback actually needs it: screenshots are the
            // slowest step in the loop, and we promise never to store them on disk.
            val screenshotForLlm = if (useVision) VisionFallback.captureScreenshotBase64() else null

            session.recordObserve(
                packageName = pkg,
                treeJson = treeJson,
                fingerprint = fingerprint,
                actionableCount = actionable,
                screenshotBase64 = null,
                note = if (useVision) "vision_heuristic" else null,
            )

            val contextBlocks = mutableListOf<ChatModels.ContentBlock>(
                ChatModels.ContentBlock.Text(
                    buildString {
                        val appLabel = DeviceContext.installedApps(appContext)
                            .firstOrNull { it.packageName == pkg }?.label
                        appendLine("Current app: ${appLabel ?: "unknown"} ($pkg)")
                        appendLine("Screen: ${screen.width()}x${screen.height()}")
                        appendLine("Action history:")
                        appendLine(history.ifBlank { "(none)" })
                        appendLine()
                        appendLine("Accessibility tree (indexed):")
                        appendLine(treeJson)
                        if (screenshotForLlm != null) {
                            appendLine()
                            appendLine("Vision fallback active — screenshot attached. Prefer tap_xy if needed.")
                        }
                    },
                ),
            )
            if (screenshotForLlm != null) {
                contextBlocks.add(ChatModels.ContentBlock.Image(screenshotForLlm))
            }

            messages.add(
                ChatModels.Message(role = "user", content = contextBlocks),
            )

            val llmStarted = System.currentTimeMillis()
            val response = try {
                withContext(Dispatchers.IO) {
                    client.createMessage(
                        system = SystemPrompt.TEXT,
                        messages = messages,
                        tools = ToolDefinitions.tools,
                        maxTokens = com.drishti.BuildConfig.LLM_MAX_TOKENS,
                    )
                }
            } catch (e: CancellationException) {
                session.end("cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed", e)
                session.recordLlm(
                    provider = null,
                    toolNames = emptyList(),
                    durationMs = System.currentTimeMillis() - llmStarted,
                    error = e.message,
                )
                val human = AgentErrors.humanise(e.message)
                pointerOverlay.showStatus(human, 6000)
                speechOutput.speak(human)
                ActionLog.append("LLM ERROR: ${e.message}")
                session.end("llm_error", e.message)
                return
            }
            val toolUses = response.content.filter { it.type == "tool_use" }
            session.recordLlm(
                provider = response.provider ?: client.providerId,
                toolNames = toolUses.map { it.name.orEmpty() },
                durationMs = System.currentTimeMillis() - llmStarted,
            )
            ActionLog.append("LLM provider used: ${response.provider ?: client.providerId}")

            val assistantBlocks = mutableListOf<ChatModels.ContentBlock>()
            response.content.forEach { block ->
                when (block.type) {
                    "text" -> if (!block.text.isNullOrBlank()) {
                        assistantBlocks.add(ChatModels.ContentBlock.Text(block.text))
                    }
                    "tool_use" -> assistantBlocks.add(
                        ChatModels.ContentBlock.ToolUse(
                            id = block.id.orEmpty(),
                            name = block.name.orEmpty(),
                            input = block.input ?: buildJsonObject {},
                        ),
                    )
                }
            }
            messages.add(ChatModels.Message(role = "assistant", content = assistantBlocks))

            if (toolUses.isEmpty()) {
                val text = response.content.firstOrNull { it.type == "text" }?.text
                if (!forcedToolRetry) {
                    forcedToolRetry = true
                    session.recordNote("no_tools_retry", text ?: "empty tool list")
                    messages.add(
                        ChatModels.Message(
                            role = "user",
                            content = "You must call a tool this turn. " +
                                "If you need information from the user, call ask_user(question). " +
                                "If the task is finished, call done(summary). " +
                                "Do not reply with text only.",
                        ),
                    )
                    continue
                }
                pointerOverlay.showStatus(text?.take(80) ?: "No action")
                session.recordNote("no_tools", text ?: "empty tool list")
                session.end("completed_no_tools")
                return
            }
            forcedToolRetry = false

            val toolResults = mutableListOf<ChatModels.ContentBlock>()
            var finished = false

            for (tool in toolUses) {
                if (!coroutineContext.isActive) break
                steps++
                stepsTaken = steps
                val name = tool.name.orEmpty()
                val input = tool.input ?: JsonObject(emptyMap())
                val fpBefore = lastFp.ifBlank { fingerprint }
                val result = executor.execute(name, input)

                val actionKey = "$name:${input}"
                val stuckRepeat = progress.record(name, input.toString())
                val stuckNoChange = if (result.fingerprintAfter.isNotEmpty()) {
                    progress.recordFingerprint(result.fingerprintAfter, name)
                } else {
                    false
                }
                if (result.fingerprintAfter.isNotEmpty()) {
                    lastFp = result.fingerprintAfter
                }

                if (result.ok) routeTaken.add("$name ${input}".take(160))
                onProgress?.invoke(steps, describeStep(name, input))

                session.recordTool(
                    step = steps,
                    name = name,
                    args = input.toString(),
                    result = result.message,
                    ok = result.ok,
                    fingerprintBefore = fpBefore,
                    fingerprintAfter = result.fingerprintAfter.ifBlank { fpBefore },
                )

                toolResults.add(
                    ChatModels.ContentBlock.ToolResult(
                        toolUseId = tool.id.orEmpty(),
                        content = result.message,
                        isError = !result.ok,
                    ),
                )

                if (result.done) {
                    finished = true
                    session.end("done", result.message)
                    break
                }
                if (stuckRepeat) {
                    markStuck(
                        session,
                        speechOutput,
                        pointerOverlay,
                        "repeated identical action: $actionKey",
                    )
                    finished = true
                    break
                }
                if (stuckNoChange) {
                    markStuck(
                        session,
                        speechOutput,
                        pointerOverlay,
                        "no tree change for ${progress.noChangeStreak} actions",
                    )
                    finished = true
                    break
                }
                if (steps >= MAX_STEPS) break
            }

            messages.add(ChatModels.Message(role = "user", content = toolResults))
            pruneMessages(messages)

            if (finished) break
        }

        if (steps >= MAX_STEPS) {
            speechOutput.speak("Step limit reached")
            pointerOverlay.showStatus("Step limit reached")
            session.end("step_limit")
        } else if (runStore.readManifest(session.runId)?.status == "running") {
            session.end(if (!coroutineContext.isActive) "cancelled" else "completed")
        }
    }

    private fun pruneMessages(messages: MutableList<ChatModels.Message>) {
        if (messages.size <= 9) return
        val head = messages.first()
        val tail = messages.takeLast(8)
        messages.clear()
        messages.add(head)
        messages.addAll(tail)
    }

    private fun markStuck(
        session: RunSession,
        speechOutput: SpeechOutput,
        pointerOverlay: PointerOverlay,
        reason: String,
    ) {
        speechOutput.speak("I'm stuck")
        pointerOverlay.showStatus("I'm stuck")
        ActionLog.append("STUCK: $reason")
        session.recordNote("stuck", reason)
        session.end("stuck")
    }

    companion object {
        private const val TAG = "AgentOrchestrator"
        private val MAX_STEPS = com.drishti.BuildConfig.AGENT_MAX_STEPS
    }
}
