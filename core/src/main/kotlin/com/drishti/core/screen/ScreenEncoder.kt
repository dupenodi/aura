package com.drishti.core.screen

/** Something on screen the model may point at, by its short reference number. */
data class Target(
    val ref: Int,
    val role: String,
    val label: String,
    val bounds: Bounds,
    /** Device handle of the node, for re-reading live bounds just before pointing. */
    val nodeKey: Int,
    val viewId: String? = null,
    val scrollable: Boolean = false,
    /** "on", "off", "selected"… — lets a toggle count as done even when nothing else moves. */
    val states: List<String> = emptyList(),
)

/** A screen rendered for the model, plus the lookup from its references back to pixels. */
class EncodedScreen(
    val text: String,
    val targets: Map<Int, Target>,
    /** Stable fingerprint used to tell a new screen from the same one redrawn. */
    val signature: Set<String>,
    /** Few labelled things to point at: a picture would help the model here. */
    val sparse: Boolean,
    val title: String?,
)

/**
 * Turns an accessibility tree into a short, readable list the model can act on.
 *
 * The raw tree is mostly layout: nested containers, ids, class names, and a clickable row
 * whose words live in two child TextViews. Sending that costs thousands of tokens a turn and
 * makes the model pick the TextView instead of the row. Here every pressable thing becomes
 * one line carrying the words a person would use for it, in reading order, so a typical
 * screen is a few hundred tokens and the reference the model returns is the thing to press.
 *
 * ```
 * App: Settings · Title: Network & internet
 * [1] button "Navigate up" @6,7
 * # "Network & internet"
 * [2] item "Internet" (Wi-Fi network) @50,21
 * [3] switch "Airplane mode" [off] @50,30
 * [4] list (scroll down for more) @50,55
 * ```
 */
object ScreenEncoder {

    private const val MAX_LINES = 140
    private const val MAX_LABEL = 70
    private const val MAX_DETAIL = 60
    private const val MAX_CONTEXT = 100

    fun encode(screen: Screen): EncodedScreen {
        val screenBounds = screen.bounds
        val items = mutableListOf<Item>()

        fun visit(node: UiNode, insideActionable: Boolean) {
            val clipped = node.bounds.intersect(screenBounds)
            val visible = clipped != null && !clipped.isEmpty && node.bounds.width > 4 && node.bounds.height > 4
            val actionable = visible && isActionable(node, insideActionable)

            if (visible && node.scrollable && hasScrollRoom(node)) {
                items += Item.Scroll(node, clipped!!)
            }
            if (actionable) {
                items += Item.Action(node, clipped!!)
            } else if (visible && !insideActionable) {
                ownWords(node)?.let { items += Item.Context(node, clipped!!, it) }
            }
            node.children.forEach { visit(it, insideActionable || actionable) }
        }
        screen.roots.forEach { visit(it, insideActionable = false) }

        // Reading order: top to bottom, then left to right, with rows of similar height
        // treated as one line so a toolbar reads left-to-right.
        val band = maxOf(ROW_BAND, screen.height / 60)
        val ordered = items.sortedWith(
            compareBy<Item>({ it.sortY / band }, { it.clip.l }, { it.order }),
        )

        val title = screen.title?.takeIf { it.isNotBlank() } ?: firstHeading(ordered)
        val targets = LinkedHashMap<Int, Target>()
        val lines = mutableListOf<String>()
        val signature = LinkedHashSet<String>()
        val labelsShown = HashSet<String>()
        title?.let { labelsShown += normalize(it).lowercase() }
        var labelled = 0
        var ref = 0

        for (item in ordered) {
            if (lines.size >= MAX_LINES) break
            when (item) {
                is Item.Action -> {
                    val described = describe(item.node)
                    ref++
                    targets[ref] = Target(
                        ref = ref,
                        role = described.role,
                        label = described.label,
                        bounds = item.node.bounds,
                        nodeKey = item.node.key,
                        viewId = item.node.viewId,
                        states = described.states,
                    )
                    if (described.labelled) labelled++
                    labelsShown += described.label.lowercase()
                    described.detail?.let { labelsShown += it.lowercase() }
                    lines += buildString {
                        append('[').append(ref).append("] ").append(described.role)
                        append(" \"").append(described.label).append('"')
                        described.value?.let { append(" = \"").append(it).append('"') }
                        described.detail?.let { append(" (").append(it).append(')') }
                        described.states.forEach { append(" [").append(it).append(']') }
                        if (item.clip.height < item.node.bounds.height * 6 / 10) append(" [partly off screen]")
                        append(position(item.clip, screen))
                    }
                    signature += "a|${described.role}|${described.label.lowercase()}"
                }

                is Item.Scroll -> {
                    ref++
                    targets[ref] = Target(
                        ref = ref,
                        role = "list",
                        label = "list",
                        bounds = item.clip,
                        nodeKey = item.node.key,
                        viewId = item.node.viewId,
                        scrollable = true,
                    )
                    val dirs = buildList {
                        if (item.node.has(UiNode.SCROLL_FORWARD)) add("down for more")
                        if (item.node.has(UiNode.SCROLL_BACKWARD)) add("up for more")
                    }
                    lines += "[$ref] ${scrollNoun(item.node)} (scroll ${dirs.joinToString(", ")})" +
                        position(item.clip, screen)
                }

                is Item.Context -> {
                    val words = item.words
                    val key = words.lowercase()
                    // A caption already carried by a row's label adds nothing but tokens.
                    if (key in labelsShown) continue
                    labelsShown += key
                    val marker = if (item.node.heading) "#" else "·"
                    lines += "$marker \"${clip(words, MAX_CONTEXT)}\""
                    signature += "c|$key"
                }
            }
        }

        val header = buildString {
            append("App: ").append(screen.appLabel ?: screen.pkg)
            if (screen.appLabel != null) append(" (").append(screen.pkg).append(')')
            title?.let { append(" · Title: ").append(clip(it, MAX_LABEL)) }
            if (screen.keyboardVisible) append(" · Keyboard: open")
        }
        signature += "p|${screen.pkg}"
        title?.let { signature += "t|${it.lowercase()}" }

        val text = buildString {
            appendLine(header)
            if (lines.isEmpty()) appendLine("(nothing readable on screen)")
            lines.forEach { appendLine(it) }
        }.trimEnd()

        val sparse = labelled < SPARSE_MIN_LABELLED || hasOpaqueSurface(screen)
        return EncodedScreen(text, targets, signature, sparse, title)
    }

