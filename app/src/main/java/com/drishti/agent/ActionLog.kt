package com.drishti.agent

import java.util.concurrent.CopyOnWriteArrayList

/** In-memory action log for the current task (not persisted). */
object ActionLog {
    private val entries = CopyOnWriteArrayList<String>()

    fun clear() {
        entries.clear()
    }

    fun append(line: String) {
        entries.add(line)
        if (entries.size > 200) {
            entries.removeAt(0)
        }
    }

    fun snapshot(): String = entries.joinToString("\n")

    fun recent(n: Int = 8): List<String> = entries.takeLast(n)
}
