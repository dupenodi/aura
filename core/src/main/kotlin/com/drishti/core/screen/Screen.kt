package com.drishti.core.screen

import kotlinx.serialization.Serializable

/** A rectangle in absolute screen pixels. */
@Serializable
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()

    fun contains(o: Bounds): Boolean = l <= o.l && t <= o.t && r >= o.r && b >= o.b
    fun contains(x: Int, y: Int): Boolean = x in l until r && y in t until b
    fun intersects(o: Bounds): Boolean = l < o.r && o.l < r && t < o.b && o.t < b

    fun intersect(o: Bounds): Bounds? {
        if (!intersects(o)) return null
        return Bounds(maxOf(l, o.l), maxOf(t, o.t), minOf(r, o.r), minOf(b, o.b))
    }

    fun iou(o: Bounds): Float {
        val i = intersect(o)?.area ?: return 0f
        val u = area + o.area - i
        return if (u <= 0) 0f else i.toFloat() / u
    }

    override fun toString(): String = "[$l,$t][$r,$b]"
}

/**
 * One accessibility node, copied out of the platform into plain data.
 *
 * Kept free of android.* so the whole agent — encoding, deciding, verifying — runs and is
 * tested on a JVM, and so a session can be recorded to JSON and replayed exactly.
 */
@Serializable
data class UiNode(
    /** Short class name: "TextView", "Switch", "RecyclerView". */
    val cls: String = "",
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    /** Resource entry name without the package: "switch_widget", "search_src_text". */
    val viewId: String? = null,
    /** Platform stateDescription, e.g. "On", "50 percent". */
    val state: String? = null,
    val bounds: Bounds,
    val flags: Int = 0,
    val children: List<UiNode> = emptyList(),
    /** Device-side handle so the live node can be re-read; -1 when there is none. */
    val key: Int = -1,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    val clickable: Boolean get() = has(CLICKABLE)
    val longClickable: Boolean get() = has(LONG_CLICKABLE)
    val editable: Boolean get() = has(EDITABLE)
    val scrollable: Boolean get() = has(SCROLLABLE)
    val checkable: Boolean get() = has(CHECKABLE)
    val checked: Boolean get() = has(CHECKED)
    val selected: Boolean get() = has(SELECTED)
    val disabled: Boolean get() = has(DISABLED)
    val focused: Boolean get() = has(FOCUSED)
    val password: Boolean get() = has(PASSWORD)
    val heading: Boolean get() = has(HEADING)

    fun walk(visit: (UiNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
    }

    companion object {
        const val CLICKABLE = 1
        const val LONG_CLICKABLE = 1 shl 1
        const val EDITABLE = 1 shl 2
        const val SCROLLABLE = 1 shl 3
        const val CHECKABLE = 1 shl 4
        const val CHECKED = 1 shl 5
        const val SELECTED = 1 shl 6
        const val DISABLED = 1 shl 7
        const val FOCUSED = 1 shl 8
        const val PASSWORD = 1 shl 9
        const val HEADING = 1 shl 10
        const val SCROLL_FORWARD = 1 shl 11
        const val SCROLL_BACKWARD = 1 shl 12
    }
}

/** Everything the agent may know about what is on screen right now. */
@Serializable
data class Screen(
    val pkg: String,
    val width: Int,
    val height: Int,
    val roots: List<UiNode>,
    val appLabel: String? = null,
    val activity: String? = null,
    /** Window or pane title when the app provides one. */
    val title: String? = null,
    val keyboardVisible: Boolean = false,
    val timeMs: Long = 0L,
) {
    val bounds: Bounds get() = Bounds(0, 0, width, height)

    fun nodeCount(): Int {
        var n = 0
        roots.forEach { r -> r.walk { n++ } }
        return n
    }
}

/**
 * Something the platform told us the user did, or that the screen did.
 *
 * Click events carry the node that was pressed, which is how a step is known to be done:
 * comparing that node with the one we pointed at is exact where diffing the screen is a guess.
 */
@Serializable
data class UiEvent(
    val type: Type,
    val pkg: String = "",
    val cls: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val bounds: Bounds? = null,
    val timeMs: Long = 0L,
) {
    enum class Type {
        CLICK,
        LONG_CLICK,
        TEXT_CHANGED,
        SCROLLED,
        /** A new activity, dialog or panel came to the front. */
        WINDOW_STATE,
        /** Windows were added, removed or reordered (keyboard, shade, popup). */
        WINDOWS_CHANGED,
        CONTENT_CHANGED,
        FOCUSED,
        SELECTED,
    }

    /** User-initiated, as opposed to the screen redrawing itself. */
    val isInteraction: Boolean
        get() = type == Type.CLICK || type == Type.LONG_CLICK || type == Type.TEXT_CHANGED ||
            type == Type.SCROLLED || type == Type.SELECTED
}