    /**
     * True when [after] is a different screen rather than [before] with a detail redrawn —
     * a clock ticking, a progress bar moving. Package or title changing settles it at once;
     * otherwise a quarter of what is listed has to have come or gone.
     */
    fun movedOn(before: EncodedScreen, after: EncodedScreen): Boolean {
        if (before.signature.isEmpty() || after.signature.isEmpty()) return false
        val pkgBefore = before.signature.firstOrNull { it.startsWith("p|") }
        val pkgAfter = after.signature.firstOrNull { it.startsWith("p|") }
        if (pkgBefore != pkgAfter) return true
        val tBefore = before.signature.firstOrNull { it.startsWith("t|") }
        val tAfter = after.signature.firstOrNull { it.startsWith("t|") }
        if (tBefore != null && tAfter != null && tBefore != tAfter) return true
        val common = before.signature.count { it in after.signature }
        val union = before.signature.size + after.signature.size - common
        return union > 0 && (union - common).toFloat() / union > CHANGE_THRESHOLD
    }

    // ---- What counts as pressable ---------------------------------------------------------

    private fun isActionable(node: UiNode, insideActionable: Boolean): Boolean {
        if (node.clickable || node.longClickable || node.editable) return true
        // A Switch inside a clickable Settings row is not separately pressable — the row is.
        // On its own (a bare toggle in a toolbar) it is the target.
        if (node.checkable && !insideActionable) return true
        return false
    }

    private fun hasScrollRoom(node: UiNode): Boolean =
        node.has(UiNode.SCROLL_FORWARD) || node.has(UiNode.SCROLL_BACKWARD)

    // ---- Words ------------------------------------------------------------------------------

    private class Described(
        val role: String,
        val label: String,
        val labelled: Boolean,
        val detail: String?,
        val value: String?,
        val states: List<String>,
    )

    private fun describe(node: UiNode): Described {
        val role = role(node)
        val states = mutableListOf<String>()

        if (node.editable) {
            val label = firstNonBlank(node.hint, node.desc, humanizeId(node.viewId), labelFromChildren(node))
            val raw = node.text?.trim().orEmpty()
            val value = when {
                raw.isEmpty() || raw == node.hint -> null
                node.password -> "•••"
                else -> clip(raw, MAX_LABEL)
            }
            if (node.focused) states += "typing here"
            if (node.disabled) states += "disabled"
            return Described(role, clip(label ?: "text box", MAX_LABEL), label != null, null, value, states)
        }

        val own = firstNonBlank(node.text, node.desc)
        val parts = LinkedHashSet<String>()
        own?.let { parts += normalize(it) }
        collectWords(node, parts)
        val words = parts.filter { it.isNotBlank() }

        val label: String
        val labelled: Boolean
        if (words.isNotEmpty()) {
            label = clip(words.first(), MAX_LABEL)
            labelled = true
        } else {
            val fallback = firstNonBlank(node.hint, humanizeId(node.viewId))
            label = fallback ?: "unlabelled ${node.cls.ifBlank { "element" }}"
            labelled = fallback != null
        }
        val detail = words.drop(1).joinToString(", ").takeIf { it.isNotBlank() }?.let { clip(it, MAX_DETAIL) }

        toggleState(node)?.let { states += it }
        node.state?.takeIf { it.isNotBlank() && toggleState(node) == null }?.let { states += clip(it, 30) }
        if (node.selected) states += "selected"
        if (node.disabled) states += "disabled"
        return Described(role, label, labelled, detail, null, states)
    }

