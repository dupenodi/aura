package com.drishti.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A task the user saved so they can run it again without typing it out. */
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val task: String,
    val runCount: Int = 0,
    val lastRunAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val subtitle: String
        get() = if (runCount == 0) "Never run" else "Run $runCount×"
}

/** Locally stored routines. Nothing here leaves the device. */
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

    /** Bumps the run counter so the list can order by what the user actually uses. */
    fun recordRun(id: String) {
        val existing = byId(id) ?: return
        save(existing.copy(runCount = existing.runCount + 1, lastRunAt = System.currentTimeMillis()))
    }

    private fun load(): List<Routine> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    Routine(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name"),
                        task = o.optString("task"),
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
