package com.clicky.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.clicky.debug.RingBufferLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Suspend wrappers around [AccessibilityService.dispatchGesture] and global actions.
 */
class GestureExecutor(
    private val serviceProvider: () -> ClickyAccessibilityService?,
) {
    enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

    suspend fun tap(x: Float, y: Float): Boolean {
        // Round once so float→gesture coords don't truncate inconsistently across OEMs.
        val px = x.roundToInt().toFloat()
        val py = y.roundToInt().toFloat()
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        return dispatch(stroke, "tap($px,$py)")
    }

    suspend fun longPress(x: Float, y: Float): Boolean {
        val px = x.roundToInt().toFloat()
        val py = y.roundToInt().toFloat()
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
        return dispatch(stroke, "longPress($px,$py)")
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val dur = durationMs.coerceIn(50L, 5_000L)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        return dispatch(stroke, "swipe($x1,$y1)->($x2,$y2) ${dur}ms")
    }

    suspend fun scroll(
        direction: ScrollDirection,
        fromNode: AccessibilityNodeInfo? = null,
    ): Boolean {
        val service = requireService("scroll") ?: return false

        // Prefer node ACTION_SCROLL_* when a scrollable node is provided / found.
        val node = fromNode ?: findScrollable(service)
        if (node != null) {
            val action = when (direction) {
                ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val ok = runCatching { node.performAction(action) }.getOrDefault(false)
            if (fromNode == null) runCatching { node.recycle() }
            if (ok) {
                RingBufferLogger.tool("scroll", "nodeAction $direction → true")
                return true
            }
        }

        val (w, h) = service.displaySize()
        val cx = w / 2f
        val cy = h / 2f
        val delta = minOf(w, h) * 0.35f
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        when (direction) {
            ScrollDirection.UP -> {
                x1 = cx; y1 = cy + delta; x2 = cx; y2 = cy - delta
            }
            ScrollDirection.DOWN -> {
                x1 = cx; y1 = cy - delta; x2 = cx; y2 = cy + delta
            }
            ScrollDirection.LEFT -> {
                x1 = cx + delta; y1 = cy; x2 = cx - delta; y2 = cy
            }
            ScrollDirection.RIGHT -> {
                x1 = cx - delta; y1 = cy; x2 = cx + delta; y2 = cy
            }
        }
        return swipe(x1, y1, x2, y2, 350L)
    }

    suspend fun typeText(text: String, targetNodeId: Int? = null): Boolean {
        val service = requireService("typeText") ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                var focused = findFocusedEditable(service)
                if (focused == null && targetNodeId != null) {
                    val target = service.nodeCache[targetNodeId]?.get()
                    if (target != null) {
                        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        // Brief yield for focus to settle is handled by caller; try again.
                        focused = findFocusedEditable(service) ?: target.takeIf { it.isEditable }
                    }
                }
                if (focused == null) {
                    RingBufferLogger.tool("typeText", "no editable node → false")
                    return@runCatching false
                }
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                RingBufferLogger.tool("typeText", "len=${text.length} → $ok")
                ok
            }.getOrElse { e ->
                RingBufferLogger.tool("typeText", "error=${e.message}")
                false
            }
        }
    }

    suspend fun back(): Boolean = performGlobal(AccessibilityService.GLOBAL_ACTION_BACK, "back")

    suspend fun home(): Boolean = performGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "home")

    suspend fun recents(): Boolean =
        performGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")

    suspend fun notifications(): Boolean =
        performGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")

    suspend fun openApp(packageName: String): Boolean {
        val service = requireService("openApp") ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val launch = service.packageManager.getLaunchIntentForPackage(packageName)
                if (launch == null) {
                    RingBufferLogger.tool("openApp", "$packageName → no launch intent")
                    return@runCatching false
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launch)
                RingBufferLogger.tool("openApp", "$packageName → true")
                true
            }.getOrElse { e ->
                RingBufferLogger.tool("openApp", "error=${e.message}")
                false
            }
        }
    }

    suspend fun clickNode(nodeId: Int): Boolean {
        val service = requireService("clickNode") ?: return false
        val density = service.resources.displayMetrics.density
        val node = service.nodeCache[nodeId]?.get()
        if (node == null) {
            // WeakReference dead — fall back to stored center from last capture.
            val stored = service.lastNodes.firstOrNull { it.id == nodeId }
            if (stored != null && !stored.bounds.isEmpty) {
                val (sx, sy) = preciseTapPoint(stored.bounds, density)
                RingBufferLogger.tool(
                    "clickNode",
                    "id=$nodeId missing live ref → stored precise $sx,$sy",
                )
                return tap(sx, sy)
            }
            RingBufferLogger.tool("clickNode", "id=$nodeId missing → false")
            return false
        }
        // Prefer ACTION_CLICK XOR gesture — never both for the same tap_node.
        // A successful ACTION_CLICK must return immediately (no gesture follow-up).
        val clicked = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) {
            RingBufferLogger.tool("clickNode", "id=$nodeId ACTION_CLICK → true")
            return true
        }
        // Fallback only when ACTION_CLICK failed: gesture at precise bounds center.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val (sx, sy) = preciseTapPoint(bounds, density)
            RingBufferLogger.tool("clickNode", "id=$nodeId gesture fallback $sx,$sy")
            return tap(sx, sy)
        }
        val stored = service.lastNodes.firstOrNull { it.id == nodeId }
        if (stored != null && !stored.bounds.isEmpty) {
            val (sx, sy) = preciseTapPoint(stored.bounds, density)
            RingBufferLogger.tool("clickNode", "id=$nodeId stored gesture $sx,$sy")
            return tap(sx, sy)
        }
        RingBufferLogger.tool("clickNode", "id=$nodeId ACTION_CLICK=false no bounds → false")
        return false
    }

    /**
     * Precise tap point for [bounds]:
     * - small targets (<48dp): exact geometric center
     * - huge nodes: slight inward bias from edges before center (avoids chrome/padding)
     * Coordinates are rounded to ints for consistent gesture dispatch.
     */
    fun preciseTapPoint(bounds: Rect, density: Float): Pair<Float, Float> {
        if (bounds.isEmpty) return 0f to 0f
        val minDim = minOf(bounds.width(), bounds.height()).toFloat()
        val smallPx = 48f * density
        val area = bounds.width().toLong() * bounds.height().toLong()
        val working = Rect(bounds)
        // Only huge targets get a tiny inset so the tap sits inside content, not the rim.
        if (minDim >= smallPx && area >= HUGE_NODE_AREA_PX) {
            val insetX = (bounds.width() * 0.03f).coerceIn(2f * density, 10f * density).roundToInt()
            val insetY = (bounds.height() * 0.03f).coerceIn(2f * density, 10f * density).roundToInt()
            if (working.width() > insetX * 2 && working.height() > insetY * 2) {
                working.inset(insetX, insetY)
            }
        }
        val cx = ((working.left + working.right) / 2f).roundToInt().toFloat()
        val cy = ((working.top + working.bottom) / 2f).roundToInt().toFloat()
        return cx to cy
    }

    private suspend fun performGlobal(action: Int, name: String): Boolean {
        val service = requireService(name) ?: return false
        val ok = runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        RingBufferLogger.tool(name, "→ $ok")
        return ok
    }

    private suspend fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        label: String,
    ): Boolean {
        val service = requireService(label) ?: return false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            runCatching {
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            runCatching {
                                RingBufferLogger.tool(label, "→ true")
                                if (cont.isActive) cont.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            runCatching {
                                RingBufferLogger.tool(label, "→ cancelled")
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    },
                    null,
                )
                if (!dispatched) {
                    RingBufferLogger.tool(label, "→ dispatch=false")
                    if (cont.isActive) cont.resume(false)
                }
            }.onFailure { e ->
                RingBufferLogger.tool(label, "error=${e.message}")
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun requireService(op: String): ClickyAccessibilityService? {
        val service = serviceProvider()
        if (service == null) {
            ClickyAccessibilityService.showEnableToast()
            RingBufferLogger.tool(op, "service=null")
        }
        return service
    }

    private fun findFocusedEditable(service: ClickyAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findFirstEditable(root)
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            runCatching { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun findScrollable(service: ClickyAccessibilityService): AccessibilityNodeInfo? {
        // Prefer largest scrollable from last ScreenReader cache.
        val cached = service.nodeCache.values
            .mapNotNull { it.get() }
            .filter { it.isScrollable }
            .maxByOrNull { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                abs(r.width() * r.height())
            }
        if (cached != null) return cached

        val root = service.rootInActiveWindow ?: return null
        return try {
            findFirstScrollable(root)
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollable(child)
            runCatching { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    companion object {
        /** ~100ms reads as a clean tap on picky apps without becoming a long-press. */
        private const val TAP_MS = 100L
        private const val LONG_PRESS_MS = 600L
        /** Area threshold (px²) for optional inward bias on huge nodes. */
        private const val HUGE_NODE_AREA_PX = 180_000L
    }
}

private fun ClickyAccessibilityService.displaySize(): Pair<Int, Int> {
    val dm = resources.displayMetrics
    return dm.widthPixels to dm.heightPixels
}
