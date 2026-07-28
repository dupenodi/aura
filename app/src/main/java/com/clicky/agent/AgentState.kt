package com.clicky.agent

import android.content.Context
import android.content.SharedPreferences
import com.clicky.ai.ChatMessage
import com.clicky.debug.RingBufferLogger
import com.clicky.overlay.AgentMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentRunStatus {
    Idle,
    Running,
    Finished,
    Error,
}

data class AgentTurn(
    val userText: String,
    val messages: List<ChatMessage>,
    val summary: String? = null,
)

/**
 * Compact on-device conversation entry for cross-run memory.
 * Cross-device long-term memory can be Supabase later — not needed for hackathon demos.
 */
@Serializable
data class SessionMessage(
    val role: String,
    val text: String,
    val atMs: Long = System.currentTimeMillis(),
)

data class AgentUiState(
    val mode: AgentMode = AgentMode.Coach,
    val status: AgentRunStatus = AgentRunStatus.Idle,
    val statusLine: String = "Idle",
    val lastSummary: String? = null,
    val lastError: String? = null,
    val iteration: Int = 0,
    val model: String = "",
    /** Active LLM provider label, e.g. OpenAI / Anthropic. */
    val provider: String = "",
    val lastToolName: String? = null,
    val lastToolResult: String? = null,
    /** How many compact session messages are retained. */
    val sessionMessageCount: Int = 0,
)

/**
 * Mutable agent session: mode, run status, tap dedupe for the current turn,
 * and rolling on-device conversation memory across voice/text commands.
 */
