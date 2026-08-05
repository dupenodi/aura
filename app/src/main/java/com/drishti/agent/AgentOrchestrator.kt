package com.drishti.agent

import android.content.Context
import android.util.Log
import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.accessibility.TreeJson
import com.drishti.ai.ChatBackend
import com.drishti.ai.ChatModels
import com.drishti.ai.LlmRouter
import com.drishti.data.AuraPrefs
import com.drishti.data.SensitiveApps
import com.drishti.data.TaskHistory
import com.drishti.data.TaskOutcome
import com.drishti.data.TaskRecord
import com.drishti.overlay.PointerOverlay
import com.drishti.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Runs one "show me how" session: look at the screen, decide the single next step, point
 * at it, wait for the user, repeat.
 */
class AgentOrchestrator(
    private val appContext: Context,
    private val pointerOverlay: PointerOverlay,
    private val scope: CoroutineScope,
    private val client: ChatBackend = LlmRouter(),
) {
    private val speechOutput = SpeechOutput(appContext)
    private val prefs = AuraPrefs.get(appContext)
    private val history = TaskHistory.get(appContext)

    private var job: Job? = null

    /** Steps shown in the current session, so history is right even if it ends badly. */
    private var stepsTaken = 0

    /** Notifies the UI when a task starts/stops so the avatar can show activity. */
    var onRunStateChanged: ((running: Boolean) -> Unit)? = null

    fun cancel() {
        job?.cancel()
        pointerOverlay.hide()
        pointerOverlay.showStatus("Stopped")
    }

    fun runTask(task: String) {
        job?.cancel()
        // The agent loop must NOT run on Main — LLM calls and tree walks would ANR.
        job = scope.launch(Dispatchers.Default) {
            // Voice settings can change between runs; apply them before we speak.
            speechOutput.enabled = prefs.speakAloud.value
            speechOutput.setLanguage(prefs.language.value)
            onRunStateChanged?.invoke(true)

            stepsTaken = 0
            var outcome = Outcome(TaskOutcome.Completed, null, 0)
            try {
                outcome = runLoop(task)
            } catch (e: CancellationException) {
                outcome = Outcome(TaskOutcome.Cancelled, null, stepsTaken)
                pointerOverlay.hide()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent failed", e)
                val human = AgentErrors.humanise(e.message)
                outcome = Outcome(TaskOutcome.Stopped, human, stepsTaken)
                pointerOverlay.hide()
                pointerOverlay.showStatus(human, 6000)
                speechOutput.speak(human)
            } finally {
                history.add(
                    TaskRecord(
                        id = UUID.randomUUID().toString(),
                        task = task.trim(),
                        steps = outcome.steps,
                        outcome = outcome.result,
                        detail = outcome.detail,
                    ),
                )
                onRunStateChanged?.invoke(false)
            }
        }
    }

    private data class Outcome(
        val result: TaskOutcome,
        val detail: String?,
        val steps: Int,
    )

    private suspend fun runLoop(task: String): Outcome {
        ActionLog.clear()
        ActionLog.append("TASK: $task")
        speechOutput.speak("Let me show you")
        pointerOverlay.showStatus("Looking at your screen…")

        val executor = ToolExecutor(
            appContext = appContext,
            pointerOverlay = pointerOverlay,
            speechOutput = speechOutput,
        )

        val messages = mutableListOf(
            ChatModels.Message(
                role = "user",
                content = buildString {
                    appendLine("The user asked: $task")
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
        val progress = ProgressGuard()
        var forcedToolRetry = false

        while (coroutineContext.isActive && steps < MAX_STEPS) {
            val service = ScreenAgentAccessibilityService.getInstance()
            if (service == null) {
                return stop(
                    steps,
                    "I've lost permission to read the screen — turn Aura back on in " +
                        "Accessibility settings.",
                )
            }

            // Banking/health/password apps are never read. Enforced here rather than in
            // settings, because the privacy screen states it as unconditional.
            if (SensitiveApps.isSensitive(service.currentPackage())) {
                return stop(steps, "This looks like a banking app — I've stopped reading the screen.")
            }

            val roots = withContext(Dispatchers.Default) { service.captureAgentTree() }
            val pkg = service.currentPackage()
            val appLabel = DeviceContext.installedApps(appContext)
                .firstOrNull { it.packageName == pkg }?.label

            messages.add(
                ChatModels.Message(
                    role = "user",
                    content = buildString {
                        appendLine("They are looking at: ${appLabel ?: "unknown"} ($pkg)")
                        appendLine("What has happened so far:")
                        appendLine(ActionLog.recent(6).joinToString("\n").ifBlank { "(nothing yet)" })
                        appendLine()
                        appendLine("On screen now (indexed):")
                        appendLine(TreeJson.compactTreeString(roots))
                    },
                ),
            )

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
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed", e)
                ActionLog.append("LLM ERROR: ${e.message}")
                return stop(steps, AgentErrors.humanise(e.message))
            }

            val toolUses = response.content.filter { it.type == "tool_use" }
            messages.add(
                ChatModels.Message(
                    role = "assistant",
                    content = response.content.mapNotNull { block ->
                        when (block.type) {
                            "text" -> block.text?.takeIf { it.isNotBlank() }
                                ?.let { ChatModels.ContentBlock.Text(it) }
                            "tool_use" -> ChatModels.ContentBlock.ToolUse(
                                id = block.id.orEmpty(),
                                name = block.name.orEmpty(),
                                input = block.input ?: buildJsonObject {},
                            )
                            else -> null
                        }
                    },
                ),
            )

            if (toolUses.isEmpty()) {
                if (!forcedToolRetry) {
                    forcedToolRetry = true
                    messages.add(
                        ChatModels.Message(
                            role = "user",
                            content = "You must call a tool this turn — point_at for the next " +
                                "step, or done(summary) if they have got there. Never reply " +
                                "with text alone.",
                        ),
                    )
                    continue
                }
                return stop(steps, "I'm not sure what to show you next")
            }
            forcedToolRetry = false

            // One step at a time is the whole model here: anything past the first tool
            // was decided against a screen the user has not reached yet.
            val tool = toolUses.first()
            val name = tool.name.orEmpty()
            val input = tool.input ?: JsonObject(emptyMap())

            steps++
            stepsTaken = steps
            val result = executor.execute(name, input)

            messages.add(
                ChatModels.Message(
                    role = "user",
                    content = listOf(
                        ChatModels.ContentBlock.ToolResult(
                            toolUseId = tool.id.orEmpty(),
                            content = result.message,
                            isError = !result.ok,
                        ),
                    ),
                ),
            )
            pruneMessages(messages)

            if (result.done) {
                // A step the user never did ends the session too — but honestly, so the
                // history doesn't claim they finished something they walked away from.
                return if (result.ok) {
                    Outcome(TaskOutcome.Completed, null, steps)
                } else {
                    Outcome(TaskOutcome.Stopped, "Left you partway — you didn't take the step", steps)
                }
            }

            if (progress.record(name, input.toString())) {
                ActionLog.append("STUCK: repeated $name")
                return stop(steps, "I've shown you that step already — let's stop there")
            }
        }

        if (steps >= MAX_STEPS) {
            return stop(steps, "That's as far as I can take you for now")
        }
        return Outcome(TaskOutcome.Cancelled, null, steps)
    }

    /** Ends the run with something the user can actually understand. */
    private fun stop(steps: Int, message: String): Outcome {
        pointerOverlay.hide()
        pointerOverlay.showStatus(message, 6000)
        speechOutput.speak(message)
        return Outcome(TaskOutcome.Stopped, message, steps)
    }

    private fun pruneMessages(messages: MutableList<ChatModels.Message>) {
        if (messages.size <= 9) return
        val head = messages.first()
        val tail = messages.takeLast(8)
        messages.clear()
        messages.add(head)
        messages.addAll(tail)
    }

    companion object {
        private const val TAG = "AgentOrchestrator"
        private val MAX_STEPS = com.drishti.BuildConfig.AGENT_MAX_STEPS
    }
}