    /** Words of the non-pressable things inside a pressable one, in reading order. */
    private fun collectWords(node: UiNode, out: MutableSet<String>) {
        val kids = node.children.sortedWith(compareBy({ it.bounds.t / ROW_BAND }, { it.bounds.l }))
        for (child in kids) {
            if (child.clickable || child.editable) continue
            firstNonBlank(child.text, child.desc)?.let { out += normalize(it) }
            collectWords(child, out)
        }
    }

    private fun labelFromChildren(node: UiNode): String? {
        val parts = LinkedHashSet<String>()
        collectWords(node, parts)
        return parts.firstOrNull()
    }

    /** On/off for a toggle, whether the toggle is the node itself or a widget inside a row. */
    private fun toggleState(node: UiNode): String? {
        if (node.checkable) return if (node.checked) "on" else "off"
        var found: String? = null
        fun look(n: UiNode) {
            if (found != null) return
            if (n.checkable) {
                found = if (n.checked) "on" else "off"
                return
            }
            if (!n.clickable) n.children.forEach(::look)
        }
        node.children.forEach(::look)
        return found
    }

    private fun ownWords(node: UiNode): String? {
        val words = firstNonBlank(node.text, node.desc) ?: return null
        return normalize(words).takeIf { it.isNotBlank() }
    }

    private fun role(node: UiNode): String {
        val c = node.cls
        return when {
            node.editable || c.contains("EditText") -> "field"
            c.contains("Switch") || c.contains("Toggle") -> "switch"
            c.contains("CheckBox", ignoreCase = true) -> "checkbox"
            c.contains("RadioButton") -> "radio"
            c.contains("SeekBar") || c.contains("Slider") -> "slider"
            c.contains("Tab") -> "tab"
            c.contains("Button") -> "button"
            c.contains("Image") && !hasWordsInside(node) -> "icon"
            hasWordsInside(node) && (node.text == null || node.children.isNotEmpty()) -> "item"
            else -> "button"
        }
    }

    private fun hasWordsInside(node: UiNode): Boolean =
        node.children.any { !it.text.isNullOrBlank() || !it.desc.isNullOrBlank() || hasWordsInside(it) }

    private fun scrollNoun(node: UiNode): String = when {
        node.cls.contains("Pager") -> "pages"
        node.cls.contains("Horizontal") -> "row"
        else -> "list"
    }

    private fun firstHeading(items: List<Item>): String? =
        items.firstOrNull { it is Item.Context && it.node.heading }?.let { (it as Item.Context).words }

    /**
     * A large surface the tree cannot see into — a game, a camera preview, a map, a Flutter
     * view without semantics. The words list is blind there, so a screenshot earns its cost.
     */
    private fun hasOpaqueSurface(screen: Screen): Boolean {
        val screenArea = screen.bounds.area
        var opaque = false
        screen.roots.forEach { root ->
            root.walk { n ->
                if (opaque) return@walk
                val big = n.bounds.area > screenArea * 4 / 10
                val blind = n.children.isEmpty() && n.text.isNullOrBlank() && n.desc.isNullOrBlank()
                val surfacey = n.cls.contains("Surface") || n.cls.contains("Texture") ||
                    n.cls.contains("WebView") || n.cls.contains("Flutter") || n.cls == "View"
                if (big && blind && surfacey) opaque = true
            }
        }
        return opaque
    }

    // ---- Formatting -------------------------------------------------------------------------

    private fun position(b: Bounds, screen: Screen): String {
        if (screen.width <= 0 || screen.height <= 0) return ""
        val x = (b.cx * 100L / screen.width).toInt().coerceIn(0, 100)
        val y = (b.cy * 100L / screen.height).toInt().coerceIn(0, 100)
        return " @$x,$y"
    }

    private fun humanizeId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val words = id.substringAfterLast('/')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .lowercase()
            .trim()
        // Generated ids ("id1", "a3f") would only mislead.
        if (words.length < 3 || words.none { it.isLetter() } || Regex("^[a-z]?\\d+$").matches(words)) return null
        return words
    }

    private fun normalize(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.let { normalize(it) }

    private sealed class Item(val clip: Bounds, val order: Int) {
        /** Lists sort by their top edge (they introduce what is in them); everything else by centre. */
        val sortY: Int get() = if (this is Scroll) clip.t else clip.cy

        class Action(val node: UiNode, clip: Bounds) : Item(clip, 1)
        class Scroll(val node: UiNode, clip: Bounds) : Item(clip, 0)
        class Context(val node: UiNode, clip: Bounds, val words: String) : Item(clip, 2)
    }

    private const val ROW_BAND = 24
    private const val SPARSE_MIN_LABELLED = 3
    private const val CHANGE_THRESHOLD = 0.25f
}
