package com.drishti.core.agent

import com.drishti.core.screen.Bounds
import com.drishti.core.screen.EncodedScreen
import com.drishti.core.screen.Target
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class Action(val wire: String) {
    Tap("tap"),
    LongPress("long_press"),
    Type("type"),
    Scroll("scroll"),
    Back("back"),
    Home("home"),
    OpenApp("open_app"),
    /** Something is loading; say so and watch, without asking for anything. */
    Wait("wait"),
    Done("done"),
    /** Not possible, not safe, or not on this phone. */
    Cannot("cannot"),
    ;

    /** Shown to the user and then waited on. */
    val guides: Boolean get() = this in setOf(Tap, LongPress, Type, Scroll, Back, Home, OpenApp)

    companion object {
        fun fromWire(s: String?): Action? = entries.firstOrNull { it.wire.equals(s?.trim(), ignoreCase = true) }
    }
}

enum class Direction { Down, Up, Left, Right }

/** The single next thing to show, as decided by the model. */
@Serializable
data class Step(
    val action: Action,
    val say: String,
    val progress: String = "",
    val target: Int? = null,
    /** Percent of screen width/height, for things the tree has no reference for. */
    val pointX: Float? = null,
    val pointY: Float? = null,
    val text: String? = null,
    val direction: Direction? = null,
    val app: String? = null,
    val expect: String? = null,
) {
    companion object {
        const val TOOL_NAME = "next_step"

        /** Parses the model's tool arguments. Missing or mistyped fields become null. */
        fun parse(args: JsonObject): Step? {
            fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            fun int(k: String) = (args[k] as? JsonPrimitive)?.let { p ->
                p.contentOrNull?.trim()?.removePrefix("[")?.removeSuffix("]")?.toDoubleOrNull()?.toInt()
            }
            val action = Action.fromWire(str("action")) ?: return null
            val point = (args["point"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toFloat() }
            return Step(
                action = action,
                say = str("say").orEmpty(),
                progress = str("progress").orEmpty(),
                target = int("target"),
                pointX = point?.getOrNull(0),
                pointY = point?.getOrNull(1),
                text = (args["text"] as? JsonPrimitive)?.contentOrNull,
                direction = str("direction")?.let { d -> Direction.entries.firstOrNull { it.name.equals(d, true) } },
                app = str("app"),
                expect = str("expect"),
            )
        }

        fun toolSchema(language: Language): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("progress") {
                    put("type", "string")
                    put(
                        "description",
                        "Max 15 words, English: what this screen is, how far along they are, and what is left.",
                    )
                }
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { Action.entries.forEach { add(JsonPrimitive(it.wire)) } }
                }
                putJsonObject("target") {
                    put("type", "integer")
                    put("description", "The [n] reference of the element. Required for tap, long_press and type; for scroll, the list to scroll.")
                }
                putJsonObject("point") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "number") }
                    put("description", "[x, y] in percent of the screen. Only when the thing to press is visible in the screenshot but has no [n] reference.")
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "type: exactly what they should type.")
                }
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("down", "up", "left", "right").forEach { add(JsonPrimitive(it)) } }
                    put("description", "scroll: which way the content should move to reveal what is needed. down = see more below.")
                }
                putJsonObject("app") {
                    put("type", "string")
                    put("description", "open_app: package name from the installed apps list.")
                }
                putJsonObject("say") {
                    put("type", "string")
                    put(
                        "description",
                        "What Aura says aloud, in ${language.label}. Max 12 words, warm and plain. " +
                            "Name things exactly as written on screen.",
                    )
                }
                putJsonObject("expect") {
                    put("type", "string")
                    put("description", "Max 8 words: what should appear once they have done it.")
                }
            }
            put("required", buildJsonArray { listOf("progress", "action", "say").forEach { add(JsonPrimitive(it)) } })
        }
    }
}

