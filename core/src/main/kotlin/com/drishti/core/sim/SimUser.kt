package com.drishti.core.sim

import com.drishti.core.agent.Action
import com.drishti.core.agent.Direction
import com.drishti.core.agent.GuideUi
import com.drishti.core.agent.Language
import com.drishti.core.agent.ShownStep
import com.drishti.core.agent.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The person holding the simulated phone. They read the ring and do what it says — or,
 * depending on [Behavior], hesitate, press the wrong thing, or open apps on their own.
 */
class SimUser(
    private val phone: SimPhone,
    private val scope: CoroutineScope,
    private val behavior: Behavior = Behavior(),
) : GuideUi {

    data class Behavior(
        /** Time to find the ring and press it. */
        val reactionMs: Long = 1_200,
        /** Step numbers (1-based) they don't act on at all. */
        val ignoreSteps: Set<Int> = emptySet(),
        /** Step numbers where their finger lands on the row above the ring. */
        val wrongTapSteps: Set<Int> = emptySet(),
        /** "Open WhatsApp" with no icon on screen: they find it themselves. */
        val opensAppsUnaided: Boolean = true,
        val typeDelayMs: Long = 800,
    )

    val shown = mutableListOf<ShownStep>()
    var emphasized = 0
        private set
    var pausedCount = 0
        private set
    var finishedMessage: String? = null
        private set
    var finishedSuccess: Boolean? = null
        private set
    val thinkingLines = mutableListOf<String?>()

    /** When each step was shown and when the user acted on it, in the session's clock. */
    val actedAt = mutableListOf<Long>()
    var clock: () -> Long = { 0L }

    private var job: Job? = null

    override fun thinking(text: String?) {
        thinkingLines += text
    }

    override fun show(step: ShownStep) {
        shown += step
        val n = shown.size
        job?.cancel()
        job = scope.launch {
            delay(behavior.reactionMs)
            if (n in behavior.ignoreSteps) return@launch
            act(step, wrong = n in behavior.wrongTapSteps)
        }
    }

    override fun emphasize() {
        emphasized++
    }

    override fun hide() = Unit

    override fun paused(message: String) {
        pausedCount++
    }

    override fun finished(message: String, success: Boolean) {
        finishedMessage = message
        finishedSuccess = success
        job?.cancel()
    }

    private suspend fun act(step: ShownStep, wrong: Boolean) {
        actedAt += clock()
        val b = step.bounds
        when (step.action) {
            Action.Tap, Action.LongPress -> if (b != null) {
                if (wrong) phone.tapAt(b.cx, b.t - 60) else phone.tapAt(b.cx, b.cy)
            }

            Action.Type -> {
                if (b != null && phone.state.focusedField == null) {
                    phone.tapAt(b.cx, b.cy)
                    delay(300)
                }
                delay(behavior.typeDelayMs)
                phone.type(step.text.orEmpty())
            }

            Action.Scroll -> phone.scroll(down = step.direction != Direction.Up)
            Action.Back -> phone.back()
            Action.Home -> phone.home()
            Action.OpenApp -> when {
                b != null -> phone.tapAt(b.cx, b.cy)
                behavior.opensAppsUnaided && step.app != null -> phone.launch(step.app)
            }

            else -> Unit
        }
    }
}

/** Keeps what Aura said, for assertions and transcripts. */
class RecordingVoice : Voice {
    val said = mutableListOf<String>()
    var stops = 0
        private set

    override fun say(text: String, language: Language) {
        said += text
    }

    override fun stop() {
        stops++
    }
}
