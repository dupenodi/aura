package com.drishti.agent

import android.content.Context
import android.content.SharedPreferences
import com.drishti.debug.RingBufferLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo-friendly on-device action path cache (SharedPreferences — not RL / RAG).
 *
 * After a successful multi-step finish(), stores the compact tool sequence keyed by
 * normalized intent (goal keywords + preferred app). On a matching later utterance,
 * injects the recipe into the prompt so the model can replay instead of exploring.
 * UI Replay uses [AgentLoop.replayRecipe] for a deterministic fast path.
 */
@Serializable
data class RecipeStep(
    val name: String,
    val args: String,
    val x: Float? = null,
    val y: Float? = null,
    val nodeId: Int? = null,
    val text: String? = null,
)

@Serializable
data class StoredRecipe(
    val key: String,
    val intentLabel: String,
    val steps: List<RecipeStep>,
    val savedAtMs: Long = System.currentTimeMillis(),
    val lastUsedAtMs: Long = System.currentTimeMillis(),
    val useCount: Int = 1,
    /** Original user utterance that produced this recipe (best for replay / fallback). */
    val sourceUtterance: String = "",
)

@Singleton
class RecipeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _recipes = MutableStateFlow(loadAll())
    val recipes: StateFlow<List<StoredRecipe>> = _recipes.asStateFlow()

    private val _count = MutableStateFlow(_recipes.value.size)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun size(): Int = _count.value

    fun listAll(): List<StoredRecipe> = _recipes.value

    fun get(key: String): StoredRecipe? = _recipes.value.find { it.key == key } ?: load(key)

    /**
     * Persist a multi-step recipe. Overwrites any prior entry for the same intent key.
     * Rejects meta-only runs (observe/speak/finish) — requires at least one real UI action.
     */
    fun save(
        userGoal: String,
        steps: List<RecipeStep>,
        preferredApp: String? = null,
    ): Boolean {
        val compact = steps
            .filter { it.name in ACTION_TOOLS }
            .map { enrich(it) }
            .take(MAX_STEPS)
        if (compact.size < MIN_STEPS) {
            RingBufferLogger.log("recipe", "reject save: need ≥$MIN_STEPS action steps")
            return false
        }
        if (!hasRealUiActions(compact)) {
            RingBufferLogger.log(
                "recipe",
                "reject save: meta-only (no open_app/tap/tap_node/type_text)",
            )
            return false
        }
        // Never key a recipe off a "save the flow" utterance.
        if (SaveFlowIntent.matches(userGoal)) {
            RingBufferLogger.log("recipe", "reject save: userGoal is save-flow meta utterance")
            return false
        }
        val key = intentKey(userGoal, preferredApp)
        if (key.isBlank()) return false
        val label = intentLabel(userGoal, preferredApp)
        val prior = get(key)
        val now = System.currentTimeMillis()
        val recipe = StoredRecipe(
            key = key,
            intentLabel = label,
            steps = compact,
            savedAtMs = prior?.savedAtMs ?: now,
            lastUsedAtMs = now,
            useCount = (prior?.useCount ?: 0) + 1,
            sourceUtterance = userGoal.trim().ifBlank { prior?.sourceUtterance.orEmpty() },
        )
        val encoded = runCatching { json.encodeToString(recipe) }.getOrElse { return false }
        prefs.edit().putString(key, encoded.take(MAX_VALUE_CHARS)).apply()
        refresh()
        RingBufferLogger.log(
            "recipe",
            "saved key=$key steps=${compact.size} label=$label",
        )
        return true
    }

    /**
     * Promote a [FlowRecord] (including cancelled/incomplete) into Learned flows.
     * Uses the original utterance as the recipe key/source.
     */
    fun saveFromFlow(
        flow: FlowRecord,
        preferredApp: String? = null,
    ): Boolean {
        val steps = stepsFromHistory(flow.steps)
        return save(
            userGoal = flow.userUtterance.ifBlank { flow.summary },
            steps = steps,
            preferredApp = preferredApp,
        )
    }

    /** Convert successful history tool rows into recipe steps (with coords when present). */
    fun stepsFromHistory(steps: List<FlowHistoryStep>): List<RecipeStep> {
        return steps
            .filter { it.success && it.name in ACTION_TOOLS }
            .map { stepFromToolCall(it.name, it.args, resultText = it.resultText) }
    }

    fun hasRealUiActions(steps: List<RecipeStep>): Boolean =
        steps.any { it.name in REAL_UI_ACTIONS }

    /** Best matching recipe for this utterance, or null. */
    fun findMatch(userGoal: String, preferredApp: String? = null): StoredRecipe? {
        val key = intentKey(userGoal, preferredApp)
        if (key.isBlank()) return null
        load(key)?.let { return it }
        // Soft match: any stored recipe whose key tokens are a subset of this intent.
        val tokens = key.split('+').filter { it.isNotBlank() }.toSet()
        if (tokens.isEmpty()) return null
        var best: StoredRecipe? = null
        var bestOverlap = 0
        for ((storedKey, raw) in prefs.all) {
            val recipe = decode(raw?.toString()) ?: continue
            val storedTokens = storedKey.split('+').filter { it.isNotBlank() }.toSet()
            val overlap = storedTokens.intersect(tokens).size
            val needed = (storedTokens.size * 2 + 2) / 3 // ~⅔ of recipe tokens
            if (overlap >= needed.coerceAtLeast(1) && overlap > bestOverlap) {
                best = recipe
                bestOverlap = overlap
            }
        }
        if (best != null) {
            RingBufferLogger.log(
                "recipe",
                "matched soft key=${best.key} overlap=$bestOverlap for $key",
            )
        }
        return best
    }

    fun markUsed(key: String) {
        val recipe = get(key) ?: return
        val updated = recipe.copy(
            lastUsedAtMs = System.currentTimeMillis(),
            useCount = recipe.useCount + 1,
        )
        val encoded = runCatching { json.encodeToString(updated) }.getOrElse { return }
        prefs.edit().putString(key, encoded.take(MAX_VALUE_CHARS)).apply()
        refresh()
    }

    fun delete(key: String): Boolean {
        if (!prefs.contains(key)) return false
        prefs.edit().remove(key).apply()
        refresh()
        RingBufferLogger.log("recipe", "deleted key=$key")
        return true
    }

    fun formatForPrompt(recipe: StoredRecipe): String = buildString {
        append("Known successful recipe (verify with observe; replay when UI matches): ")
        append(recipe.intentLabel)
        append('\n')
        recipe.steps.forEachIndexed { i, step ->
            append(i + 1)
            append(". ")
            append(step.name)
            append('(')
            append(step.args.take(120))
            if (step.nodeId != null) append(" node_id=${step.nodeId}")
            if (step.x != null && step.y != null) {
                append(" @${step.x.toInt()},${step.y.toInt()}")
            }
            if (!step.text.isNullOrBlank()) append(" text=\"${step.text.take(40)}\"")
            append(")\n")
        }
        append("Prefer this path when the screen looks the same; re-observe if a step fails.")
    }

    fun clear() {
        prefs.edit().clear().apply()
        refresh()
        RingBufferLogger.log("recipe", "cleared all recipes")
    }

    fun formatAsText(): String {
        if (_recipes.value.isEmpty()) return "(no recipes cached)"
        return _recipes.value.joinToString("\n") { r ->
            "${r.key} → ${r.steps.size} steps (${r.intentLabel})"
        }
    }

    /** Build a recipe step from a tool call, pulling tap/type hints out of args + result. */
    fun stepFromToolCall(
        name: String,
        argumentsJson: String,
        tapX: Float? = null,
        tapY: Float? = null,
        resultText: String? = null,
    ): RecipeStep {
        val raw = argumentsJson.ifBlank { "{}" }
        val hints = parseHints(raw)
        val fromResult = parseCoordsFromResult(resultText)
        val x = tapX ?: hints.x ?: fromResult?.first
        val y = tapY ?: hints.y ?: fromResult?.second
        val enrichedArgs = mergeCoordsIntoArgs(raw, x, y, hints.nodeId, hints.text)
        return enrich(
            RecipeStep(
                name = name,
                args = enrichedArgs.take(MAX_ARGS_CHARS),
                x = x,
                y = y,
                nodeId = hints.nodeId,
                text = hints.text,
            ),
        )
    }

    /**
     * FAST_DETERMINISTIC replay invocation: prefer coordinate [tap] over [tap_node]
     * when exact x,y were persisted (faster + stable across node-id churn).
     */
    fun replayInvocation(step: RecipeStep): Pair<String, String> = replayInvocationOf(step)

    /** Args JSON to execute on replay (prefers structured x/y/node/text when present). */
    fun argsForReplay(step: RecipeStep): String = argsForReplayOf(step)

    private fun enrich(step: RecipeStep): RecipeStep {
        val hints = parseHints(step.args)
        val x = step.x ?: hints.x
        val y = step.y ?: hints.y
        val nodeId = step.nodeId ?: hints.nodeId
        val text = step.text ?: hints.text
        return step.copy(
            args = mergeCoordsIntoArgs(step.args, x, y, nodeId, text).take(MAX_ARGS_CHARS),
            x = x,
            y = y,
            nodeId = nodeId,
            text = text,
        )
    }

    fun mergeCoordsIntoArgs(
        argsJson: String,
        x: Float?,
        y: Float?,
        nodeId: Int? = null,
        text: String? = null,
    ): String = Companion.mergeCoordsIntoArgs(argsJson, x, y, nodeId, text)

    fun parseCoordsFromResult(resultText: String?): Pair<Float, Float>? =
        Companion.parseCoordsFromResult(resultText)

    private data class Hints(
        val x: Float? = null,
        val y: Float? = null,
        val nodeId: Int? = null,
        val text: String? = null,
    )

    private fun parseHints(argsJson: String): Hints {
        val obj = runCatching {
            json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return Hints()
        return Hints(
            x = obj["x"]?.jsonPrimitive?.floatOrNull,
            y = obj["y"]?.jsonPrimitive?.floatOrNull,
            nodeId = obj["node_id"]?.jsonPrimitive?.intOrNull,
            text = obj["text"]?.jsonPrimitive?.contentOrNull?.take(80)
                ?: obj["query"]?.jsonPrimitive?.contentOrNull?.take(80),
        )
    }

    private fun load(key: String): StoredRecipe? {
        val raw = prefs.getString(key, null) ?: return null
        return decode(raw)
    }

    private fun decode(raw: String?): StoredRecipe? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<StoredRecipe>(raw) }.getOrNull()
    }

    private fun loadAll(): List<StoredRecipe> {
        return prefs.all.mapNotNull { (_, v) -> decode(v?.toString()) }
            .sortedByDescending { it.lastUsedAtMs }
    }

    private fun refresh() {
        val all = loadAll()
        _recipes.value = all
        _count.value = all.size
    }

    companion object {
        private const val PREFS_NAME = "drishti_action_recipes"
        private const val MIN_STEPS = 2
        private const val MAX_STEPS = 24
        private const val MAX_ARGS_CHARS = 220
        private const val MAX_VALUE_CHARS = 8_000

        /** Tools worth replaying (skip speak/observe/annotate/wait/prefs/finish). */
        val ACTION_TOOLS = setOf(
            "open_app",
            "tap",
            "tap_node",
            "type_text",
            "scroll",
            "swipe",
            "press_key",
            "long_press",
        )

        /** At least one of these is required — rejects observe/speak/finish-only runs. */
        val REAL_UI_ACTIONS = setOf(
            "open_app",
            "tap",
            "tap_node",
            "type_text",
        )

        private val APP_KEYWORDS = listOf(
            "zepto", "blinkit", "instamart", "swiggy", "zomato",
            "uber", "ola", "instagram", "insta", "maps", "youtube",
            "chrome", "whatsapp", "settings",
        )

        private val GOAL_KEYWORDS = listOf(
            "onion", "garlic", "biryani", "book", "cab", "ride", "taxi",
            "order", "cart", "search", "follow", "unfollow", "like",
            "grocery", "food", "restaurant", "destination", "pay",
            "kg", "sweets", "meghana",
        )

        fun intentKey(userGoal: String, preferredApp: String? = null): String {
            val tokens = linkedSetOf<String>()
            val lower = userGoal.lowercase()
            for (app in APP_KEYWORDS) {
                if (lower.contains(app)) tokens += app
            }
            preferredApp?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { tokens += it }
            for (g in GOAL_KEYWORDS) {
                if (lower.contains(g)) tokens += g
            }
            // Fallback: significant latin words (≥4 letters) from the goal.
            if (tokens.size < 2) {
                Regex("[a-zA-Z]{4,}").findAll(lower)
                    .map { it.value }
                    .filter { it !in STOP_WORDS }
                    .take(4)
                    .forEach { tokens += it }
            }
            return tokens.sorted().joinToString("+").take(96)
        }

        fun intentLabel(userGoal: String, preferredApp: String? = null): String {
            val key = intentKey(userGoal, preferredApp)
            return key.ifBlank { userGoal.trim().take(48) }.replace('+', ' ')
        }

        private val STOP_WORDS = setOf(
            "open", "please", "with", "from", "that", "this", "have", "want",
            "need", "some", "just", "into", "over", "under", "about", "then",
            "continue", "keep", "going", "next", "resume",
        )

        private val TAP_COORD_REGEX =
            Regex("""(?:at |tapped )(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)""")

        private val helperJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Parse "tapped node N at X,Y" / "tapped X,Y" from tool result text. */
        fun parseCoordsFromResult(resultText: String?): Pair<Float, Float>? {
            if (resultText.isNullOrBlank()) return null
            val m = TAP_COORD_REGEX.find(resultText) ?: return null
            val x = m.groupValues[1].toFloatOrNull() ?: return null
            val y = m.groupValues[2].toFloatOrNull() ?: return null
            return x to y
        }

        /** Embed exact coords into args JSON so promote-from-history keeps them. */
        fun mergeCoordsIntoArgs(
            argsJson: String,
            x: Float?,
            y: Float?,
            nodeId: Int? = null,
            text: String? = null,
        ): String {
            val base = argsJson.ifBlank { "{}" }
            val obj = runCatching {
                helperJson.parseToJsonElement(base).jsonObject.toMutableMap()
            }.getOrElse { return base }
            x?.let { obj["x"] = JsonPrimitive(it) }
            y?.let { obj["y"] = JsonPrimitive(it) }
            nodeId?.let { obj["node_id"] = JsonPrimitive(it) }
            text?.takeIf { it.isNotBlank() }?.let { obj["text"] = JsonPrimitive(it) }
            return JsonObject(obj).toString().ifBlank { base }
        }

        fun argsForReplayOf(step: RecipeStep): String {
            val base = step.args.ifBlank { "{}" }
            val obj = runCatching {
                helperJson.parseToJsonElement(base).jsonObject.toMutableMap()
            }.getOrElse { return base }
            step.x?.let { obj["x"] = JsonPrimitive(it) }
            step.y?.let { obj["y"] = JsonPrimitive(it) }
            step.nodeId?.let { obj["node_id"] = JsonPrimitive(it) }
            step.text?.takeIf { it.isNotBlank() }?.let { obj["text"] = JsonPrimitive(it) }
            return JsonObject(obj).toString().ifBlank { base }
        }

        fun replayInvocationOf(step: RecipeStep): Pair<String, String> {
            if (step.name == "tap_node" && step.x != null && step.y != null) {
                return "tap" to """{"x":${step.x},"y":${step.y}}"""
            }
            if (step.name == "tap" && step.x != null && step.y != null) {
                return "tap" to """{"x":${step.x},"y":${step.y}}"""
            }
            return step.name to argsForReplayOf(step)
        }
    }
}
