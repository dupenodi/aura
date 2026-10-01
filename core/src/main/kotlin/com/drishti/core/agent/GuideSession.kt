package com.drishti.core.agent

import com.drishti.core.llm.LlmException
import com.drishti.core.screen.Bounds
import com.drishti.core.screen.EncodedScreen
import com.drishti.core.screen.Screen
import com.drishti.core.screen.ScreenEncoder
import com.drishti.core.screen.Target
import com.drishti.core.screen.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class GuideConfig(
    val maxSteps: Int = 25,
    /** The screen counts as settled once nothing has redrawn for this long… */
    val settleQuietMs: Long = 250,
    /** …or after this long regardless (a spinner never settles). */
    val settleMaxMs: Long = 1_500,
    /** Say the instruction again. */
    val repeatAfterMs: Long = 12_000,
    /** Make the ring bigger and reassure. */
    val emphasizeAfterMs: Long = 30_000,
    /** Stop waiting and pause; the session resumes from the orb. */
    val pauseAfterMs: Long = 60_000,
    /** A paused session that nobody comes back to ends here. */
    val pausedGiveUpMs: Long = 10 * 60_000,
    /** A typing step is done once they have stopped typing this long. */
    val typeIdleMs: Long = 3_000,
    /** How often to re-read the screen when the platform reports nothing useful. */
    val pollMs: Long = 1_000,
    val screenshots: ScreenshotPolicy = ScreenshotPolicy.Auto,
    val maxConsecutiveWaits: Int = 3,
)

enum class ScreenshotPolicy {
    /** When the tree can't describe the screen, or the last step went wrong. */
    Auto,
    Always,
    Never,
}

enum class Outcome { Completed, Stopped, Cancelled }

data class StepMetrics(
    val action: Action?,
    val model: String,
    /** Waiting for the screen to settle, reading it, and the model deciding. */
    val settleMs: Long,
    val observeMs: Long,
    val planMs: Long,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val screenshot: Boolean,
    val result: String,
)

data class SessionResult(
    val outcome: Outcome,
    /** Steps shown to the user. */
    val steps: Int,
    /** What was said at the end. */
    val message: String?,
    val metrics: List<StepMetrics>,
    val history: List<HistoryEntry>,
)

/**
 * One "show me how" session: observe → decide → show → watch the user → repeat.
 *
 * Nothing here touches the phone. A step is done only when the user does it, and that is
 * read from what the platform reports — the click on the node we pointed at — rather than
 * guessed from the screen changing. When they press something else, Aura carries on from
 * wherever they ended up; when they hesitate, it repeats, reassures, and then pauses
 * instead of throwing their progress away.
 */
