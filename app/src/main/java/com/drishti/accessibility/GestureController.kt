package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gestures and global actions via AccessibilityService.
 * Success means the gesture completed (or was cancelled), not merely accepted for dispatch.
 */
object GestureController {
    private const val TAG = "GestureController"
    private const val TAP_DURATION_MS = 50L
    private const val DEFAULT_SWIPE_DURATION_MS = 300
    private const val MIN_SWIPE_DURATION_MS = 10
    private const val MAX_SWIPE_DURATION_MS = 5000

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        return dispatch(stroke, "Tap at ($x, $y)")
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int = DEFAULT_SWIPE_DURATION_MS,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val dur = durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur.toLong())
        return dispatch(stroke, "Swipe ($startX,$startY)->($endX,$endY)")
    }

    fun performGlobalAction(action: Int): Boolean {
        val service = ScreenAgentAccessibilityService.getInstance() ?: return false
        return try {
            val result = service.performGlobalAction(action)
            Log.d(TAG, "Global Action $action performed: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Global action error", e)
            false
        }
    }

    private suspend fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        label: String,
    ): Boolean {
        val service = ScreenAgentAccessibilityService.getInstance() ?: return false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            try {
                val accepted = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "$label completed")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "$label cancelled")
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!accepted) {
                    Log.w(TAG, "$label dispatch rejected")
                    if (cont.isActive) cont.resume(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "$label error", e)
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
