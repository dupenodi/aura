package com.clicky.accessibility

import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.clicky.accessibility.model.ScreenContext
import com.clicky.accessibility.model.UiNode
import com.clicky.debug.RingBufferLogger
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Walks the accessibility tree (BFS, max depth 25), compresses to interactive /
 * labeled visible nodes, caps at 60 preferring Search/editable + clickable, and
 * formats a prompt-ready text representation with center coords for taps.
 */
class ScreenReader(
    private val serviceProvider: () -> ClickyAccessibilityService?,
) {
    fun captureContext(): ScreenContext? {
        val service = serviceProvider()
        if (service == null) {
            ClickyAccessibilityService.showEnableToast()
            RingBufferLogger.tool("ScreenReader.captureContext", "service=null")
            return null
        }

        return runCatching {
            val root = service.rootInActiveWindow
            if (root == null) {
                RingBufferLogger.tool("ScreenReader.captureContext", "root=null")
                return null
            }

            try {
                val (screenW, screenH) = service.screenSize()
                val packageName = root.packageName?.toString().orEmpty()
                val activityName = root.className?.toString()?.takeIf {
                    it.contains("Activity", ignoreCase = true)
                }

                val candidates = walkTree(root, screenW, screenH)
                val selected = selectTopNodes(candidates, MAX_NODES)
                val withIds = selected.mapIndexed { index, node ->
                    node.copy(id = index + 1)
                }
                val prompt = buildPromptText(packageName, screenW, screenH, withIds)
                val signature = buildScreenSignature(packageName, withIds)
                // Keep stable id → node mapping for later ACTION_CLICK / tap retry.
                service.updateNodeCache(withIds, packageName = packageName, signature = signature)

                val ctx = ScreenContext(
                    packageName = packageName,
                    activityName = activityName,
                    screenW = screenW,
                    screenH = screenH,
                    nodes = withIds,
                    asPromptText = prompt,
                )
                RingBufferLogger.tool(
                    "ScreenReader.captureContext",
                    "ok nodes=${withIds.size} pkg=$packageName " +
                        "searchTagged=${withIds.count { isSearchLabeled(it) }}",
                )
                ctx
            } finally {
                runCatching { root.recycle() }
            }
        }.getOrElse { e ->
            RingBufferLogger.tool("ScreenReader.captureContext", "error=${e.message}")
            null
        }
    }

    private fun walkTree(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): List<UiNode> {
        data class Frame(val node: AccessibilityNodeInfo, val depth: Int, val isRoot: Boolean)

        val out = ArrayList<UiNode>()
        val queue = ArrayDeque<Frame>()
        queue.add(Frame(root, 0, isRoot = true))

        while (queue.isNotEmpty()) {
            val (node, depth, isRoot) = queue.removeFirst()
            runCatching {
                if (shouldKeep(node, screenW, screenH)) {
                    out.add(toUiNode(tempId = 0, node = node))
                }
                if (depth < MAX_DEPTH) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        queue.add(Frame(child, depth + 1, isRoot = false))
                    }
                }
            }
            // Recycle non-root nodes obtained via getChild; root is recycled by caller.
            if (!isRoot) {
                runCatching { node.recycle() }
            }
        }

        return out
    }

    private fun shouldKeep(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        if (bounds.right < 0 || bounds.bottom < 0) return false
        if (bounds.left >= screenW || bounds.top >= screenH) return false

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hasLabel = text.isNotEmpty() || desc.isNotEmpty()
        // Always keep Search-labeled nodes (Swiggy "Search for 'Biryani'") even if
        // not clickable/editable flags are missing on some builds.
        val searchLabeled = text.contains("search", ignoreCase = true) ||
            desc.contains("search", ignoreCase = true)

        return searchLabeled ||
            node.isClickable ||
            node.isEditable ||
            node.isScrollable ||
            node.isCheckable ||
            (hasLabel && node.isVisibleToUser)
    }

    private fun toUiNode(tempId: Int, node: AccessibilityNodeInfo): UiNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val retained = AccessibilityNodeInfo.obtain(node)
        return UiNode(
            id = tempId,
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = Rect(bounds),
            centerX = bounds.centerX(),
            centerY = bounds.centerY(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isVisibleToUser = node.isVisibleToUser,
            nodeRef = WeakReference(retained),
        )
    }

    private fun selectTopNodes(candidates: List<UiNode>, max: Int): List<UiNode> {
        if (candidates.size <= max) return candidates
        // Force-keep Search-labeled nodes so they survive the 60-node cap.
        val mustKeep = candidates.filter { isSearchLabeled(it) }
            .sortedWith(
                compareByDescending<UiNode> { nodePriority(it) }
                    .thenByDescending { it.area },
            )
        val ranked = candidates.sortedWith(
            compareByDescending<UiNode> { nodePriority(it) }
                .thenByDescending { it.area },
        )
        val kept = LinkedHashSet<UiNode>()
        for (n in mustKeep) {
            if (kept.size >= max) break
            kept.add(n)
        }
        for (n in ranked) {
            if (kept.size >= max) break
            kept.add(n)
        }
        val dropped = ranked.filter { it !in kept }
        dropped.forEach { n ->
            runCatching { n.nodeRef.get()?.recycle() }
        }
        return kept.sortedBy { it.bounds.top * 10_000L + it.bounds.left }
    }

    private fun nodePriority(n: UiNode): Int {
        val searchLabeled = isSearchLabeled(n)
        val editable = n.isEditable ||
            (n.className?.contains("EditText", ignoreCase = true) == true)
        return when {
            // Highest: explicit Search text/cd — must beat address chips.
            searchLabeled && editable -> 10
            searchLabeled -> 9
            editable -> 3
            n.isClickable -> 2
            n.isCheckable -> 1
            else -> 0
        }
    }

    private fun isSearchLabeled(n: UiNode): Boolean {
        val text = n.text?.trim().orEmpty()
        val desc = n.contentDescription?.trim().orEmpty()
        return text.contains("search", ignoreCase = true) ||
            desc.contains("search", ignoreCase = true)
    }

    private fun buildPromptText(
        packageName: String,
        screenW: Int,
        screenH: Int,
        nodes: List<UiNode>,
    ): String {
        val sb = StringBuilder()
        // packageName must always be visible to the model every turn.
        sb.append("packageName=").append(packageName.ifBlank { "unknown" })
        sb.append(" app=").append(packageName.ifBlank { "unknown" })
        sb.append(' ').append(screenW).append('x').append(screenH)
        for (n in nodes) {
            sb.append('\n')
            sb.append('[').append(n.id).append("] ")
            if (isSearchLabeled(n) || n.isEditable) {
                sb.append("[SEARCH_FIELD] ")
            }
            sb.append(roleLabel(n))
            // Keep search placeholders / dish hints in the compressed tree.
            val label = nodeDisplayLabel(n)
            if (label.isNotEmpty()) {
                sb.append(" \"").append(label.replace("\"", "'").take(80)).append('"')
            }
            if (n.isEditable) sb.append(" editable")
            if (n.isFocused) sb.append(" focused")
            if (n.isChecked) sb.append(" checked")
            sb.append(' ')
            sb.append('(').append(n.bounds.left).append(',').append(n.bounds.top).append(')')
            sb.append('-')
            sb.append('(').append(n.bounds.right).append(',').append(n.bounds.bottom).append(')')
            sb.append(" center=").append(n.centerX).append(',').append(n.centerY)
        }
        return sb.toString()
    }

    private fun nodeDisplayLabel(n: UiNode): String {
        val text = n.text?.trim().orEmpty()
        val desc = n.contentDescription?.trim().orEmpty()
        // Prefer the longer of text/desc so "Search for 'Biryani'" isn't dropped for a short icon desc.
        return when {
            text.isNotEmpty() && desc.isNotEmpty() ->
                if (text.length >= desc.length) text else desc
            text.isNotEmpty() -> text
            else -> desc
        }
    }

    private fun roleLabel(n: UiNode): String {
        val cls = n.className.orEmpty()
        val label = nodeDisplayLabel(n).lowercase()
        val searchLike = label.contains("search")
        return when {
            // Tag editable fields clearly so the model prefers them over address chips.
            n.isEditable || cls.contains("EditText", ignoreCase = true) -> "Edit/Search"
            searchLike && n.isClickable -> "Search"
            searchLike -> "Search"
            cls.contains("Button", ignoreCase = true) -> "Button"
            cls.contains("Image", ignoreCase = true) -> "Image"
            cls.contains("CheckBox", ignoreCase = true) -> "CheckBox"
            cls.contains("Switch", ignoreCase = true) -> "Switch"
            cls.contains("SeekBar", ignoreCase = true) -> "SeekBar"
            n.isScrollable -> "Scroll"
            n.isClickable -> "Clickable"
            else -> cls.substringAfterLast('.').ifBlank { "Node" }
        }
    }

    private fun buildScreenSignature(packageName: String, nodes: List<UiNode>): String {
        val parts = nodes.take(24).joinToString("|") { n ->
            val label = (n.text ?: n.contentDescription ?: "").take(40)
            "${n.id}:${n.bounds.flattenToString()}:$label"
        }
        return "$packageName#${nodes.size}#$parts"
    }

    companion object {
        const val MAX_DEPTH = 25
        const val MAX_NODES = 60
    }
}

private fun ClickyAccessibilityService.screenSize(): Pair<Int, Int> {
    val wm = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}