@Singleton
class AgentState @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Completed turns (user request → finish), newest last. Full tool traces for debugging. */
    private val turns = mutableListOf<AgentTurn>()

    /**
     * Compact session memory (user + assistant summary) across runs in this app session.
     * Persisted lightly so process death mid-demo doesn't wipe context.
     */
    private val sessionMemory = mutableListOf<SessionMessage>()

    // --- Per-turn tap dedupe (all taps within ~2s + same-node until observe) ---
    var lastTappedNodeId: Int? = null
        private set
    var lastTapX: Float? = null
        private set
    var lastTapY: Float? = null
        private set
    var lastTapAtMs: Long = 0L
        private set
    var lastTapLabel: String? = null
        private set
    var lastTapWasToggle: Boolean = false
        private set

    /**
     * Node ids successfully tapped this turn. Cleared on observe so the model may
     * retry only after proof the UI was re-checked (prevents same-id double taps).
     */
    private val successfulTapNodeIdsThisTurn = mutableSetOf<Int>()

    /** User utterance for the active turn (search/address guards, hints). */
    var currentUserGoal: String = ""
        private set

    /**
     * Last STT-detected speech language for this process (BCP-47, e.g. kn-IN).
     * Used as default TTS [target_language_code] when speak() omits language.
     * Not persisted — preference `language` covers cross-session defaults.
     */
    @Volatile
    var lastDetectedLanguage: String? = null
        private set

    /** Node ids annotated this turn (highlight-before-tap). */
    private val annotatedNodeIds = mutableSetOf<Int>()

    /** Latest observe_screen / auto-observe prompt text for checkout finish guards. */
    var lastScreenContextText: String = ""
        private set

    /** True after a successful tap on Pay / Place order / Proceed to pay this turn. */
    var attemptedPayThisTurn: Boolean = false
        private set

    /**
     * Count of consecutive state-changing tools since the last successful speak().
     * Soft-nudges narration when this reaches 2+.
     */
    var stateChangingSinceSpeak: Int = 0
        private set

    init {
        loadSessionMemory()
    }

    fun setMode(mode: AgentMode) {
        _ui.update { it.copy(mode = mode) }
    }

    fun mode(): AgentMode = _ui.value.mode

    fun lastTurns(limit: Int): List<AgentTurn> {
        if (limit <= 0) return emptyList()
        return turns.takeLast(limit).toList()
    }

    fun appendTurn(turn: AgentTurn) {
        turns.add(turn)
        while (turns.size > MAX_STORED_TURNS) {
            turns.removeAt(0)
        }
        rememberExchange(turn.userText, turn.summary)
    }

    /** Rolling compact history for the prompt (newest last). */
    fun lastSessionMessages(limit: Int = HISTORY_MESSAGES_IN_PROMPT): List<SessionMessage> {
        if (limit <= 0) return emptyList()
        return sessionMemory.takeLast(limit).toList()
    }

    fun sessionMessageCount(): Int = sessionMemory.size

    /**
     * Clear on-device conversation memory (debug / demo reset).
     * Does not touch Supabase — none is used here.
     */
    fun clearHistory() {
        turns.clear()
        sessionMemory.clear()
        clearTapTracking()
        prefs.edit().remove(KEY_SESSION_MEMORY).apply()
        _ui.update { it.copy(sessionMessageCount = 0, lastSummary = null) }
        RingBufferLogger.log("memory", "cleared chat history")
    }

    /** Call at the start of each agent run. */
    fun beginTurn(userGoal: String = "") {
        clearTapTracking()
        currentUserGoal = userGoal.trim()
        annotatedNodeIds.clear()
        lastScreenContextText = ""
        attemptedPayThisTurn = false
        stateChangingSinceSpeak = 0
    }

    fun recordScreenContext(text: String) {
        if (text.isNotBlank()) lastScreenContextText = text
    }

    fun recordPayAttempt() {
        attemptedPayThisTurn = true
    }

    fun recordSpeak() {
        stateChangingSinceSpeak = 0
    }

    fun recordStateChangingTool() {
        stateChangingSinceSpeak += 1
    }

    fun needsNarrationNudge(): Boolean = stateChangingSinceSpeak >= 2

    fun setLastDetectedLanguage(code: String?) {
        val normalized = code?.trim()?.takeIf { it.isNotEmpty() }
        lastDetectedLanguage = normalized
        if (normalized != null) {
            RingBufferLogger.log("stt", "lastDetectedLanguage=$normalized")
        }
    }

    fun clearTapTracking() {
        lastTappedNodeId = null
        lastTapX = null
        lastTapY = null
        lastTapAtMs = 0L
        lastTapLabel = null
        lastTapWasToggle = false
        successfulTapNodeIdsThisTurn.clear()
    }

    /** After observe_screen — allow re-tapping only once UI was re-checked. */
    fun onObserveCompleted() {
        successfulTapNodeIdsThisTurn.clear()
        lastTappedNodeId = null
        lastTapAtMs = 0L
        lastTapX = null
        lastTapY = null
        lastTapLabel = null
        lastTapWasToggle = false
    }

    fun recordAnnotate(nodeId: Int?) {
        if (nodeId != null) annotatedNodeIds.add(nodeId)
    }

    fun wasAnnotated(nodeId: Int): Boolean = nodeId in annotatedNodeIds

    fun recordTap(
        nodeId: Int?,
        x: Float,
        y: Float,
        label: String?,
        wasToggle: Boolean,
    ) {
        lastTappedNodeId = nodeId
        lastTapX = x
        lastTapY = y
        lastTapAtMs = System.currentTimeMillis()
        lastTapLabel = label
        lastTapWasToggle = wasToggle
        if (nodeId != null) successfulTapNodeIdsThisTurn.add(nodeId)
    }

    /**
     * True when a new tap_node/tap looks like a duplicate:
     * - same node id already tapped successfully this turn (until observe)
     * - same node id or near-XY within [DUPLICATE_TAP_WINDOW_MS] (~2s) for ALL taps
     */
    fun isDuplicateTap(
        nodeId: Int?,
        x: Float,
        y: Float,
        label: String?,
        looksToggle: Boolean,
    ): String? {
        if (nodeId != null && nodeId in successfulTapNodeIdsThisTurn) {
            return "blocked: already tapped node $nodeId this turn — observe first"
        }
        val now = System.currentTimeMillis()
        if (lastTapAtMs <= 0L || now - lastTapAtMs > DUPLICATE_TAP_WINDOW_MS) {
            return null
        }
        val sameNode = nodeId != null && lastTappedNodeId != null && nodeId == lastTappedNodeId
        if (sameNode) {
            return "blocked: already tapped node $nodeId — observe first"
        }
        val lx = lastTapX
        val ly = lastTapY
        if (lx != null && ly != null) {
            val dx = x - lx
            val dy = y - ly
            val near = dx * dx + dy * dy <= DUPLICATE_TAP_RADIUS_PX * DUPLICATE_TAP_RADIUS_PX
            if (near) {
                val toggleHint = if (looksToggle || lastTapWasToggle ||
                    isToggleLikeLabel(label) || isToggleLikeLabel(lastTapLabel)
                ) {
                    " (toggle/control tapped once)"
                } else {
                    ""
                }
                return "blocked: already tapped near (${lx.toInt()},${ly.toInt()}) " +
                    "— observe first$toggleHint"
            }
        }
        return null
    }

    fun updateStatus(
        status: AgentRunStatus,
        statusLine: String,
        iteration: Int = _ui.value.iteration,
        model: String = _ui.value.model,
        provider: String = _ui.value.provider,
        lastToolName: String? = _ui.value.lastToolName,
        lastToolResult: String? = _ui.value.lastToolResult,
        lastSummary: String? = _ui.value.lastSummary,
        lastError: String? = _ui.value.lastError,
    ) {
        _ui.update {
            it.copy(
                status = status,
                statusLine = statusLine,
                iteration = iteration,
                model = model,
                provider = provider,
                lastToolName = lastToolName,
                lastToolResult = lastToolResult,
                lastSummary = lastSummary,
                lastError = lastError,
                sessionMessageCount = sessionMemory.size,
            )
        }
    }

    private fun rememberExchange(userText: String, summary: String?) {
        val user = userText.trim()
        if (user.isNotEmpty()) {
            sessionMemory.add(SessionMessage(role = "user", text = user))
        }
        val assistant = summary?.trim().orEmpty()
        if (assistant.isNotEmpty()) {
            sessionMemory.add(
                SessionMessage(role = "assistant", text = assistant.take(MAX_SUMMARY_CHARS)),
            )
        }
        while (sessionMemory.size > MAX_SESSION_MESSAGES) {
            sessionMemory.removeAt(0)
        }
        persistSessionMemory()
        _ui.update { it.copy(sessionMessageCount = sessionMemory.size) }
    }

    private fun persistSessionMemory() {
        runCatching {
            val encoded = json.encodeToString(sessionMemory.toList())
            prefs.edit().putString(KEY_SESSION_MEMORY, encoded).apply()
        }.onFailure {
            RingBufferLogger.log("memory", "persist failed: ${it.message}")
        }
    }

    private fun loadSessionMemory() {
        val raw = prefs.getString(KEY_SESSION_MEMORY, null) ?: return
        runCatching {
            val loaded = json.decodeFromString<List<SessionMessage>>(raw)
            sessionMemory.clear()
            sessionMemory.addAll(loaded.takeLast(MAX_SESSION_MESSAGES))
            _ui.update { it.copy(sessionMessageCount = sessionMemory.size) }
            RingBufferLogger.log("memory", "loaded ${sessionMemory.size} session messages")
        }.onFailure {
            RingBufferLogger.log("memory", "load failed: ${it.message}")
            prefs.edit().remove(KEY_SESSION_MEMORY).apply()
        }
    }

    companion object {
        const val MAX_STORED_TURNS = 20
        /** Compact user/assistant pairs injected into the next run's prompt. */
        const val HISTORY_MESSAGES_IN_PROMPT = 20
        /** Legacy detailed turns kept in memory (tool traces); prompt uses compact session. */
        const val HISTORY_TURNS_IN_PROMPT = 8

        /** Block same-node / near-XY re-taps within this window (all controls, not only toggles). */
        const val DUPLICATE_TAP_WINDOW_MS = 2_000L
        const val DUPLICATE_TAP_RADIUS_PX = 48f
        private const val MAX_SESSION_MESSAGES = 40
        private const val MAX_SUMMARY_CHARS = 500
        private const val PREFS_NAME = "clicky_agent_session"
        private const val KEY_SESSION_MEMORY = "session_memory_json"

        fun isToggleLikeLabel(label: String?): Boolean {
            if (label.isNullOrBlank()) return false
            val l = label.lowercase().trim()
            // Exact-ish toggle control labels (Instagram Follow/Following/Unfollow, Like, Save, etc.)
            val exact = setOf(
                "follow", "following", "unfollow",
                "like", "liked", "unlike",
                "save", "saved", "unsave", "bookmark",
                "mute", "unmute",
                "subscribe", "subscribed", "unsubscribe",
                "notify", "notifications", "turn on notifications", "turn off notifications",
                "on", "off",
            )
            if (l in exact) return true
            return TOGGLE_SUBSTRINGS.any { needle ->
                l == needle || l.startsWith("$needle ") || l.endsWith(" $needle") ||
                    l.contains(" $needle ")
            }
        }

        private val TOGGLE_SUBSTRINGS = listOf(
            "follow", "unfollow", "following",
            "like", "unlike",
            "save", "saved",
            "notification", "notify",
            "mute", "unmute",
            "subscribe",
            "switch",
        )
    }
}
