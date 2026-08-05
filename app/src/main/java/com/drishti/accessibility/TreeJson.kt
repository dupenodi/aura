package com.drishti.accessibility

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Ported from mobilerun-portal JsonBuilders (element tree shape). */
object TreeJson {
    fun elementNodeToJson(element: ElementNode): JSONObject =
        elementNodeToJson(element, identitySet())

    fun rootsToJsonArray(roots: List<ElementNode>): JSONArray {
        val array = JSONArray()
        roots.forEach { array.put(elementNodeToJson(it)) }
        return array
    }

    fun compactTreeString(roots: List<ElementNode>): String =
        rootsToJsonArray(roots).toString()

    /**
     * The stable shape of a screen: what is on it and roughly where.
     *
     * Used to tell whether the user actually followed an instruction. Comparing the tree
     * verbatim is too twitchy for that — a clock ticking over or a fading animation
     * changes it without anything having happened.
     */
    fun signature(roots: List<ElementNode>): Set<String> =
        roots.flatMap { it.flatten() }
            .filter { it.text.isNotBlank() || it.nodeInfo.isClickable || it.nodeInfo.isEditable }
            .map { "${it.className}:${it.text.take(24)}:${it.rect.centerX() / 32}:${it.rect.centerY() / 32}" }
            .toSet()

    /**
     * True when [after] is a different screen from [before] rather than the same screen
     * with a detail redrawn. A fifth of the elements have to have come or gone.
     */
    fun movedOn(before: Set<String>, after: Set<String>): Boolean {
        // A screen we could not read is not a screen that changed. Reporting change here
        // let one failed tree read stand in for the user having acted.
        if (before.isEmpty() || after.isEmpty()) return false
        val common = before.count { it in after }
        val union = before.size + after.size - common
        return union > 0 && (union - common).toFloat() / union > CHANGE_THRESHOLD
    }

    private const val CHANGE_THRESHOLD = 0.2f

    private fun elementNodeToJson(
        element: ElementNode,
        visited: MutableSet<ElementNode>,
    ): JSONObject {
        if (!visited.add(element)) {
            return JSONObject().apply {
                put("index", element.overlayIndex)
                put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
                put("className", element.className)
                put("text", element.text)
                put(
                    "bounds",
                    "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}",
                )
                put("clickable", element.nodeInfo.isClickable)
                put("editable", element.nodeInfo.isEditable)
                put("children", JSONArray())
            }
        }

        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put(
                "bounds",
                "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}",
            )
            put("clickable", element.nodeInfo.isClickable)
            put("editable", element.nodeInfo.isEditable)
            put("scrollable", element.nodeInfo.isScrollable)
            put("focused", element.nodeInfo.isFocused)

            val childrenArray = JSONArray()
            element.children.forEach { child ->
                if (!visited.contains(child)) {
                    childrenArray.put(elementNodeToJson(child, visited))
                }
            }
            put("children", childrenArray)
            visited.remove(element)
        }
    }

    private fun identitySet(): MutableSet<ElementNode> =
        Collections.newSetFromMap(IdentityHashMap())
}
