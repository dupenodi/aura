package com.drishti.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A task the user saved to run again.
 *
 * [learnedRoute] is the interesting part: after a routine succeeds we keep the steps that
 * actually worked, and replay them as a hint next time. That is the local "supermemory" —
 * the second run of a routine starts from what happened last time instead of rediscovering
 * the same path, which makes it both faster and far more reliable.
 */
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val task: String,
    val learnedRoute: List<String> = emptyList(),
    val runCount: Int = 0,
    val lastRunAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val subtitle: String
        get() = when {
            runCount == 0 -> "Never run"
            learnedRoute.isNotEmpty() -> "Run $runCount× · knows the way"
            else -> "Run $runCount×"
        }
}

/**
 * Locally stored routines and what Aura has learned about running them.
 * Nothing here leaves the device.
 */
class RoutineStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "routines.json")

    private val _routines = MutableStateFlow(load())
    val routines: StateFlow<List<Routine>> = _routines

    fun save(routine: Routine) {
        val next = _routines.value.filterNot { it.id == routine.id } + routine
        _routines.value = next.sortedByDescending { it.lastRunAt }
        persist()
    }

    fun add(name: String, task: String): Routine {
        val routine = Routine(name = name.trim(), task = task.trim())
        save(routine)
        return routine
    }

    fun delete(id: String) {
        _routines.value = _routines.value.filterNot { it.id == id }
        persist()
    }

    fun byId(id: String): Routine? = _routines.value.firstOrNull { it.id == id }

    /** Finds a saved routine whose task matches what the user just asked for. */
    fun matching(task: String): Routine? {
        val needle = task.trim().lowercase()
        if (needle.isEmpty()) return null
        return _routines.value.firstOrNull {
            it.task.lowercase() == needle || it.name.lowercase() == needle
        }
    }

    /** Records a successful run and the route that achieved it. */
    fun recordRun(id: String, route: List<String>) {
        val existing = byId(id) ?: return
        save(
            existing.copy(
                learnedRoute = route.takeIf { it.isNotEmpty() } ?: existing.learnedRoute,
                runCount = existing.runCount + 1,
                lastRunAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun load(): List<Routine> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val steps = o.optJSONArray("route")
                add(
                    Routine(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name"),
                        task = o.optString("task"),
                        learnedRoute = buildList {
                            if (steps != null) {
                                for (j in 0 until steps.length()) add(steps.getString(j))
                            }
                        },
                        runCount = o.optInt("runCount"),
                        lastRunAt = o.optLong("lastRunAt"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    ),
                )
            }
        }.filter { it.task.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun persist() {
        runCatching {
            val array = JSONArray()
            _routines.value.forEach { r ->
                array.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("name", r.name)
                        put("task", r.task)
                        put("route", JSONArray(r.learnedRoute))
                        put("runCount", r.runCount)
                        put("lastRunAt", r.lastRunAt)
                        put("createdAt", r.createdAt)
                    },
                )
            }
            file.writeText(array.toString())
        }
    }

    companion object {
        @Volatile
        private var instance: RoutineStore? = null

        fun get(context: Context): RoutineStore =
            instance ?: synchronized(this) {
                instance ?: RoutineStore(context).also { instance = it }
            }
    }
}
