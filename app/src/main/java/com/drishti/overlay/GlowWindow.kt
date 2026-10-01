package com.drishti.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.drishti.accessibility.ScreenAgentAccessibilityService

/**
 * Full-screen, untouchable windows (the edge glow, the target ring) are added through the
 * accessibility service when it is running.
 *
 * That matters for touches, not looks. Since Android 12 an app overlay that covers the
 * screen only lets taps through while the combined opacity of that app's overlays stays at
 * or under 0.8. Two full-screen app overlays (glow + ring) together come to 0.96, and every
 * tap on the app being guided would be swallowed. Accessibility overlays are trusted and
 * don't count towards that limit.
 */
internal object OverlayHost {
    fun pick(fallback: Context): Context = ScreenAgentAccessibilityService.getInstance() ?: fallback

    fun fullScreenParams(host: Context): WindowManager.LayoutParams {
        val type = when {
            host is AccessibilityService -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val size = realScreenSize(host)
        return WindowManager.LayoutParams(
            size.x,
            size.y,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            // An untrusted overlay over 0.8 blocks every touch beneath it (Android 12+).
            if (host !is AccessibilityService) alpha = 0.8f
        }
    }

    /** Full physical display size, including the status and navigation bar areas. */
    fun realScreenSize(context: Context): android.graphics.Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            android.graphics.Point(bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
        }
    }
}

/**
 * The edge glow's window. It exists only while there is light to show: added when a mode
 * turns on, removed once [EdgeGlowView.Mode.Off] has faded out, so an idle phone carries no
 * full-screen window at all.
 */
internal class GlowWindow(private val service: Context) {
    private var view: EdgeGlowView? = null
    private var host: Context? = null

    var mode: EdgeGlowView.Mode = EdgeGlowView.Mode.Off
        set(value) {
            field = value
            if (value == EdgeGlowView.Mode.Off) {
                view?.mode = value
            } else {
                ensure()?.mode = value
            }
        }

    fun level(value: Float) {
        view?.level = value
    }

    private fun ensure(): EdgeGlowView? {
        val wanted = OverlayHost.pick(service)
        view?.let { current ->
            // The accessibility service came (or went) since the window was made: move house.
            if (host === wanted && current.isAttachedToWindow) return current
            remove()
        }
        val created = EdgeGlowView(wanted)
        created.onFaded = { if (mode == EdgeGlowView.Mode.Off) remove() }
        val wm = wanted.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            wm.addView(created, OverlayHost.fullScreenParams(wanted))
            view = created
            host = wanted
            created
        } catch (e: Exception) {
            Log.w("GlowWindow", "couldn't add the glow: ${e.message}")
            null
        }
    }

    fun remove() {
        val v = view ?: return
        val h = host
        view = null
        host = null
        v.onFaded = null
        h?.let { runCatching { (it.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } }
    }
}
