package com.drishti.eval

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persists agent runs under app-private storage for offline analysis.
 *
 * Layout:
 *   filesDir/runs/<runId>/
 *     manifest.json
 *     events.jsonl
 *     summary.txt
 *     trees/observe_NNN.json
 *     shots/observe_NNN.png   (optional)
 */
class RunStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "runs").also { it.mkdirs() }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    /** One JSON object per line — must not be pretty-printed. */
    private val jsonl = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun listRuns(limit: Int = 30): List<RunManifest> {
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val mf = File(dir, "manifest.json")
                if (!mf.exists()) return@mapNotNull null
                runCatching { json.decodeFromString<RunManifest>(mf.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.startedAtMs }
            ?.take(limit)
            .orEmpty()
    }

    fun runDir(runId: String): File = File(root, runId)

    fun readEvents(runId: String): List<TraceEvent> {
        val file = File(runDir(runId), "events.jsonl")
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val lines = text.lines().filter { it.isNotBlank() }
        val lineParsed = lines.mapNotNull { line ->
            runCatching { jsonl.decodeFromString<TraceEvent>(line) }.getOrNull()
        }
        if (lineParsed.size == lines.size && lines.isNotEmpty()) return lineParsed
        // Older runs wrote pretty-printed multi-line JSON into "jsonl".
        return decodeJsonStream(text)
    }

    fun readManifest(runId: String): RunManifest? {
        val mf = File(runDir(runId), "manifest.json")
        if (!mf.exists()) return null
        return runCatching { json.decodeFromString<RunManifest>(mf.readText()) }.getOrNull()
    }

    fun absolutePathHint(): String = root.absolutePath

    fun pruneOldRuns(keep: Int = MAX_RUNS) {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
            .sortedByDescending { it.lastModified() }
        dirs.drop(keep).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    fun begin(task: String): RunSession {
        pruneOldRuns()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val runId = "${stamp}_${UUID.randomUUID().toString().take(8)}"
        val dir = File(root, runId).also {
            it.mkdirs()
            File(it, "trees").mkdirs()
            File(it, "shots").mkdirs()
        }
        val manifest = RunManifest(
            runId = runId,
            task = task,
            startedAtMs = System.currentTimeMillis(),
            dirName = dir.name,
        )
        writeManifest(manifest)
        appendEvent(
            runId,
            TraceEvent(
                tsMs = manifest.startedAtMs,
                type = "run_start",
                message = task,
            ),
        )
        return RunSession(this, manifest)
    }

    internal fun writeManifest(manifest: RunManifest) {
        File(runDir(manifest.runId), "manifest.json").writeText(json.encodeToString(manifest))
    }

    internal fun appendEvent(runId: String, event: TraceEvent) {
        File(runDir(runId), "events.jsonl").appendText(jsonl.encodeToString(event) + "\n")
    }

    private fun decodeJsonStream(text: String): List<TraceEvent> {
        val out = mutableListOf<TraceEvent>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            var depth = 0
            var inString = false
            var escape = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val slice = text.substring(start, i + 1)
                                runCatching {
                                    out.add(json.decodeFromString<TraceEvent>(slice))
                                }
                                i++
                                break
                            }
                        }
                    }
                }
                i++
            }
            if (depth != 0) break
        }
        return out
    }

    internal fun writeTree(runId: String, step: Int, treeJson: String): String {
        val rel = "trees/observe_${step.toString().padStart(3, '0')}.json"
        File(runDir(runId), rel).writeText(treeJson)
        return rel
    }

    internal fun writeShotBase64(runId: String, step: Int, base64Png: String): String? {
        return try {
            val bytes = Base64.decode(base64Png, Base64.DEFAULT)
            val rel = "shots/observe_${step.toString().padStart(3, '0')}.png"
            File(runDir(runId), rel).writeBytes(bytes)
            rel
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write screenshot", e)
            null
        }
    }

    internal fun writeSummary(runId: String, text: String) {
        File(runDir(runId), "summary.txt").writeText(text)
    }

    companion object {
        private const val TAG = "RunStore"
        const val MAX_RUNS = 30

        @Volatile
        private var instance: RunStore? = null

        fun get(context: Context): RunStore {
            return instance ?: synchronized(this) {
                instance ?: RunStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

class RunSession internal constructor(
    private val store: RunStore,
    private val manifest: RunManifest,
) {
    val runId: String get() = manifest.runId
    private var observeStep = 0
    private var lastFingerprint: String = ""

    fun recordObserve(
        packageName: String,
        treeJson: String,
        fingerprint: String,
        actionableCount: Int,
        screenshotBase64: String? = null,
        note: String? = null,
    ) {
        observeStep++
        val treeFile = store.writeTree(manifest.runId, observeStep, treeJson)
        val shotFile = screenshotBase64?.let { store.writeShotBase64(manifest.runId, observeStep, it) }
        val changed = lastFingerprint.isNotEmpty() && fingerprint != lastFingerprint
        if (changed) manifest.treeChangeCount++
        store.appendEvent(
            manifest.runId,
            TraceEvent(
                tsMs = System.currentTimeMillis(),
                type = "observe",
                step = observeStep,
                packageName = packageName,
                fingerprint = fingerprint.take(500),
                fingerprintBefore = lastFingerprint.take(500).ifBlank { null },
                treeChanged = if (lastFingerprint.isEmpty()) null else changed,
                actionableCount = actionableCount,
                treeFile = treeFile,
                shotFile = shotFile,
                message = note ?: TreeDiff.shortLabel(
                    TreeDiff.summarize(lastFingerprint, fingerprint),
                ),
            ),
        )
        lastFingerprint = fingerprint
        store.writeManifest(manifest)
    }

    fun recordLlm(
        provider: String?,
        toolNames: List<String>,
        durationMs: Long,
        error: String? = null,
    ) {
        manifest.llmCalls++
        store.appendEvent(
            manifest.runId,
            TraceEvent(
                tsMs = System.currentTimeMillis(),
                type = if (error != null) "llm_error" else "llm",
                provider = provider,
                message = error ?: "tools=${toolNames.joinToString(",")}",
                durationMs = durationMs,
            ),
        )
        store.writeManifest(manifest)
    }

    fun recordTool(
        step: Int,
        name: String,
        args: String,
        result: String,
        ok: Boolean,
        fingerprintBefore: String,
        fingerprintAfter: String,
    ) {
        manifest.stepCount = maxOf(manifest.stepCount, step)
        val diff = TreeDiff.summarize(fingerprintBefore, fingerprintAfter)
        if (diff.changed) manifest.treeChangeCount++
        store.appendEvent(
            manifest.runId,
            TraceEvent(
                tsMs = System.currentTimeMillis(),
                type = "tool",
                step = step,
                toolName = name,
                toolArgs = args.take(500),
                toolResult = result.take(500),
                toolOk = ok,
                fingerprintBefore = fingerprintBefore.take(500),
                fingerprintAfter = fingerprintAfter.take(500),
                treeChanged = diff.changed,
                message = TreeDiff.shortLabel(diff),
            ),
        )
        lastFingerprint = fingerprintAfter
        store.writeManifest(manifest)
    }

    fun recordNote(type: String, message: String) {
        if (type == "stuck") manifest.stuck = true
        store.appendEvent(
            manifest.runId,
            TraceEvent(
                tsMs = System.currentTimeMillis(),
                type = type,
                message = message,
            ),
        )
        store.writeManifest(manifest)
    }

    fun end(status: String, error: String? = null) {
        manifest.status = status
        manifest.error = error
        manifest.endedAtMs = System.currentTimeMillis()
        store.writeManifest(manifest)
        store.appendEvent(
            manifest.runId,
            TraceEvent(
                tsMs = manifest.endedAtMs!!,
                type = "run_end",
                message = status,
            ),
        )
        val events = store.readEvents(manifest.runId)
        val sb = StringBuilder()
        sb.appendLine("runId=${manifest.runId}")
        sb.appendLine("task=${manifest.task}")
        sb.appendLine("status=${manifest.status}")
        sb.appendLine("steps=${manifest.stepCount} llmCalls=${manifest.llmCalls} treeChanges=${manifest.treeChangeCount}")
        sb.appendLine("stuck=${manifest.stuck}")
        error?.let { sb.appendLine("error=$it") }
        sb.appendLine()
        sb.appendLine("timeline:")
        events.forEach { e ->
            sb.appendLine(
                "- ${e.type}" +
                    (e.step?.let { " #$it" } ?: "") +
                    (e.toolName?.let { " $it" } ?: "") +
                    (e.treeChanged?.let { " changed=$it" } ?: "") +
                    (e.message?.let { " | $it" } ?: ""),
            )
        }
        store.writeSummary(manifest.runId, sb.toString())
        Log.i(TAG, "Run saved: ${store.runDir(manifest.runId).absolutePath}")
    }

    companion object {
        private const val TAG = "RunSession"
    }
}
