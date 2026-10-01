package com.drishti.agent

import android.content.Context
import android.util.Log
import com.drishti.ai.Llm
import com.drishti.core.agent.GuideConfig
import com.drishti.core.agent.GuideSession
import com.drishti.core.agent.GuideUi
import com.drishti.core.agent.Language
import com.drishti.core.agent.Outcome
import com.drishti.core.agent.ScreenshotPolicy
import com.drishti.core.agent.StepPlanner
import com.drishti.core.agent.Voice
import com.drishti.data.AuraPrefs
import com.drishti.data.SessionRecorder
import com.drishti.data.TaskHistory
import com.drishti.data.TaskOutcome
import com.drishti.data.TaskRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Runs one guided session at a time on the real phone and keeps the user-facing history.
 *
 * Everything that decides what to show and when lives in [GuideSession] (in :core, tested
 * against the simulated Pixel); this class only connects it to the accessibility service,
 * the overlay, the voice and the history list.
 */
class GuideRunner(
    private val context: Context,
    private val ui: GuideUi,
    private val voice: Voice,
    private val scope: CoroutineScope,
) {
    private val prefs = AuraPrefs.get(context)
    private val history = TaskHistory.get(context)
    private val device by lazy { AndroidDevice(context) }

    /** Built once: the client keeps a warm connection and the prompt cache stays hot. */
    private val planner: StepPlanner by lazy { Llm.planner() }

    private var job: Job? = null

    @Volatile
    private var session: GuideSession? = null

    /** Notifies the overlay when a session starts or ends so the handle and glow can show activity. */
    var onRunStateChanged: ((running: Boolean) -> Unit)? = null

    val isPaused: Boolean get() = session?.paused == true

    /** Opens the model connection early — while they are still speaking, for instance. */
    fun warmUp() {
        scope.launch { runCatching { planner.warmUp() } }
    }

    /**
     * Starts a session. [spoken] is the language speech recognition heard, if it said.
     * Under automatic language: what they spoke, else the script they typed in, else the
     * last language they used.
     */
    fun runTask(task: String, spoken: Language? = null) {
        job?.cancel()
        val language = if (prefs.autoLanguage.value) {
            (spoken ?: Language.detect(task))?.also { remember(it) } ?: prefs.language.value.core
        } else {
            prefs.language.value.core
        }
        job = scope.launch {
            onRunStateChanged?.invoke(true)
            val recorder = SessionRecorder(context, task)
            recorder.record("language", mapOf("language" to language.name, "auto" to prefs.autoLanguage.value))
            val s = GuideSession(
                task = task,
                language = language,
                device = device,
                planner = planner,
                ui = ui,
                voice = voice,
                config = GuideConfig(
                    screenshots = if (prefs.useScreenshots.value) ScreenshotPolicy.Auto else ScreenshotPolicy.Never,
                ),
                trace = recorder::record,
            )
            session = s
            var record = TaskRecord(UUID.randomUUID().toString(), task.trim(), 0, TaskOutcome.Cancelled)
            try {
                val result = s.run()
                record = record.copy(
                    steps = result.steps,
                    outcome = when (result.outcome) {
                        Outcome.Completed -> TaskOutcome.Completed
                        Outcome.Stopped -> TaskOutcome.Stopped
                        Outcome.Cancelled -> TaskOutcome.Cancelled
                    },
                    detail = result.message.takeIf { result.outcome != Outcome.Completed },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "session crashed", e)
                record = record.copy(outcome = TaskOutcome.Stopped, detail = "Something went wrong")
                recorder.record("crash", mapOf("error" to e.toString()))
                ui.finished("Something went wrong on my side. Nothing was changed.", false)
            } finally {
                session = null
                history.add(record)
                onRunStateChanged?.invoke(false)
            }
        }
    }

    /** Typed romanised text has no script to go by; the last spoken language is the best guess. */
    private fun remember(language: Language) {
        prefs.setLanguage(com.drishti.voice.AuraLanguage.fromTag(language.tag))
    }

    /** The handle was tapped while the session waits for them: carry on from where they are. */
    fun resume(): Boolean {
        val s = session ?: return false
        if (!s.paused) return false
        s.resume()
        return true
    }

    fun cancel() {
        job?.cancel()
        voice.stop()
        ui.hide()
    }

    companion object {
        private const val TAG = "GuideRunner"
    }
}
