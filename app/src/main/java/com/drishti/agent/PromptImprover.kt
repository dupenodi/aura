package com.drishti.agent

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drishti.ai.ChatBackend
import com.drishti.ai.ChatModels
import com.drishti.ai.LlmRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Rewrites what the user actually said into a brief the agent can execute.
 *
 * People speak in shorthand ("turn off the sounds", "send mum the photos"). The main
 * agent loop then burns turns — and wall-clock seconds — discovering which app that
 * means and where the setting lives. A small, cheap model does that translation once,
 * up front, for a fraction of the cost of a single wasted agent step.
 *
 * This is strictly an optimisation: every failure path returns the user's original
 * words, so a slow or unavailable fast model can never block a task.
 */
class PromptImprover(
    private val appContext: Context,
    private val router: LlmRouter = LlmRouter(),
) {

    data class Improved(
        /** The task the agent should run. Falls back to the raw input. */
        val task: String,
        /** Optional hint about where to start, folded into the task text. */
        val plan: String? = null,
        /** Set when the request is too ambiguous to act on safely. */
        val clarifyingQuestion: String? = null,
        /** False when we fell back to the user's raw words. */
        val improved: Boolean = false,
    )

    suspend fun improve(rawTask: String): Improved {
        val trimmed = rawTask.trim()
        if (trimmed.isBlank()) return Improved(task = rawTask)

        // Very short or already-explicit requests aren't worth a round trip.
        if (trimmed.length < MIN_LENGTH) return Improved(task = trimmed)

        val backend = router.fastBackend() ?: return Improved(task = trimmed)

        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { callModel(backend, trimmed) }
                .onFailure { Log.w(TAG, "Prompt improve failed: ${it.message}") }
                .getOrNull()
        } ?: Improved(task = trimmed).also {
            Log.i(TAG, "Prompt improve skipped (timeout or error); using raw task")
        }
    }

    private suspend fun callModel(backend: ChatBackend, task: String): Improved =
        withContext(Dispatchers.IO) {
            val response = backend.createMessage(
                system = SYSTEM,
                messages = listOf(
                    ChatModels.Message(
                        role = "user",
                        content = buildString {
                            appendLine("Installed apps (name → package):")
                            appendLine(DeviceContext.installedAppsBlock(appContext))
                            appendLine()
                            appendLine("User request: \"$task\"")
                        },
                    ),
                ),
                tools = emptyList(),
                maxTokens = 400,
            )

            val text = response.content
                .firstOrNull { it.type == "text" }
                ?.text
                .orEmpty()
                .trim()

            parse(text, fallback = task)
        }

    private fun parse(raw: String, fallback: String): Improved {
        // Small models like to wrap JSON in prose or code fences; take the outermost object.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Improved(task = fallback)

        return runCatching {
            val json = JSONObject(raw.substring(start, end + 1))
            val question = json.optString("clarifying_question").takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            }
            if (question != null) {
                return@runCatching Improved(
                    task = fallback,
                    clarifyingQuestion = question,
                    improved = true,
                )
            }

            val rewritten = json.optString("task").takeIf { it.isNotBlank() } ?: fallback
            val plan = json.optString("plan").takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            }
            Improved(
                task = if (plan != null) "$rewritten\n\nSuggested route: $plan" else rewritten,
                plan = plan,
                improved = true,
            )
        }.getOrElse { Improved(task = fallback) }
    }

    companion object {
        private const val TAG = "PromptImprover"
        private const val TIMEOUT_MS = 6_000L
        private const val MIN_LENGTH = 12
        private const val MAX_APPS = 60

        private val SYSTEM = """
You rewrite a phone user's spoken request into a precise task for an Android agent that
controls the screen through the accessibility service.

Return ONLY a JSON object, no prose and no code fences:
{"task": "...", "plan": "...", "clarifying_question": null}

Rules for "task":
- Keep the user's actual intent. Never invent a different goal, recipient, or amount.
- Name the concrete app and, where you are confident, the exact package name from the list.
- Turn vague phrasing into the operation as it is actually labelled on Android
  (e.g. "make text bigger" -> "increase Display size and font size in Settings").
- One or two sentences. Imperative. No pleasantries, no restating these rules.

Rules for "plan":
- A short route hint only when you are confident, e.g. "Settings > Sound & vibration".
- Use null when you would be guessing. A wrong route costs more than no route.

Rules for "clarifying_question":
- Use a question ONLY when the request cannot be acted on safely without an answer:
  a missing recipient, an ambiguous choice between real alternatives, or an amount.
- Never ask about things you can discover by looking at the screen.
- Otherwise null.
""".trimIndent()
    }
}
