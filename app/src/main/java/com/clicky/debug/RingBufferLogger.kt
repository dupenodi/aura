package com.clicky.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe ring buffer for tool call / result logging.
 * Debug UI (PART 5) can read [snapshot] to display recent entries.
 */
object RingBufferLogger {
    private const val CAPACITY = 200

    data class Entry(
        val timestampMs: Long,
        val tag: String,
        val message: String,
    ) {
        fun formatLine(): String {
            val time = TIME_FORMAT.format(Date(timestampMs))
            return "$time [$tag] $message"
        }
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        runCatching {
            entries.add(Entry(System.currentTimeMillis(), tag, message))
            while (entries.size > CAPACITY) {
                if (entries.isNotEmpty()) {
                    entries.removeAt(0)
                }
            }
            android.util.Log.d("Clicky/$tag", message)
        }
    }

    fun tool(name: String, result: String) {
        log("tool", "$name → $result")
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
    }
}
