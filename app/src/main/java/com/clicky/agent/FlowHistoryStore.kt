package com.clicky.agent

import android.content.Context
import android.content.SharedPreferences
import com.clicky.debug.RingBufferLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class FlowHistoryStep(
    val name: String,
    val args: String,
    val success: Boolean,
    /** Tool result snippet (used to recover exact tap x,y when promoting to recipes). */
    val resultText: String? = null,
)

@Serializable
data class FlowRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val userUtterance: String,
    val mode: String,
    val summary: String,
    val steps: List<FlowHistoryStep> = emptyList(),
    val packageName: String? = null,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
)

/**
 * Ring buffer of recent agent runs (SharedPreferences — demo memory, not cloud).
 */
@Singleton
class FlowHistoryStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _flows = MutableStateFlow(loadAll())
    val flows: StateFlow<List<FlowRecord>> = _flows.asStateFlow()

    fun list(): List<FlowRecord> = _flows.value

    fun latest(): FlowRecord? = _flows.value.firstOrNull()

    fun get(id: String): FlowRecord? = _flows.value.find { it.id == id }

    fun save(record: FlowRecord) {
        val next = (listOf(record) + _flows.value)
            .distinctBy { it.id }
            .take(MAX_FLOWS)
        persist(next)
        RingBufferLogger.log(
            "flow",
            "saved id=${record.id.take(8)} steps=${record.steps.size} " +
                "completed=${record.completed} ${record.durationMs}ms",
        )
    }

    /**
     * Most recent history entry with real UI actions, suitable to promote into Learned flows.
     * Skips meta save-command runs and runs older than [withinMs].
     */
    fun findPromotable(
        minActionSteps: Int = MIN_PROMOTE_ACTION_STEPS,
        withinMs: Long = PROMOTE_WINDOW_MS,
        nowMs: Long = System.currentTimeMillis(),
        excludeId: String? = null,
    ): FlowRecord? {
        return list().firstOrNull { flow ->
            flow.id != excludeId &&
                flow.timestampMs >= nowMs - withinMs &&
                !SaveFlowIntent.matches(flow.userUtterance) &&
                !isMetaSaveSummary(flow.summary) &&
                actionStepCount(flow) >= minActionSteps
        }
    }

    /** Mark a flow completed for learning and append a user-visible note. */
    fun markPromoted(id: String, note: String = "Saved to learned flows by user"): FlowRecord? {
        val current = get(id) ?: return null
        val summary = buildString {
            append(current.summary.trim())
            if (isNotEmpty() && !endsWith('.')) append('.')
            if (isNotEmpty()) append(' ')
            append(note)
        }.take(500)
        val updated = current.copy(completed = true, summary = summary)
        val next = _flows.value.map { if (it.id == id) updated else it }
        persist(next)
        RingBufferLogger.log("flow", "promoted id=${id.take(8)} note=$note")
        return updated
    }

    fun clear() {
        prefs.edit().remove(KEY_FLOWS).apply()
        _flows.value = emptyList()
        RingBufferLogger.log("flow", "cleared history")
    }

    private fun persist(records: List<FlowRecord>) {
        val encoded = runCatching { json.encodeToString(records) }.getOrElse {
            RingBufferLogger.log("flow", "encode failed: ${it.message}")
            return
        }
        prefs.edit().putString(KEY_FLOWS, encoded).apply()
        _flows.value = records
    }

    private fun loadAll(): List<FlowRecord> {
        val raw = prefs.getString(KEY_FLOWS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<FlowRecord>>(raw).take(MAX_FLOWS)
        }.getOrElse {
            RingBufferLogger.log("flow", "load failed: ${it.message}")
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "clicky_flow_history"
        private const val KEY_FLOWS = "flows_json"
        const val MAX_FLOWS = 30
        const val MIN_PROMOTE_ACTION_STEPS = 4
        const val PROMOTE_WINDOW_MS = 15 * 60 * 1000L

        /** Tools that count as meaningful shopping / navigation progress. */
        val MEANINGFUL_ACTION_TOOLS = setOf(
            "open_app",
            "tap",
            "tap_node",
            "type_text",
            "scroll",
            "swipe",
            "press_key",
            "long_press",
        )

        fun actionStepCount(flow: FlowRecord): Int =
            flow.steps.count { it.success && it.name in MEANINGFUL_ACTION_TOOLS }

        fun hasMeaningfulActions(flow: FlowRecord, minSteps: Int = MIN_PROMOTE_ACTION_STEPS): Boolean =
            actionStepCount(flow) >= minSteps

        fun isMetaSaveSummary(summary: String): Boolean {
            val s = summary.lowercase()
            return s.contains("saved to learned") ||
                s.contains("saved prior flow") ||
                SaveFlowIntent.matches(summary)
        }
    }
}
