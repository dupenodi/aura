package com.drishti.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** How a task ended, which drives the icon and tone in the history list. */
enum class TaskOutcome { Completed, Stopped, Cancelled }

data class TaskRecord(
    val id: String,
    val task: String,
    val steps: Int,
    val outcome: TaskOutcome,
    /** Why it stopped, when it didn't finish — shown instead of the step count. */
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** "4 steps · 12 min ago" — the meta line under the task title. */
    fun metaLine(now: Long = System.currentTimeMillis()): String {
        val when0 = relativeTime(now - timestamp)
        return when (outcome) {
            TaskOutcome.Completed -> "$steps steps · $when0"
            TaskOutcome.Stopped -> "${detail ?: "Stopped"} · $when0"
            TaskOutcome.Cancelled -> "Cancelled · $when0"
        }
    }

    private fun relativeTime(deltaMs: Long): String {
        val mins = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "yesterday"
            days < 7 -> "${days}d ago"
            else -> "last week"
        }
    }
}

/**
 * The user-facing record of what Aura did, kept on device for seven days.
 *
 * This is the product's trust surface — deliberately plain-language and separate from
 * the internal run traces, which are diagnostics rather than something a person reads.
 */
class TaskHistory private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "task_history.json")

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<TaskRecord>> = _records

    fun add(record: TaskRecord) {
        val next = (listOf(record) + _records.value).take(MAX_RECORDS)
        _records.value = next
        save(next)
    }

    fun clear() {
        _records.value = emptyList()
        runCatching { file.delete() }
    }

    private fun load(): List<TaskRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val ts = o.optLong("ts")
                // Seven-day retention is a promise made on the privacy screen; enforce on read.
                if (ts < cutoff) continue
                add(
                    TaskRecord(
                        id = o.optString("id"),
                        task = o.optString("task"),
                        steps = o.optInt("steps"),
                        outcome = runCatching { TaskOutcome.valueOf(o.optString("outcome")) }
                            .getOrDefault(TaskOutcome.Completed),
                        detail = o.optString("detail").takeIf { it.isNotBlank() },
                        timestamp = ts,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun save(records: List<TaskRecord>) {
        runCatching {
            val array = JSONArray()
            records.forEach { r ->
                array.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("task", r.task)
                        put("steps", r.steps)
                        put("outcome", r.outcome.name)
                        put("detail", r.detail ?: "")
                        put("ts", r.timestamp)
                    },
                )
            }
            file.writeText(array.toString())
        }
    }

    companion object {
        private const val MAX_RECORDS = 50
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(7)

        @Volatile
        private var instance: TaskHistory? = null

        fun get(context: Context): TaskHistory =
            instance ?: synchronized(this) {
                instance ?: TaskHistory(context).also { instance = it }
            }
    }
}
