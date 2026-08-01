package com.drishti.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import com.drishti.accessibility.GestureController
import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.accessibility.TreeJson
import com.drishti.data.AutoSpeed
import com.drishti.data.AuraMode
import com.drishti.overlay.ConfirmPromptOverlay
import com.drishti.overlay.PointerOverlay
import com.drishti.voice.SpeechOutput
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutor(
    private val appContext: Context,
    private val pointerOverlay: PointerOverlay,
    private val confirmOverlay: ConfirmPromptOverlay,
    private val speechOutput: SpeechOutput,
    private val askUserHandler: suspend (String) -> String,
    private val modeFor: (packageName: String) -> AuraMode = { AuraMode.Auto },
    private val autoSpeed: () -> AutoSpeed = { AutoSpeed.FollowAlong },
) {
    data class Result(
        val ok: Boolean,
        val message: String,
        val done: Boolean = false,
        val fingerprintAfter: String = "",
    )

    suspend fun execute(name: String, input: JsonObject): Result {
        val argsText = input.toString()
        val service = ScreenAgentAccessibilityService.getInstance()
            ?: return Result(false, "Accessibility service not running")

        val targetHint = when (name) {
            "tap" -> {
                val idx = input.int("index") ?: return Result(false, "missing index")
                service.findElementByIndex(idx)?.text.orEmpty()
            }
            "type" -> input.string("text").orEmpty()
            "open_app" -> input.string("package_name").orEmpty()
            else -> ""
        }

        if (SafetyGate.isSensitive(name, argsText, targetHint)) {
            speechOutput.speak("This looks sensitive. Please confirm.")
            pointerOverlay.showStatus("Confirm sensitive action")
            val ok = confirmOverlay.confirm("Allow: $name?\n$argsText")
            if (!ok) {
                ActionLog.append("DENIED $name $argsText")
                return Result(false, "User denied sensitive action")
            }
        }

        val result = when (name) {
            "tap" -> tapIndex(service, input.int("index"))
            "tap_xy" -> tapXy(input.int("x"), input.int("y"))
            "type" -> {
                val text = input.string("text") ?: return Result(false, "missing text")
                val clear = input.bool("clear") ?: true
                val index = input.int("index")
                val ok = service.inputText(text, clear, index)
                Result(
                    ok,
                    when {
                        ok -> "typed"
                        index != null -> "type failed (index=$index); tap the field first or pick a different index"
                        else -> "type failed; tap an editable field first, or pass index"
                    },
                )
            }
            "swipe" -> {
                val sx = input.int("startX") ?: return Result(false, "missing startX")
                val sy = input.int("startY") ?: return Result(false, "missing startY")
                val ex = input.int("endX") ?: return Result(false, "missing endX")
                val ey = input.int("endY") ?: return Result(false, "missing endY")
                val dur = input.int("durationMs") ?: 300
                val ok = GestureController.swipe(sx, sy, ex, ey, dur)
                Result(ok, if (ok) "swiped" else "swipe failed")
            }
            "scroll" -> {
                val direction = input.string("direction") ?: "down"
                val before = TreeJson.fingerprint(service.getVisibleElements())
                val ok = service.scroll(direction)
                delay(SETTLE_MS)
                val after = TreeJson.fingerprint(service.captureAgentTree())
                val changed = before != after
                Result(
                    ok,
                    when {
                        !ok -> "scroll failed"
                        changed -> "scrolled $direction"
                        else -> "scrolled $direction (no tree change — likely end of list)"
                    },
                )
            }
            "open_app" -> openApp(service, input.string("package_name"))
            "wait_for_change" -> {
                val ms = input.int("ms") ?: 800
                delay(ms.toLong())
                service.captureAgentTree()
                Result(true, "waited ${ms}ms")
            }
            "speak" -> {
                val text = input.string("text").orEmpty()
                speechOutput.speak(text)
                pointerOverlay.showStatus(text)
                Result(true, "spoke")
            }
            "ask_user" -> {
                val q = input.string("question") ?: return Result(false, "missing question")
                speechOutput.speak(q)
                pointerOverlay.showStatus(q)
                val answer = confirmOverlay.ask(q).ifBlank { askUserHandler(q) }
                Result(true, "user said: $answer")
            }
            "done" -> {
                val summary = input.string("summary").orEmpty()
                speechOutput.speak("Done")
                pointerOverlay.showStatus(summary.ifBlank { "Done" })
                Result(true, summary, done = true)
            }
            "back" -> {
                val ok = GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Result(ok, if (ok) "back" else "back failed")
            }
            "home" -> {
                val ok = GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Result(ok, if (ok) "home" else "home failed")
            }
            "recents" -> {
                val ok = GestureController.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_RECENTS,
                )
                Result(ok, if (ok) "recents" else "recents failed")
            }
            else -> Result(false, "unknown tool: $name")
        }

        ActionLog.append("$name -> ${result.message}")
        delay(SETTLE_MS)
        val fp = TreeJson.fingerprint(service.captureAgentTree())
        return result.copy(fingerprintAfter = fp)
    }

    private suspend fun tapIndex(
        service: ScreenAgentAccessibilityService,
        index: Int?,
    ): Result {
        if (index == null) return Result(false, "missing index")
        val target = service.resolveTapTarget(index)
            ?: return Result(false, "index $index not found in last observe tree")
        val bounds = Rect(target.bounds)
        val name = target.element?.text?.takeIf { it.isNotBlank() }

        if (modeFor(service.currentPackage()) == AuraMode.Guide) {
            return guideToTarget(service, bounds, name)
        }

        pointerOverlay.showTargetAt(bounds, POINTER_HOLD_MS + 400L, name?.let { "Tap \"$it\"" } ?: "Tap")
        delay(autoSpeed().stepPauseMs)

        // Prefer semantic click when we still have a live node; else gesture at published bounds.
        if (target.element != null && service.clickElement(target.element)) {
            return Result(true, "clicked index=$index via ACTION_CLICK")
        }
        val x = bounds.centerX()
        val y = bounds.centerY()
        val ok = GestureController.tap(x, y)
        return Result(
            ok,
            if (ok) {
                val via = if (target.element != null) "live" else "published-bounds"
                "tapped index=$index at ($x,$y) [$via]"
            } else {
                "tap failed"
            },
        )
    }

    /**
     * Guide mode: point at the target and wait for the user's own finger.
     *
     * "Points, waits, never taps" is a promise, so this must never fall back to a
     * gesture. We watch the accessibility tree for a change to know the user acted;
     * if they don't within [GUIDE_WAIT_MS] we hand control back to the model with an
     * honest report rather than silently doing it ourselves.
     */
    private suspend fun guideToTarget(
        service: ScreenAgentAccessibilityService,
        bounds: Rect,
        name: String?,
    ): Result {
        val instruction = name?.let { "Tap \"$it\"" } ?: "Tap the highlighted button"
        pointerOverlay.showTargetAt(bounds, GUIDE_WAIT_MS, instruction)
        pointerOverlay.showStatus(instruction, GUIDE_WAIT_MS)
        speechOutput.speak(instruction)

        val before = TreeJson.fingerprint(service.captureAgentTree())
        val deadline = SystemClock.elapsedRealtime() + GUIDE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(GUIDE_POLL_MS)
            val now = TreeJson.fingerprint(service.captureAgentTree())
            if (now != before) {
                pointerOverlay.hide()
                return Result(true, "user tapped \"${name ?: "the target"}\"; screen advanced")
            }
        }
        pointerOverlay.hide()
        return Result(
            false,
            "Guide mode: pointed at \"${name ?: "the target"}\" but the user has not tapped yet. " +
                "Do not retry the same tap; call done() and let them finish.",
        )
    }

    private suspend fun tapXy(x: Int?, y: Int?): Result {
        if (x == null || y == null) return Result(false, "missing x/y")
        val bounds = Rect(x - 40, y - 40, x + 40, y + 40)
        pointerOverlay.showTargetAt(bounds, POINTER_HOLD_MS + 400L, "Tap")
        delay(POINTER_HOLD_MS)
        val ok = GestureController.tap(x, y)
        return Result(ok, if (ok) "tapped ($x,$y)" else "tap failed")
    }

    private suspend fun openApp(
        service: ScreenAgentAccessibilityService,
        packageName: String?,
    ): Result {
        if (packageName.isNullOrBlank()) return Result(false, "missing package_name")
        return try {
            val resolved = resolvePackage(packageName)
                ?: return Result(
                    false,
                    "no installed app matches \"$packageName\" — pick a different app, " +
                        "do not ask the user to install anything",
                )
            val launch = appContext.packageManager.getLaunchIntentForPackage(resolved)
                ?: return Result(false, "no launch intent for $resolved")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launch)

            val deadline = SystemClock.elapsedRealtime() + OPEN_APP_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(OPEN_APP_POLL_MS)
                if (service.currentPackage() == resolved) {
                    service.captureAgentTree()
                    return Result(true, "opened $resolved")
                }
            }
            Result(
                true,
                "launched $resolved (foreground still ${service.currentPackage().ifBlank { "unknown" }})",
            )
        } catch (e: Exception) {
            Result(false, "open_app failed: ${e.message}")
        }
    }


    /**
     * Maps whatever the model asked for onto something actually installed.
     *
     * The model guesses package names from memory and is often close but not exact
     * ("org.telegram.messenger" when the phone has a fork, or just "Telegram"). Falling
     * back to a label/package match turns a dead end into a working launch, which matters
     * more than being strict about the argument.
     */
    private fun resolvePackage(requested: String): String? {
        val pm = appContext.packageManager
        if (pm.getLaunchIntentForPackage(requested) != null) return requested

        val needle = requested.substringAfterLast('.').lowercase().ifBlank { requested.lowercase() }
        val launchable = pm.getInstalledApplications(0)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }

        // Prefer an exact label match, then a package/label containment match.
        val byLabel = launchable.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(needle, ignoreCase = true)
        }
        if (byLabel != null) return byLabel.packageName

        return launchable.firstOrNull {
            it.packageName.lowercase().contains(needle) ||
                pm.getApplicationLabel(it).toString().lowercase().contains(needle)
        }?.packageName
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? {
        val p = this[key]?.jsonPrimitive ?: return null
        return p.intOrNull ?: p.doubleOrNull?.toInt()
    }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private const val SETTLE_MS = 350L
        // Long enough that the guidance highlight actually registers before we act on it.
        private const val POINTER_HOLD_MS = 750L
        private const val GUIDE_WAIT_MS = 20_000L
        private const val GUIDE_POLL_MS = 350L
        private const val OPEN_APP_WAIT_MS = 4_000L
        private const val OPEN_APP_POLL_MS = 200L
    }
}
