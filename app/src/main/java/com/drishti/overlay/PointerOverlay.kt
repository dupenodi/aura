package com.drishti.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.drishti.accessibility.ScreenAgentAccessibilityService

/**
 * Non-interactive guidance overlay: spotlight highlight + neon cursor.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY only when constructed with an AccessibilityService;
 * BubbleService must use TYPE_APPLICATION_OVERLAY (otherwise BadTokenException).
 */
class PointerOverlay(private val context: Context) :
    ScreenAgentAccessibilityService.OverlayDrawingController {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var drawingEnabled = true

    private val pointerView = PointerView(context)
    private var pointerAdded = false

    /**
     * Guards against a stale hide (scheduled for an earlier target) cancelling a
     * highlight that was shown after it — previously this made guidance flicker away
     * mid-step, which read as "the highlight is broken".
     */
    private var showGeneration = 0

    /** Set by the host (BubbleService) to render status/agent text in its chat bubble. */
    var statusListener: ((text: String, durationMs: Long) -> Unit)? = null

    @JvmOverloads
    fun showTargetAt(bounds: Rect, durationMs: Long = DEFAULT_HOLD_MS, label: String? = null) {
        handler.post {
            if (!drawingEnabled) return@post
            if (!ensurePointer()) return@post
            val generation = ++showGeneration
            pointerView.visibility = View.VISIBLE
            pointerView.setTarget(bounds, label)
            handler.postDelayed({
                if (generation == showGeneration) pointerView.dismiss()
            }, durationMs.coerceAtLeast(MIN_HOLD_MS))
        }
    }

    fun hide() {
        handler.post {
            showGeneration++
            pointerView.dismiss()
            statusListener?.invoke("", 0L)
        }
    }

    fun showStatus(text: String, durationMs: Long = 2000L) {
        handler.post {
            if (!drawingEnabled) return@post
            statusListener?.invoke(text, durationMs)
        }
    }

    fun detach() {
        handler.post {
            showGeneration++
            pointerView.stopAnimations()
            if (pointerAdded) {
                try {
                    wm.removeView(pointerView)
                } catch (_: Exception) {
                }
                pointerAdded = false
            }
        }
    }

    override fun isDrawingEnabled(): Boolean = drawingEnabled

    override fun setDrawingEnabled(enabled: Boolean) {
        drawingEnabled = enabled
        handler.post {
            if (!enabled) {
                showGeneration++
                // Screenshots for the vision fallback must not contain our own chrome,
                // so drop it instantly rather than fading.
                pointerView.hideImmediate()
            }
        }
    }

    private fun ensurePointer(): Boolean {
        if (pointerAdded) return true
        // MATCH_PARENT stops short of the navigation-bar inset (measured 2337 of 2400px
        // on a Pixel 6), which clipped highlights near the bottom of the screen. Size the
        // window to the real display instead so every target can be drawn.
        val screen = realScreenSize()
        val params = baseParams().apply {
            width = screen.x
            height = screen.y
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return try {
            wm.addView(pointerView, params)
            pointerAdded = true
            ScreenAgentAccessibilityService.getInstance()?.overlayDrawingController = this
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pointer overlay", e)
            false
        }
    }

    /** Full physical display size, including status/navigation bar areas. */
    private fun realScreenSize(): android.graphics.Point {
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

    private fun baseParams(): WindowManager.LayoutParams {
        val type = when {
            context is AccessibilityService &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /**
     * Spotlight + neon cursor, drawn entirely in **absolute screen coordinates**.
     *
     * Accessibility bounds come from `getBoundsInScreen`, but an overlay window is not
     * guaranteed to be positioned at screen (0,0) — status bar, cutout and gesture insets
     * can shift it. Rather than guessing those insets, we ask the view where it actually
     * landed via [getLocationOnScreen] and translate the canvas by that delta, which stays
     * correct on every device, orientation and inset configuration.
     */
    private class PointerView(context: Context) : View(context) {
        init {
            // BlurMaskFilter and PorterDuff.CLEAR need a software layer;
            // overlay windows are hardware-accelerated by default.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        private val windowOrigin = IntArray(2)

        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val cutoutPath = Path()
        private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NEON
            style = Paint.Style.STROKE
            strokeWidth = dp(5f)
            maskFilter = BlurMaskFilter(dp(9f), BlurMaskFilter.Blur.NORMAL)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NEON
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
        }
        private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F20B1020")
            style = Paint.Style.FILL
        }
        private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NEON
            textSize = dp(13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val cursorGlowOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Violet outer bloom, matching the design's second drop-shadow.
            color = NEON_DEEP
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL)
        }
        private val cursorGlowInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NEON
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
        }
        private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Highlight to cyan to violet down the length of the arrow.
            shader = android.graphics.LinearGradient(
                0f,
                0f,
                dp(17f),
                dp(27f),
                intArrayOf(NEON_BRIGHT, NEON, NEON_DEEP),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        private val cursorEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Faint dark rim so the cursor still reads on white backgrounds.
            color = Color.parseColor("#66101828")
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val tapRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NEON
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }

        /** Modern pointer silhouette, authored in a 17x27 grid with the tip at (0,0). */
        private val cursorPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, dp(24f))
            lineTo(dp(5.6f), dp(18.6f))
            lineTo(dp(9.2f), dp(27f))
            lineTo(dp(13.4f), dp(25f))
            lineTo(dp(9.8f), dp(17f))
            lineTo(dp(16.6f), dp(16.6f))
            close()
        }

        private val target = RectF()
        private val labelRect = RectF()
        private var labelText: String? = null
        private var showHighlight = false

        private var cursorX = 0f
        private var cursorY = 0f
        private var cursorVisible = false
        private var cursorScale = 1f

        /** 0..1 master fade so the spotlight eases in and out instead of popping. */
        private var revealFraction = 0f
        private var pulsePhase = 0f
        private var rippleFraction = 1f

        private var moveAnimator: ValueAnimator? = null
        private var pulseAnimator: ValueAnimator? = null
        private var revealAnimator: ValueAnimator? = null
        private var rippleAnimator: ValueAnimator? = null

        fun setTarget(bounds: Rect, label: String?) {
            target.set(bounds)
            // Breathing room so the ring frames the element rather than clipping it.
            target.inset(-dp(6f), -dp(6f))
            labelText = label?.takeIf { it.isNotBlank() }?.take(38)
            showHighlight = true
            animateReveal(1f)
            // Aim at the element's centre; the cursor tip is its own origin.
            moveCursorTo(bounds.exactCenterX(), bounds.exactCenterY())
            startPulse()
        }

        /** Graceful exit — fades the spotlight out, then clears state. */
        fun dismiss() {
            animateReveal(0f) {
                showHighlight = false
                cursorVisible = false
                labelText = null
                stopPulse()
            }
        }

        /** Immediate teardown with no animation (used before screenshots). */
        fun hideImmediate() {
            stopAnimations()
            revealFraction = 0f
            showHighlight = false
            cursorVisible = false
            labelText = null
            visibility = GONE
            invalidate()
        }

        fun stopAnimations() {
            moveAnimator?.cancel()
            revealAnimator?.cancel()
            rippleAnimator?.cancel()
            stopPulse()
        }

        private fun animateReveal(to: Float, onEnd: (() -> Unit)? = null) {
            revealAnimator?.cancel()
            if (to > 0f) visibility = VISIBLE
            revealAnimator = ValueAnimator.ofFloat(revealFraction, to).apply {
                duration = if (to > 0f) 200L else 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    revealFraction = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    // onAnimationEnd also fires after cancel(); without this guard a
                    // fade-out interrupted by a new target would clear the new highlight.
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        onEnd?.invoke()
                        if (revealFraction <= 0.01f) visibility = GONE
                        invalidate()
                    }
                })
                start()
            }
        }

        private fun moveCursorTo(x: Float, y: Float) {
            moveAnimator?.cancel()
            // First appearance glides in from off-target so the eye catches the motion.
            val startX = if (cursorVisible) cursorX else x - dp(90f)
            val startY = if (cursorVisible) cursorY else y - dp(80f)
            cursorVisible = true
            val travel = kotlin.math.hypot((x - startX).toDouble(), (y - startY).toDouble())
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                // Longer glides for longer distances, so speed stays roughly constant.
                duration = (260L + travel.toLong() / 3L).coerceAtMost(620L)
                interpolator = GLIDE
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    cursorX = startX + (x - startX) * t
                    cursorY = startY + (y - startY) * t
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    // Only ripple on a glide that actually reached its target.
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) tapFeedback()
                    }
                })
                start()
            }
        }

        /** Click affordance: cursor dips and a ring ripples out from the tip. */
        private fun tapFeedback() {
            ValueAnimator.ofFloat(1f, 0.82f, 1f).apply {
                duration = 260
                addUpdateListener {
                    cursorScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    rippleFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun startPulse() {
            if (pulseAnimator?.isRunning == true) return
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    pulsePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulsePhase = 0f
        }

        override fun onDraw(canvas: Canvas) {
            if (revealFraction <= 0.01f) return

            // Map absolute screen coordinates onto this window, wherever it landed.
            getLocationOnScreen(windowOrigin)
            canvas.save()
            canvas.translate(-windowOrigin[0].toFloat(), -windowOrigin[1].toFloat())

            if (showHighlight && !target.isEmpty) {
                drawSpotlight(canvas)
                drawRing(canvas)
                labelText?.let { drawLabel(canvas, it) }
            }
            if (cursorVisible) drawCursor(canvas)

            canvas.restore()
        }

        /**
         * Dim everything except the target, so "where" is unmistakable.
         *
         * Clipping the hole out beats a saveLayer + PorterDuff.CLEAR here: the pulse
         * animation redraws continuously, and this avoids allocating a full-screen
         * offscreen buffer every frame. The neon ring is drawn over the clip boundary,
         * so the un-antialiased clip edge never shows.
         */
        private fun drawSpotlight(canvas: Canvas) {
            val radius = dp(14f)
            scrimPaint.alpha = (SCRIM_ALPHA * revealFraction).toInt()
            cutoutPath.reset()
            cutoutPath.addRoundRect(target, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(cutoutPath)
            // Cover the whole window in screen space, regardless of where we're anchored.
            canvas.drawRect(
                windowOrigin[0].toFloat(),
                windowOrigin[1].toFloat(),
                (windowOrigin[0] + width).toFloat(),
                (windowOrigin[1] + height).toFloat(),
                scrimPaint,
            )
            canvas.restore()
        }

        private fun drawRing(canvas: Canvas) {
            val radius = dp(14f)
            // Gentle breathing pulse draws the eye without being frantic.
            val spread = dp(3f) * pulsePhase
            val ring = RectF(target).apply { inset(-spread, -spread) }

            ringGlowPaint.alpha = ((140 + 70 * pulsePhase) * revealFraction).toInt()
            canvas.drawRoundRect(ring, radius, radius, ringGlowPaint)

            ringPaint.alpha = (255 * revealFraction).toInt()
            canvas.drawRoundRect(target, radius, radius, ringPaint)
        }

        private fun drawLabel(canvas: Canvas, text: String) {
            val padH = dp(10f)
            val padV = dp(6f)
            val textWidth = labelTextPaint.measureText(text)
            val metrics = labelTextPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val boxW = textWidth + padH * 2
            val boxH = textHeight + padV * 2
            val gap = dp(10f)

            // Prefer above the target; flip below when there's no room.
            var top = target.top - gap - boxH
            if (top < windowOrigin[1] + dp(8f)) top = target.bottom + gap
            // Keep the chip on-screen horizontally.
            val maxLeft = windowOrigin[0] + width - boxW - dp(8f)
            val left = (target.centerX() - boxW / 2f)
                .coerceIn(windowOrigin[0] + dp(8f), maxLeft.coerceAtLeast(windowOrigin[0] + dp(8f)))

            labelRect.set(left, top, left + boxW, top + boxH)
            labelBgPaint.alpha = (242 * revealFraction).toInt()
            canvas.drawRoundRect(labelRect, boxH / 2f, boxH / 2f, labelBgPaint)

            labelTextPaint.alpha = (255 * revealFraction).toInt()
            canvas.drawText(text, left + padH, top + padV - metrics.ascent, labelTextPaint)
        }

        private fun drawCursor(canvas: Canvas) {
            val alpha = revealFraction

            // Tap ripple radiates from the cursor tip.
            if (rippleFraction < 1f) {
                tapRipplePaint.alpha = (200 * (1f - rippleFraction) * alpha).toInt()
                canvas.drawCircle(
                    cursorX,
                    cursorY,
                    dp(6f) + dp(30f) * rippleFraction,
                    tapRipplePaint,
                )
            }

            canvas.save()
            canvas.translate(cursorX, cursorY)
            canvas.scale(cursorScale, cursorScale, 0f, 0f)

            // Layered neon: wide soft halo, tight bright bloom, solid core, dark rim.
            cursorGlowOuter.alpha = ((110 + 60 * pulsePhase) * alpha).toInt()
            canvas.drawPath(cursorPath, cursorGlowOuter)
            cursorGlowInner.alpha = (200 * alpha).toInt()
            canvas.drawPath(cursorPath, cursorGlowInner)
            cursorFill.alpha = (255 * alpha).toInt()
            canvas.drawPath(cursorPath, cursorFill)
            cursorEdge.alpha = (204 * alpha).toInt()
            canvas.drawPath(cursorPath, cursorEdge)

            canvas.restore()
        }

        companion object {
            // Aura's guidance accent: cyan core bleeding into violet.
            private val NEON = Color.parseColor("#4DE8FF")
            private val NEON_BRIGHT = Color.parseColor("#D5FAFF")
            private val NEON_DEEP = Color.parseColor("#A06BFF")
            // Android force-caps FLAG_NOT_TOUCHABLE overlay windows at alpha 0.8, so
            // budget for that multiplier when picking how dark the scrim should read.
            private const val SCRIM_ALPHA = 132f
            private val GLIDE = PathInterpolator(0.22f, 0.9f, 0.24f, 1f)
        }
    }

    companion object {
        private const val TAG = "PointerOverlay"
        private const val DEFAULT_HOLD_MS = 900L
        private const val MIN_HOLD_MS = 650L
    }
}
