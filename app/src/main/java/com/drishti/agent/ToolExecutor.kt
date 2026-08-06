package com.drishti.agent

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import com.drishti.accessibility.ElementNode
import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.accessibility.TreeJson
import com.drishti.overlay.PointerOverlay
import com.drishti.voice.SpeechOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the model's chosen step into guidance: the cursor moves to one place on screen,
 * Aura says what to do there, and then it waits for the user's own finger.
 *
 * Nothing in here touches the phone. The only way a step "succeeds" is that the user did
 * it themselves and the screen moved on.
 */
class ToolExecutor(
    private val appContext: Context,
    private val pointerOverlay: PointerOverlay,
    private val speechOutput: SpeechOutput,
) {
    data class Result(
        /** True when the user followed the instruction and the screen moved on. */
        val ok: Boolean,
        val message: String,
        /** True when the session is over, whether they got there or gave up. */
        val done: Boolean = false,
    )

    suspend fun execute(name: String, input: JsonObject): Result {
        val service = ScreenAgentAccessibilityService.getInstance()
            ?: return Result(false, "I can't see the screen at the moment")

        // Let whatever the last step started (a list settling, a screen sliding in) finish
        // before resolving anything, so the cursor lands where things actually are and not
        // where they were mid-transition.
        if (name in GUIDING_TOOLS) delay(SETTLE_MS)

        val result = when (name) {
            "point_at" -> {
                val index = input.int("index") ?: return Result(false, "missing index")
                val target = service.resolveTapTarget(index)
                    ?: return Result(
                        false,
                        "index $index is not on the screen any more — look at the tree again " +
                            "and point at something that is",
                    )
                val label = input.string("label")?.takeIf { it.isNotBlank() }
                    ?: target.element?.text?.takeIf { it.isNotBlank() }
                guide(service, label?.let { "Tap $it" } ?: "Tap here", Rect(target.bounds))
            }

            "type" -> {
                val text = input.string("text") ?: return Result(false, "missing text")
                val bounds = input.int("index")?.let { service.resolveTapTarget(it)?.bounds }
                guide(service, "Type: $text", bounds?.let { Rect(it) })
            }

            "scroll" -> {
                val up = input.string("direction")?.startsWith("up", ignoreCase = true) == true
                val instruction = if (up) {
                    "Slide your finger down the screen to go back up"
                } else {
                    "Slide your finger up the screen to see more"
                }
                guide(service, instruction, null)
            }

            "open_app" -> {
                val requested = input.string("package_name")
                    ?: return Result(false, "missing package_name")
                val resolved = DeviceContext.resolve(appContext, requested)
                    ?: return Result(
                        false,
                        "no app called \"$requested\" is installed — pick one from the list " +
                            "you were given, and never tell the user to install anything",
                    )
                val label = appLabelFor(resolved) ?: requested
                // Point at the icon when it is on screen. Guiding with words alone
                // ("open Settings") is the help they already couldn't follow — the whole
                // value here is showing them where to press.
                val icon = findOnScreenByText(service, label)
                guide(service, if (icon != null) "Tap $label" else "Open $label", icon)
            }

            "back" -> guide(service, "Press the back button", null)
            "home" -> guide(service, "Go to the home screen", null)

            "speak" -> {
                val text = input.string("text").orEmpty()
                speechOutput.speak(text)
                pointerOverlay.showStatus(text, SPEAK_HOLD_MS)
                Result(true, "said it")
            }

            "done" -> {
                val summary = input.string("summary").orEmpty().ifBlank { "All done" }
                speechOutput.speak(summary)
                pointerOverlay.hide()
                pointerOverlay.showStatus(summary, SPEAK_HOLD_MS)
                Result(true, summary, done = true)
            }

            else -> Result(false, "unknown tool: $name")
        }

        ActionLog.append("$name -> ${result.message}")
        return result
    }

    /**
     * Shows one step and waits for the user to do it.
     *
     * The highlight stays up for the whole wait — an instruction that vanishes after a
     * second is worse than none for someone who reads slowly — and the instruction is
     * repeated once, aloud, before we give up.
     *
     * Giving up ends the session then and there. Asking the model what to say next would
     * only tempt it into showing the step again, and someone who has sat through half a
     * minute of a pulsing ring has already decided not to press it.
     */
    private suspend fun guide(
        service: ScreenAgentAccessibilityService,
        instruction: String,
        bounds: Rect?,
    ): Result {
        val before = TreeJson.signature(service.captureAgentTree())
        val beforePackage = service.currentPackage()

        bounds?.takeIf { !it.isEmpty }?.let { pointerOverlay.showTargetAt(it, WAIT_MS, instruction) }
        pointerOverlay.showStatus(instruction, WAIT_MS)
        speechOutput.speak(instruction)

        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        var nudged = false
        var changedPolls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            delay(POLL_MS)
            // Accessibility can be flipped off mid-step — don't sit out the full 30s.
            if (ScreenAgentAccessibilityService.getInstance() == null) {
                pointerOverlay.hide()
                val lost = "I've lost permission to read the screen — turn Aura back on in " +
                    "Accessibility settings."
                speechOutput.speak(lost)
                pointerOverlay.showStatus(lost, SPEAK_HOLD_MS)
                return Result(false, lost, done = true)
            }
            val now = TreeJson.signature(service.getVisibleElements())
            // A different app counts on its own only if the screen changed with it.
            // Unqualified, it fires on anything that merely raises a window — the keyboard,
            // the status bar — none of which means the step was taken.
            val switchedApp = service.currentPackage() != beforePackage &&
                now.isNotEmpty() &&
                now != before
            // Confirmed over two polls, so the half-drawn frame in the middle of a
            // transition cannot pass for a finished step.
            changedPolls = if (TreeJson.movedOn(before, now) || switchedApp) changedPolls + 1 else 0
            if (changedPolls >= CONFIRM_POLLS) {
                pointerOverlay.hide()
                return Result(true, "the user did it — \"$instruction\" — and the screen moved on")
            }
            if (!nudged && SystemClock.elapsedRealtime() > deadline - NUDGE_BEFORE_END_MS) {
                nudged = true
                speechOutput.speak(instruction)
            }
        }

        pointerOverlay.hide()
        val parting = "I'll leave you here — tap me when you'd like to carry on."
        speechOutput.speak(parting)
        pointerOverlay.showStatus(parting, SPEAK_HOLD_MS)
        return Result(false, "the user did not do it: \"$instruction\"", done = true)
    }

    /**
     * Finds something on the current screen labelled [label], so the cursor has somewhere
     * to point. Matches the whole label first, then a prefix, which is how launcher icons
     * appear when their names are truncated ("Settings", "Sett…").
     */
    private fun findOnScreenByText(
        service: ScreenAgentAccessibilityService,
        label: String,
    ): Rect? {
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return null

        val matches = mutableListOf<Rect>()
        fun walk(node: ElementNode) {
            val text = node.text.trim().lowercase()
            if (text.isNotEmpty() && !node.rect.isEmpty) {
                if (text == needle || text.startsWith(needle) || needle.startsWith(text)) {
                    matches.add(Rect(node.rect))
                }
            }
            node.children.forEach(::walk)
        }
        runCatching { service.getVisibleElements().forEach(::walk) }

        // Prefer the smallest match: an icon rather than the container holding it.
        return matches.minByOrNull { it.width().toLong() * it.height().toLong() }
    }

    /** Human-readable app name for a package, for the instruction. */
    private fun appLabelFor(packageName: String): String? = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? {
        val p = this[key]?.jsonPrimitive ?: return null
        return p.intOrNull ?: p.doubleOrNull?.toInt()
    }

    companion object {
        /**
         * Tools that guide, and so need the screen to be still before we read it: the
         * cursor has to land where things actually are, and the baseline we compare against
         * has to be the settled screen rather than the tail of the last transition.
         */
        private val GUIDING_TOOLS =
            setOf("point_at", "type", "open_app", "scroll", "back", "home")

        private const val SETTLE_MS = 400L

        /** How long someone gets to find the highlight and press it. */
        private const val WAIT_MS = 30_000L
        private const val POLL_MS = 500L

        /** Consecutive polls that must agree the screen changed before we believe it. */
        private const val CONFIRM_POLLS = 2

        /** Repeat the instruction once, near the end, in case they missed it. */
        private const val NUDGE_BEFORE_END_MS = 12_000L
        private const val SPEAK_HOLD_MS = 4_000L
    }
}
