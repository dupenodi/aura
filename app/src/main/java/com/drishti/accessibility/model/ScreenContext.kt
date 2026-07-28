package com.drishti.accessibility.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

data class UiNode(
    val id: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val nodeRef: WeakReference<AccessibilityNodeInfo>,
) {
    val area: Int get() = bounds.width() * bounds.height()
}

data class ScreenContext(
    val packageName: String,
    val activityName: String?,
    val screenW: Int,
    val screenH: Int,
    val nodes: List<UiNode>,
    val asPromptText: String,
)
