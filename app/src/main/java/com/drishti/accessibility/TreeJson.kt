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

    fun fingerprint(roots: List<ElementNode>): String {
        val flat = roots.flatMap { it.flatten() }
        return flat.joinToString("|") { e ->
            "${e.overlayIndex}:${e.className}:${e.text.take(40)}:${e.rect.toShortString()}"
        }
    }

    fun actionableCount(roots: List<ElementNode>): Int {
        return roots.flatMap { it.flatten() }.count { e ->
            e.nodeInfo.isClickable ||
                e.nodeInfo.isEditable ||
                e.nodeInfo.isCheckable ||
                e.nodeInfo.isScrollable ||
                e.nodeInfo.isLongClickable
        }
    }

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
