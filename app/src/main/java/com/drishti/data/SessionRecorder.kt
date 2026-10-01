package com.drishti.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes each guided session to `files/sessions/<time>.jsonl`: every screen as the model saw
 * it, every step it chose, timings, tokens and outcomes.
 *
 * This is how a bad session on a real phone becomes something to fix: pull the file
 * (`adb pull /data/data/com.drishti/files/sessions`) and read exactly what happened. Only
 * the last [KEEP] sessions are kept, and they never leave the phone on their own.
 */
class SessionRecorder(context: Context, task: String) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val file = File(dir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl")
    private val started = System.currentTimeMillis()

    init {
        prune()
        write("task", mapOf("task" to task))
    }

    fun record(kind: String, data: Map<String, Any?>) = write(kind, data)

    private fun write(kind: String, data: Map<String, Any?>) {
        val line = buildJsonObject {
            put("t", System.currentTimeMillis() - started)
            put("kind", kind)
            data.forEach { (k, v) -> put(k, toJson(v)) }
        }
        runCatching { file.appendText(line.toString() + "\n") }
            .onFailure { Log.w("SessionRecorder", "write failed: ${it.message}") }
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun prune() {
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
    }

    companion object {
        private const val KEEP = 30

        fun clear(context: Context) {
            File(context.filesDir, "sessions").listFiles()?.forEach { it.delete() }
        }
    }
}