class GuideSession(
    private val task: String,
    private val language: Language,
    private val device: Device,
    private val planner: StepPlanner,
    private val ui: GuideUi,
    private val voice: Voice,
    private val config: GuideConfig = GuideConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val trace: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    private val resumeSignal = Channel<Unit>(Channel.CONFLATED)
    private val inbox = Channel<UiEvent>(Channel.UNLIMITED)

    @Volatile
    private var lastActivityAt = Long.MIN_VALUE / 2

    @Volatile
    var paused = false
        private set

    /** The orb was tapped while paused. */
    fun resume() {
        resumeSignal.trySend(Unit)
    }

    private val history = mutableListOf<HistoryEntry>()
    private val metrics = mutableListOf<StepMetrics>()
    private var stepsShown = 0

    suspend fun run(): SessionResult = coroutineScope {
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            device.events.collect { ev ->
                if (ev.pkg == device.info.ownPackage) return@collect
                if (!ev.isInteraction || ev.type == UiEvent.Type.SCROLLED) lastActivityAt = clock()
                inbox.trySend(ev)
            }
        }
        // Opens the model connection while the first screen is being read.
        launch { runCatching { planner.warmUp() } }
        try {
            loop()
        } catch (e: CancellationException) {
            trace("end", mapOf("outcome" to "cancelled"))
            throw e
        } finally {
            listener.cancel()
            ui.hide()
        }
    }

    private suspend fun loop(): SessionResult {
        ui.thinking(Phrases.get(Phrase.Looking, language))

        var note: String? = null
        var lastProgress: String? = null
        var hard = false
        var problems = 0
        var waits = 0
        val repeats = HashMap<String, Int>()

        while (true) {
            if (stepsShown >= config.maxSteps) return end(Outcome.Stopped, Phrase.TooManySteps)

            val settleMs = timed { settle() }
            val observeStart = clock()
            val observed = observeWithRetry()
                ?: return end(Outcome.Stopped, if (device.canSeeScreen()) Phrase.NotSure else Phrase.LostPermission)
            if (SensitiveApps.isSensitive(observed.screen.pkg)) return end(Outcome.Stopped, Phrase.SensitiveApp)

            val wantShot = when (config.screenshots) {
                ScreenshotPolicy.Always -> true
                ScreenshotPolicy.Never -> false
                ScreenshotPolicy.Auto -> observed.encoded.sparse || problems > 0
            }
            val shot = if (wantShot) runCatching { device.screenshot() }.getOrNull() else null
            val observeMs = clock() - observeStart

            val planned = try {
                planWithRetry(observed, note, lastProgress, shot, hard)
            } catch (e: LlmException) {
                trace("llm_error", mapOf("kind" to e.kind.name, "message" to e.message))
                return end(Outcome.Stopped, e.phrase())
            }
            val step = planned.step
            trace(
                "plan",
                mapOf(
                    "model" to planned.model, "ms" to planned.latencyMs, "step" to step,
                    "in" to planned.usage.inputTokens, "cached" to planned.usage.cachedTokens,
                    "out" to planned.usage.outputTokens, "screen" to observed.encoded.text, "shot" to (shot != null),
                ),
            )

            fun record(result: String) {
                metrics += StepMetrics(
                    action = step?.action, model = planned.model, settleMs = settleMs, observeMs = observeMs,
                    planMs = planned.latencyMs, inputTokens = planned.usage.inputTokens,
                    cachedTokens = planned.usage.cachedTokens, outputTokens = planned.usage.outputTokens,
                    screenshot = shot != null, result = result,
                )
            }

            if (step == null) {
                record("no step")
                problems++
                if (problems >= MAX_PROBLEMS) return end(Outcome.Stopped, Phrase.NotSure)
                note = "You must call next_step with a valid action."
                hard = true
                continue
            }
            lastProgress = step.progress.takeIf { it.isNotBlank() } ?: lastProgress

            val resolution = StepResolver.resolve(
                step, observed.encoded, observed.screen.width, observed.screen.height, device::resolveApp,
            )
            val resolved = resolution.getOrNull()
            if (resolved == null) {
                val err = resolution.exceptionOrNull()
                val problem = (err as? ProblemException)?.problem
                record("invalid: ${problem?.note ?: err?.message}")
                problems++
                if (problems >= MAX_PROBLEMS) return end(Outcome.Stopped, Phrase.NotSure)
                note = problem?.note ?: err?.message
                hard = true
                continue
            }

            when (step.action) {
                Action.Done -> {
                    record("done")
                    return end(Outcome.Completed, step.say.ifBlank { null }, success = true)
                }
                Action.Cannot -> {
                    record("cannot")
                    return end(Outcome.Stopped, step.say.ifBlank { Phrases.get(Phrase.NotSure, language) })
                }
                Action.Wait -> {
                    record("wait")
                    waits++
                    if (waits > config.maxConsecutiveWaits) return end(Outcome.Stopped, Phrase.NotSure)
                    if (step.say.isNotBlank()) {
                        ui.thinking(step.say)
                        voice.say(step.say, language)
                    }
                    settleFrom = clock()
                    waitForChange(observed.encoded, WAIT_STEP_MS)
                    note = null
                    continue
                }
                else -> Unit
            }
            waits = 0

            // The same step on the same screen a third time means the model is stuck. One
            // escalation to the stronger model with that spelled out, then stop.
            val repeatKey = "${observed.encoded.signature.hashCode()}|${step.action}|${resolved.target?.label ?: step.direction}"
            val seen = (repeats[repeatKey] ?: 0) + 1
            repeats[repeatKey] = seen
            if (seen >= 3) {
                if (seen >= 4 || hard) return end(Outcome.Stopped, Phrase.GoingInCircles)
                record("repeat")
                note = "You have shown this exact step on this screen twice already and it didn't help. Choose a different way."
                hard = true
                continue
            }

            problems = 0
            stepsShown++
            val shown = show(resolved)
            val outcome = watch(resolved, observed)
            record(outcome.summary)
            trace("outcome", mapOf("result" to outcome.summary))
            history += HistoryEntry(where(observed), shown, outcome.summary)

            note = outcome.note
            hard = outcome is WatchOutcome.OffTarget && history.takeLast(2).count { it.outcome.startsWith("they pressed") } >= 2

            if (outcome is WatchOutcome.Hesitated) {
                if (!pauseUntilResumed()) return end(Outcome.Stopped, null as String?, success = false, speak = false)
                note = "They paused for a while and have come back. Check where they are now."
            }
        }
    }

    // ---- Observe ----------------------------------------------------------------------------

    private class Observed(val screen: Screen, val encoded: EncodedScreen)

    private suspend fun observe(): Observed? {
        val screen = device.snapshot() ?: return null
        val labelled = screen.copy(
            appLabel = screen.appLabel
                ?: HOME_SCREEN.takeIf { screen.pkg == device.info.launcherPackage }
                ?: device.installedApps().firstOrNull { it.pkg == screen.pkg }?.label,
        )
        return Observed(labelled, ScreenEncoder.encode(labelled))
    }

    /** A read can fail mid-transition; only repeated failure means we can't see. */
    private suspend fun observeWithRetry(): Observed? {
        repeat(OBSERVE_ATTEMPTS) { attempt ->
            observe()?.let { return it }
            if (!device.canSeeScreen()) return null
            delay(OBSERVE_RETRY_MS * (attempt + 1))
        }
        return null
    }

    private fun where(o: Observed): String {
        val app = o.screen.appLabel ?: o.screen.pkg
        return o.encoded.title?.let { "$app › $it" } ?: app
    }

    /** When the user last acted: what they did may not have started redrawing yet. */
    private var settleFrom = Long.MIN_VALUE / 2

    /** Waits until nothing has redrawn for a moment, so we read a finished screen. */
    private suspend fun settle() {
        val start = clock()
        while (true) {
            val now = clock()
            val quietFor = now - maxOf(lastActivityAt, settleFrom)
            if (quietFor >= config.settleQuietMs || now - start >= config.settleMaxMs) return
            delay((config.settleQuietMs - quietFor).coerceIn(10, 50))
        }
    }

    private suspend fun planWithRetry(
        observed: Observed,
        note: String?,
        lastProgress: String?,
        shot: Screenshot?,
        hard: Boolean,
    ): Planned {
        val input = PlanInput(
            task = task, language = language, screen = observed.encoded, history = history.toList(),
            lastProgress = lastProgress, note = note, screenshot = shot, apps = device.installedApps(),
            device = device.info, hard = hard,
        )
        return try {
            planner.plan(input)
        } catch (e: LlmException) {
            if (!e.retryable) throw e
            trace("llm_retry", mapOf("kind" to e.kind.name))
            delay(RETRY_DELAY_MS)
            planner.plan(input.copy(hard = !hard))
        }
    }

    // ---- Show -------------------------------------------------------------------------------

    /** Puts the step on screen and returns how it reads in the history. */
    private suspend fun show(resolved: ResolvedStep): String {
        val step = resolved.step
        // Lists and keyboards move things between reading the screen and drawing the ring.
        val live = resolved.target?.nodeKey?.takeIf { it >= 0 }?.let { device.liveBounds(it) }
        val bounds = live?.takeIf { !it.isEmpty } ?: resolved.bounds
        drainInbox()
        ui.show(ShownStep(step.action, step.say, bounds, step.direction, step.text, resolved.app?.pkg))
        voice.say(step.say, language)
        currentRing = bounds
        trace("show", mapOf("step" to step, "bounds" to bounds?.toString()))
        return describe(resolved)
    }

    private var currentRing: Bounds? = null

    private fun describe(r: ResolvedStep): String {
        val s = r.step
        val what = r.target?.label?.let { "\"$it\"" }
        return when (s.action) {
            Action.Tap -> "tap ${what ?: "the point shown"}"
            Action.LongPress -> "press and hold ${what ?: "the point shown"}"
            Action.Type -> "type \"${s.text}\"" + (what?.let { " into $it" } ?: "")
            Action.Scroll -> "scroll ${s.direction?.name?.lowercase() ?: "down"}"
            Action.Back -> "press Back"
            Action.Home -> "go to the home screen"
            Action.OpenApp -> "open ${r.app?.label ?: s.app}"
            else -> s.action.wire
        }
    }

    // ---- Watch ------------------------------------------------------------------------------

    private sealed class WatchOutcome(val summary: String, val note: String?) {
        class OnTarget : WatchOutcome("done", null)
        class Moved : WatchOutcome("the screen changed", null)
        class OffTarget(pressed: String) : WatchOutcome(
            "they pressed $pressed instead",
            "They pressed $pressed instead of what you showed. Guide them on from where they are now.",
        )
        class Typed(text: String) : WatchOutcome("they typed \"${text.take(40)}\"", null)
        class Scrolled : WatchOutcome("they scrolled", null)
        class Hesitated : WatchOutcome("they didn't do it and paused", null)
    }

    private suspend fun watch(resolved: ResolvedStep, before: Observed): WatchOutcome {
        val step = resolved.step
        val shownAt = clock()
        var level = 0
        var lastPoll = shownAt
        var typed: String? = null
        var typedAt = 0L

        while (true) {
            val now = clock()
            val elapsed = now - shownAt
            if (level == 0 && elapsed >= config.repeatAfterMs) {
                voice.say(step.say, language)
                level = 1
            }
            if (level == 1 && elapsed >= config.emphasizeAfterMs) {
                ui.emphasize()
                voice.say(Phrases.get(Phrase.StillThere, language), language)
                level = 2
            }
            if (elapsed >= config.pauseAfterMs) {
                ui.hide()
                return WatchOutcome.Hesitated()
            }
            typed?.let { t ->
                if (now - typedAt >= config.typeIdleMs) return finishStep(WatchOutcome.Typed(t))
            }

            val ev = withTimeoutOrNull(config.pollMs) { inbox.receive() }
            if (ev == null) {
                lastPoll = clock()
                checkScreen(resolved, before)?.let { return finishStep(it) }
                continue
            }

            when (ev.type) {
                UiEvent.Type.CLICK, UiEvent.Type.LONG_CLICK -> {
                    val hit = matchesTarget(ev, resolved)
                    if (hit == true) return finishStep(WatchOutcome.OnTarget(), interrupt = true)
                    // Pressed something else. If it took them somewhere, follow; if it did
                    // nothing (a label, an empty area), keep waiting with the ring up.
                    settle()
                    val after = observe() ?: continue
                    if (ScreenEncoder.movedOn(before.encoded, after.encoded)) {
                        return finishStep(
                            if (hit == null) WatchOutcome.Moved() else WatchOutcome.OffTarget(pressedLabel(ev)),
                            interrupt = true,
                        )
                    }
                }

                UiEvent.Type.TEXT_CHANGED -> if (step.action == Action.Type) {
                    typed = ev.text.orEmpty()
                    typedAt = clock()
                    val want = step.text.orEmpty().trim()
                    if (want.isNotEmpty() && typed.trim().equals(want, ignoreCase = true)) {
                        return finishStep(WatchOutcome.Typed(typed), interrupt = true)
                    }
                }

                UiEvent.Type.FOCUSED -> if (step.action == Action.Tap && resolved.target?.role == "field" &&
                    matchesTarget(ev, resolved) == true
                ) {
                    return finishStep(WatchOutcome.OnTarget(), interrupt = true)
                }

                UiEvent.Type.SCROLLED -> if (step.action == Action.Scroll) {
                    return finishStep(WatchOutcome.Scrolled(), interrupt = true)
                }

                UiEvent.Type.WINDOW_STATE, UiEvent.Type.WINDOWS_CHANGED, UiEvent.Type.CONTENT_CHANGED,
                UiEvent.Type.SELECTED,
                -> if (clock() - lastPoll >= config.pollMs / 2 || ev.type == UiEvent.Type.WINDOW_STATE) {
                    lastPoll = clock()
                    checkScreen(resolved, before)?.let { return finishStep(it) }
                }

                else -> Unit
            }
        }
    }

    /**
     * Reads the screen and decides whether the step happened without a click to say so:
     * Compose apps, gestures (back, home), a toggle flipping, a keyboard opening on a field.
     */
    private suspend fun checkScreen(resolved: ResolvedStep, before: Observed): WatchOutcome? {
        settle()
        val after = observe() ?: return null
        val step = resolved.step
        val target = resolved.target

        if (target != null && toggled(target, after.encoded)) return WatchOutcome.OnTarget()
        if (step.action == Action.Tap && target?.role == "field" &&
            after.screen.keyboardVisible && !before.screen.keyboardVisible
        ) {
            return WatchOutcome.OnTarget()
        }
        if (step.action == Action.OpenApp && resolved.app != null && after.screen.pkg == resolved.app.pkg) {
            return WatchOutcome.OnTarget()
        }
        if (!ScreenEncoder.movedOn(before.encoded, after.encoded)) return null
        return when (step.action) {
            Action.Scroll -> WatchOutcome.Scrolled()
            Action.Back, Action.Home -> WatchOutcome.OnTarget()
            else -> WatchOutcome.Moved()
        }
    }

    /** The thing we pointed at is still there and its on/off state flipped. */
    private fun toggled(target: Target, after: EncodedScreen): Boolean {
        val before = target.states.firstOrNull { it == "on" || it == "off" } ?: return false
        val now = after.targets.values.firstOrNull { it.label == target.label && it.role == target.role }
            ?.states?.firstOrNull { it == "on" || it == "off" } ?: return false
        return now != before
    }

    /**
     * True if the click landed on what we ringed (or something inside it, or the row that
     * contains it), false if it landed elsewhere, null if we can't tell.
     */
    private fun matchesTarget(ev: UiEvent, resolved: ResolvedStep): Boolean? {
        val ring = currentRing ?: resolved.bounds ?: return null
        val eb = ev.bounds
        if (eb != null && !eb.isEmpty) {
            if (ring.iou(eb) > 0.5f) return true
            if (ring.contains(eb.cx, eb.cy) && eb.area <= ring.area * 3 / 2) return true
            if (eb.contains(ring.cx, ring.cy) && ring.area * 4 >= eb.area) return true
            return false
        }
        val words = listOfNotNull(ev.text, ev.desc).joinToString(" ").trim().lowercase()
        val label = resolved.target?.label?.lowercase() ?: return null
        if (words.isEmpty()) return null
        return words.contains(label) || label.contains(words)
    }

    private fun pressedLabel(ev: UiEvent): String =
        listOfNotNull(ev.text, ev.desc).firstOrNull { it.isNotBlank() }?.let { "\"${it.take(40)}\"" } ?: "something else"

    private suspend fun finishStep(outcome: WatchOutcome, @Suppress("UNUSED_PARAMETER") interrupt: Boolean = false): WatchOutcome {
        // The moment they act, stop talking and take the ring away: the next step is coming.
        settleFrom = clock()
        voice.stop()
        ui.hide()
        ui.thinking(null)
        currentRing = null
        return outcome
    }

    private suspend fun waitForChange(before: EncodedScreen, maxMs: Long) {
        val start = clock()
        while (clock() - start < maxMs) {
            withTimeoutOrNull(config.pollMs) { inbox.receive() }
            settle()
            val now = observe() ?: return
            if (ScreenEncoder.movedOn(before, now.encoded)) return
        }
    }

    private suspend fun pauseUntilResumed(): Boolean {
        paused = true
        val message = Phrases.get(Phrase.Paused, language)
        ui.paused(message)
        voice.say(message, language)
        trace("paused", emptyMap())
        val resumed = withTimeoutOrNull(config.pausedGiveUpMs) { resumeSignal.receive() } != null
        paused = false
        if (resumed) {
            ui.thinking(Phrases.get(Phrase.Resuming, language))
            voice.say(Phrases.get(Phrase.Resuming, language), language)
        }
        return resumed
    }

    private fun drainInbox() {
        while (inbox.tryReceive().isSuccess) Unit
    }

    // ---- End --------------------------------------------------------------------------------

    private fun end(outcome: Outcome, phrase: Phrase, success: Boolean = false, speak: Boolean = true): SessionResult =
        end(outcome, Phrases.get(phrase, language), success, speak)

    private fun end(outcome: Outcome, message: String?, success: Boolean = false, speak: Boolean = true): SessionResult {
        ui.hide()
        val text = message ?: ""
        ui.finished(text, success)
        if (speak && text.isNotBlank()) voice.say(text, language)
        trace("end", mapOf("outcome" to outcome.name, "message" to text, "steps" to stepsShown))
        return SessionResult(outcome, stepsShown, text.ifBlank { null }, metrics.toList(), history.toList())
    }

    private suspend inline fun timed(block: () -> Unit): Long {
        val s = clock()
        block()
        return clock() - s
    }

    companion object {
        private const val MAX_PROBLEMS = 3
        private const val HOME_SCREEN = "Home screen"
        private const val RETRY_DELAY_MS = 400L
        private const val WAIT_STEP_MS = 6_000L
        private const val OBSERVE_ATTEMPTS = 4
        private const val OBSERVE_RETRY_MS = 150L
    }
}
