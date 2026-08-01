package com.drishti.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * UI element detected by the accessibility service.
 * Ported from mobilerun-portal ElementNode.
 */
data class ElementNode(
    val nodeInfo: AccessibilityNodeInfo,
    val rect: Rect,
    val text: String,
    val className: String,
    val windowLayer: Int,
    var creationTime: Long,
    val id: String,
    var parent: ElementNode? = null,
    val children: MutableList<ElementNode> = mutableListOf(),
    var clickableIndex: Int = -1,
    var nestingLevel: Int = 0,
    var semanticParentId: String? = null,
    var overlayIndex: Int = -1,
) {
    companion object {
        private const val FADE_DURATION_MS = 60000L

        fun createId(rect: Rect, className: String, text: String): String {
            return "${rect.toShortString()}_${className}_${text.take(20)}"
        }
    }

    fun calculateWeight(): Float {
        val now = System.currentTimeMillis()
        val age = now - creationTime
        return max(0f, min(1f, 1f - (age.toFloat() / FADE_DURATION_MS.toFloat())))
    }

    fun overlaps(other: ElementNode): Boolean = Rect.intersects(this.rect, other.rect)

    fun contains(other: ElementNode): Boolean = this.rect.contains(other.rect)

    fun isClickable(): Boolean = nodeInfo.isClickable

    fun isText(): Boolean = text.isNotEmpty() && !nodeInfo.isClickable

    fun addChild(child: ElementNode) {
        if (child === this || hasAncestor(child)) return
        if (!children.contains(child)) {
            children.add(child)
            child.parent = this
        }
    }

    fun removeChild(child: ElementNode) {
        children.remove(child)
        child.parent = null
    }

    fun findByOverlayIndex(index: Int): ElementNode? {
        if (overlayIndex == index) return this
        for (child in children) {
            val found = child.findByOverlayIndex(index)
            if (found != null) return found
        }
        return null
    }

    fun flatten(): List<ElementNode> {
        val out = mutableListOf<ElementNode>()
        collectFlat(out, identitySet())
        return out
    }

    private fun collectFlat(out: MutableList<ElementNode>, visited: MutableSet<ElementNode>) {
        if (!visited.add(this)) return
        out.add(this)
        for (child in children) {
            child.collectFlat(out, visited)
        }
        visited.remove(this)
    }

    private fun hasAncestor(candidate: ElementNode): Boolean {
        var current = parent
        val visited = identitySet()
        while (current != null && visited.add(current)) {
            if (current === candidate) return true
            current = current.parent
        }
        return false
    }

    private fun identitySet(): MutableSet<ElementNode> =
        Collections.newSetFromMap(IdentityHashMap())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ElementNode) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
