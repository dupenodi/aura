package com.clicky.overlay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Full physical display size in pixels (includes status bar / cutout / nav).
 * Accessibility [android.graphics.Rect] bounds and gesture coords use this space —
 * the annotation canvas must match 1:1 or highlights appear offset.
 */
object OverlayScreen {
    fun sizePx(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java) ?: run {
            val dm = context.resources.displayMetrics
            return dm.widthPixels to dm.heightPixels
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }
}