/** Why a step can't be shown as given. Sent back to the model so it can correct itself. */
sealed class StepProblem(val note: String) {
    class NoSuchTarget(ref: Int?) : StepProblem(
        "There is no [${ref ?: "?"}] on this screen. Use only reference numbers from the current screen list.",
    )
    class MissingText : StepProblem("A type step needs the exact text to type.")
    class UnknownApp(app: String?) : StepProblem(
        "\"${app.orEmpty()}\" is not installed. Pick a package from the installed apps list, or guide them another way.",
    )
    class TypeIntoNonField(ref: Int) : StepProblem("[$ref] is not a text box. Point at the text box to type into.")
    class EmptySay : StepProblem("`say` was empty — tell them what to do.")
}

/**
 * A step resolved against the screen it was decided on: where to point, what to watch.
 */
data class ResolvedStep(
    val step: Step,
    /** Element to point at; null for back/home/scroll-anywhere and coordinate taps. */
    val target: Target?,
    /** Where to draw the ring. */
    val bounds: Bounds?,
    val app: AppInfo? = null,
)

object StepResolver {
    fun resolve(
        step: Step,
        screen: EncodedScreen,
        screenWidth: Int,
        screenHeight: Int,
        resolveApp: (String) -> AppInfo?,
    ): Result<ResolvedStep> {
        if (step.say.isBlank() && step.action != Action.Wait) return Result.failure(ProblemException(StepProblem.EmptySay()))
        return when (step.action) {
            Action.Tap, Action.LongPress -> {
                val target = step.target?.let { screen.targets[it] }
                when {
                    target != null && !target.scrollable -> Result.success(ResolvedStep(step, target, target.bounds))
                    step.pointX != null && step.pointY != null -> {
                        val b = pointBounds(step.pointX, step.pointY, screenWidth, screenHeight)
                        Result.success(ResolvedStep(step, null, b))
                    }
                    else -> Result.failure(ProblemException(StepProblem.NoSuchTarget(step.target)))
                }
            }

            Action.Type -> {
                if (step.text.isNullOrEmpty()) return Result.failure(ProblemException(StepProblem.MissingText()))
                val target = step.target?.let { screen.targets[it] }
                when {
                    step.target != null && target == null ->
                        Result.failure(ProblemException(StepProblem.NoSuchTarget(step.target)))
                    target != null && target.role != "field" ->
                        Result.failure(ProblemException(StepProblem.TypeIntoNonField(target.ref)))
                    else -> Result.success(ResolvedStep(step, target, target?.bounds))
                }
            }

            Action.Scroll -> {
                val target = step.target?.let { screen.targets[it] }?.takeIf { it.scrollable }
                    ?: screen.targets.values.filter { it.scrollable }.maxByOrNull { it.bounds.area }
                Result.success(ResolvedStep(step, target, target?.bounds))
            }

            Action.OpenApp -> {
                val app = step.app?.let(resolveApp)
                    ?: return Result.failure(ProblemException(StepProblem.UnknownApp(step.app)))
                // Point at the icon if it is on screen — "open WhatsApp" is the help they
                // already couldn't follow; showing where to press is the point.
                val icon = screen.targets.values
                    .filter { !it.scrollable && labelMatches(it.label, app.label) }
                    .minByOrNull { it.bounds.area }
                Result.success(ResolvedStep(step, icon, icon?.bounds, app))
            }

            Action.Back, Action.Home, Action.Wait, Action.Done, Action.Cannot ->
                Result.success(ResolvedStep(step, null, null))
        }
    }

    private fun labelMatches(label: String, appLabel: String): Boolean {
        val a = label.trim().lowercase()
        val b = appLabel.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.startsWith(b) || (b.startsWith(a.removeSuffix("…")) && a.length >= 4)
    }

    private fun pointBounds(px: Float, py: Float, w: Int, h: Int): Bounds {
        val x = (px.coerceIn(0f, 100f) / 100f * w).toInt()
        val y = (py.coerceIn(0f, 100f) / 100f * h).toInt()
        val r = (minOf(w, h) * 0.06f).toInt().coerceAtLeast(24)
        return Bounds(x - r, y - r, x + r, y + r)
    }
}

class ProblemException(val problem: StepProblem) : Exception(problem.note)

@Serializable
data class AppInfo(val label: String, val pkg: String)
