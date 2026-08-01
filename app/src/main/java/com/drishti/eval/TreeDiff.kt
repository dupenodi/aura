package com.drishti.eval

/**
 * Lightweight tree fingerprint helpers for change detection / analysis.
 * Fingerprints are the pipe-joined strings produced by TreeJson.fingerprint.
 */
object TreeDiff {
    data class Summary(
        val beforeCount: Int,
        val afterCount: Int,
        val added: List<String>,
        val removed: List<String>,
        val changed: Boolean,
    )

    fun parseEntries(fingerprint: String): Map<String, String> {
        if (fingerprint.isBlank()) return emptyMap()
        return fingerprint.split('|')
            .mapNotNull { part ->
                val idx = part.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val key = part.substring(0, idx) // overlay index
                key to part
            }
            .toMap()
    }

    fun summarize(beforeFp: String, afterFp: String): Summary {
        val before = parseEntries(beforeFp)
        val after = parseEntries(afterFp)
        val added = after.keys.filter { it !in before }.map { after.getValue(it) }
        val removed = before.keys.filter { it !in after }.map { before.getValue(it) }
        val sharedChanged = before.keys.intersect(after.keys).filter { before[it] != after[it] }
        val changed = beforeFp != afterFp
        return Summary(
            beforeCount = before.size,
            afterCount = after.size,
            added = added + sharedChanged.map { "Δ ${after.getValue(it)}" },
            removed = removed,
            changed = changed,
        )
    }

    fun shortLabel(summary: Summary): String {
        if (!summary.changed) return "unchanged (${summary.afterCount} nodes)"
        return "changed ${summary.beforeCount}→${summary.afterCount} " +
            "+${summary.added.size} -${summary.removed.size}"
    }
}
