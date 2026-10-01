package com.drishti.core.sim

import com.drishti.core.screen.Bounds
import com.drishti.core.screen.UiNode

/**
 * Lays out a simulated screen the way Pixel apps do, so the trees the agent sees have the
 * same shapes as real ones: clickable rows whose words are in child TextViews, switches
 * inside rows, icon buttons with only a content description, a RecyclerView that only holds
 * the rows that fit and reports whether it can scroll further.
 */
class ScreenBuilder(
    val width: Int,
    val height: Int,
    private val state: SimState,
    private val screenName: String,
    private val newKey: () -> Int,
) {
    internal val handlers = HashMap<Int, Tap>()
    internal val fields = HashMap<Int, String>()
    private val nodes = mutableListOf<UiNode>()
    private var y = STATUS_H
    private val bottom get() = height - NAV_H - if (state.keyboard) KEYBOARD_H else 0

    fun root(): UiNode = UiNode(cls = "FrameLayout", bounds = Bounds(0, 0, width, height), children = nodes.toList(), key = newKey())

    fun statusBar() {
        nodes += UiNode(cls = "TextView", text = "9:41", bounds = Bounds(40, 20, 200, 90), key = newKey())
    }

    // ---- Building blocks --------------------------------------------------------------------

    fun appBar(title: String?, back: Boolean = true, actions: List<IconAction> = emptyList()) {
        val top = y
        if (back) {
            nodes += pressable(
                UiNode(cls = "ImageButton", desc = "Navigate up", bounds = Bounds(16, top + 30, 160, top + 160), flags = UiNode.CLICKABLE),
                Tap.Back,
            )
        }
        title?.let {
            nodes += UiNode(
                cls = "TextView", text = it, flags = UiNode.HEADING,
                bounds = Bounds(if (back) 180 else 48, top + 50, width - 160 * actions.size - 40, top + 140), key = newKey(),
            )
        }
        actions.forEachIndexed { i, a ->
            val right = width - 16 - i * 150
            nodes += pressable(
                UiNode(cls = "ImageButton", desc = a.desc, bounds = Bounds(right - 140, top + 30, right, top + 160), flags = UiNode.CLICKABLE),
                a.tap,
            )
        }
        y += APPBAR_H
    }

    /** A large page title, as on the top of the Settings home and Pixel subpages. */
    fun bigTitle(text: String) {
        nodes += UiNode(cls = "TextView", text = text, flags = UiNode.HEADING, bounds = Bounds(48, y, width - 48, y + 180), key = newKey())
        y += 200
    }

    fun searchBar(hint: String, field: String, tap: Tap = Tap.Focus(field)) {
        val value = state.text[field]
        val focused = state.focusedField == field
        val flags = UiNode.CLICKABLE or UiNode.EDITABLE or (if (focused) UiNode.FOCUSED else 0)
        nodes += pressable(
            UiNode(cls = "EditText", text = value ?: hint, hint = hint, bounds = Bounds(40, y + 20, width - 40, y + 160), flags = flags),
            tap,
        ).also { fields[it.key] = field }
        y += 180
    }

    /** A text field at a fixed position (chat composers sit just above the keyboard). */
    fun fieldNode(hint: String, field: String, value: String?, focused: Boolean, b: Bounds) {
        val flags = UiNode.CLICKABLE or UiNode.EDITABLE or (if (focused) UiNode.FOCUSED else 0)
        nodes += pressable(
            UiNode(cls = "EditText", text = value?.takeIf { it.isNotEmpty() } ?: hint, hint = hint, bounds = b, flags = flags),
            Tap.Focus(field),
        ).also { fields[it.key] = field }
    }

    /** Looks like a search box but is a button that opens the real search screen (Settings). */
    fun searchButton(label: String, tap: Tap) {
        nodes += pressable(
            UiNode(
                cls = "LinearLayout", bounds = Bounds(40, y + 20, width - 40, y + 160), flags = UiNode.CLICKABLE,
                children = listOf(UiNode(cls = "TextView", text = label, bounds = Bounds(160, y + 60, width - 100, y + 120))),
            ),
            tap,
        )
        y += 180
    }

    fun text(text: String, h: Int = 120, heading: Boolean = false) {
        nodes += UiNode(cls = "TextView", text = text, bounds = Bounds(48, y, width - 48, y + h), flags = if (heading) UiNode.HEADING else 0, key = newKey())
        y += h
    }

    fun button(text: String, tap: Tap, h: Int = 150) {
        nodes += pressable(
            UiNode(cls = "Button", text = text, bounds = Bounds(48, y + 15, width - 48, y + h - 15), flags = UiNode.CLICKABLE),
            tap,
        )
        y += h
    }

    fun icon(desc: String, bounds: Bounds, tap: Tap, cls: String = "ImageView") {
        nodes += pressable(UiNode(cls = cls, desc = desc, bounds = bounds, flags = UiNode.CLICKABLE), tap)
    }

    fun fab(desc: String, tap: Tap) = icon(desc, Bounds(width - 230, bottom - 230, width - 50, bottom - 50), tap, cls = "ImageButton")

    /** Bottom navigation / tabs bar. */
    fun bottomTabs(tabs: List<Pair<String, Tap>>, selected: Int) {
        val w = width / tabs.size
        val top = bottom - 200
        tabs.forEachIndexed { i, (label, tap) ->
            nodes += pressable(
                UiNode(
                    cls = "FrameLayout", desc = label, bounds = Bounds(i * w, top, (i + 1) * w, bottom),
                    flags = UiNode.CLICKABLE or (if (i == selected) UiNode.SELECTED else 0),
                    children = listOf(UiNode(cls = "TextView", text = label, bounds = Bounds(i * w + 20, top + 120, (i + 1) * w - 20, top + 180))),
                ),
                tap,
            )
        }
    }

    /** The launcher's app grid, plus the favourites row and the search pill. */
    fun appGrid(apps: List<Pair<String, String>>, columns: Int = 4) {
        val cell = width / columns
        apps.forEachIndexed { i, (label, pkg) ->
            val col = i % columns
            val row = i / columns
            val top = y + row * 300
            nodes += pressable(
                UiNode(
                    cls = "TextView", text = label, desc = label,
                    bounds = Bounds(col * cell, top, (col + 1) * cell, top + 280), flags = UiNode.CLICKABLE or UiNode.LONG_CLICKABLE,
                ),
                Tap.Launch(pkg),
            )
        }
        y += ((apps.size + columns - 1) / columns) * 300
    }

    /**
     * A scrolling list taking the rest of the screen (minus [reserveBottom] for tabs or a
     * FAB). Only rows that fit are in the tree — as in a RecyclerView — and the list says
     * which way it can still scroll.
     */
    fun list(reserveBottom: Int = 0, rowHeight: Int = 200, rows: ListScope.() -> Unit) {
        val scope = ListScope().apply(rows)
        val top = y
        val listBottom = bottom - reserveBottom
        val perPage = ((listBottom - top) / rowHeight).coerceAtLeast(1)
        val maxOffset = (scope.rows.size - perPage).coerceAtLeast(0)
        val offset = ((state.scroll[screenName] ?: 0) * (perPage - 1).coerceAtLeast(1)).coerceAtMost(maxOffset)
        val visible = scope.rows.drop(offset).take(perPage)
        val kids = visible.mapIndexed { i, r -> r.node(top + i * rowHeight, rowHeight) }
        var flags = UiNode.SCROLLABLE
        if (offset < maxOffset) flags = flags or UiNode.SCROLL_FORWARD
        if (offset > 0) flags = flags or UiNode.SCROLL_BACKWARD
        nodes += UiNode(cls = "RecyclerView", viewId = "recycler_view", bounds = Bounds(0, top, width, listBottom), flags = flags, children = kids, key = newKey())
        y = listBottom
    }

    inner class ListScope {
        internal val rows = mutableListOf<RowSpec>()

        fun row(title: String, summary: String? = null, tap: Tap? = null, icon: Boolean = true) {
            rows += RowSpec(title, summary, tap, null, icon)
        }

        /** A row with a switch at its end. The whole row is the control, as in Settings. */
        fun switchRow(title: String, key: String, summary: String? = null, default: Boolean = false) {
            if (key !in state.toggles) state.toggles[key] = default
            rows += RowSpec(title, summary, Tap.Toggle(key), key, false)
        }

        fun header(text: String) {
            rows += RowSpec(text, null, null, null, false, header = true)
        }

        /** A chat or video row: picture, name, last line. */
        fun item(title: String, subtitle: String? = null, tap: Tap) {
            rows += RowSpec(title, subtitle, tap, null, true)
        }
    }

    inner class RowSpec(
        private val title: String,
        private val summary: String?,
        private val tap: Tap?,
        private val toggleKey: String?,
        private val icon: Boolean,
        private val header: Boolean = false,
    ) {
        fun node(top: Int, h: Int): UiNode {
            if (header) {
                return UiNode(cls = "TextView", text = title, flags = UiNode.HEADING, bounds = Bounds(48, top + 60, width - 48, top + h - 20), key = newKey())
            }
            val left = if (icon) 170 else 48
            val kids = buildList {
                if (icon) add(UiNode(cls = "ImageView", bounds = Bounds(40, top + 50, 140, top + 150), key = newKey()))
                add(UiNode(cls = "TextView", text = title, viewId = "title", bounds = Bounds(left, top + 40, width - 220, top + 105), key = newKey()))
                summary?.let { add(UiNode(cls = "TextView", text = it, viewId = "summary", bounds = Bounds(left, top + 110, width - 220, top + 165), key = newKey())) }
                toggleKey?.let { k ->
                    add(
                        UiNode(
                            cls = "Switch", viewId = "switchWidget",
                            bounds = Bounds(width - 200, top + 55, width - 50, top + 145),
                            flags = UiNode.CHECKABLE or (if (state.on(k)) UiNode.CHECKED else 0), key = newKey(),
                        ),
                    )
                }
            }
            val row = UiNode(
                cls = "LinearLayout", bounds = Bounds(0, top, width, top + h),
                flags = if (tap != null) UiNode.CLICKABLE else 0, children = kids,
            )
            return if (tap != null) pressable(row, tap) else row.copy(key = newKey())
        }
    }

    fun keyboard() = Unit

    private fun pressable(node: UiNode, tap: Tap): UiNode {
        val keyed = node.copy(key = newKey())
        handlers[keyed.key] = tap
        return keyed
    }

    companion object {
        const val STATUS_H = 110
        const val APPBAR_H = 190
        const val NAV_H = 130
        const val KEYBOARD_H = 900
    }
}

data class IconAction(val desc: String, val tap: Tap)
