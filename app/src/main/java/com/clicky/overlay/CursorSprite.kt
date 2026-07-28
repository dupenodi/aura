package com.clicky.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.min

/**
 * Soft glowing cursor drawn inside [AnnotationCanvasView].
 * Visible only during an active agent turn / debug demo; fades out after.
 */
class CursorSprite(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val sizePx = 28f * density
    private val trailCapacity = 12

    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    /** 0 = hidden, 1 = fully visible. */
    var visibility: Float = 0f
        private set

    private val trail = ArrayDeque<TrailPoint>(trailCapacity)
    private var moveAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    private var pulseProgress: Float = 0f
    private var pulseActive: Boolean = false

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8F7F5.toInt()
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val arrowPath = Path()

    fun setPosition(px: Float, py: Float) {
        x = px
        y = py
        pushTrail(px, py)
    }

    fun show(immediate: Boolean = false) {
        fadeAnimator?.cancel()
        if (immediate) {
            visibility = 1f
            return
        }
        fadeAnimator = ValueAnimator.ofFloat(visibility, 1f).apply {
            duration = 180L
            addUpdateListener { visibility = it.animatedValue as Float }
            start()
        }
    }

    fun hide(fadeMs: Long = 350L) {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(visibility, 0f).apply {
            duration = fadeMs
            addUpdateListener { visibility = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Spring-interpolated move to ([targetX], [targetY]).
     * Leaves a fading motion trail of the last [trailCapacity] positions.
     */
    fun animateTo(
        targetX: Float,
        targetY: Float,
        durationMs: Long = 450L,
        onEnd: (() -> Unit)? = null,
    ) {
        moveAnimator?.cancel()
        val startX = x
        val startY = y
        show()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = SPRING
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                x = startX + (targetX - startX) * t
                y = startY + (targetY - startY) * t
                pushTrail(x, y)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    x = targetX
                    y = targetY
                    pushTrail(x, y)
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Expanding ring + white flash at the current position, 250ms. */
    fun clickPulse() {
        pulseAnimator?.cancel()
        pulseActive = true
        pulseProgress = 0f
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            addUpdateListener { pulseProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pulseActive = false
                    pulseProgress = 0f
                }
            })
            start()
        }
    }

    fun cancelAnimations() {
        moveAnimator?.cancel()
        pulseAnimator?.cancel()
        fadeAnimator?.cancel()
        moveAnimator = null
        pulseAnimator = null
        fadeAnimator = null
        pulseActive = false
    }

    fun draw(canvas: Canvas) {
        if (visibility <= 0.01f) return

        // Motion trail (oldest → newest), alpha ramp.
        var i = 0
        val n = trail.size
        for (pt in trail) {
            val age = if (n <= 1) 1f else i.toFloat() / (n - 1).toFloat()
            val alpha = (age * 0.45f * visibility * 255f).toInt().coerceIn(0, 255)
            val r = sizePx * (0.12f + 0.22f * age)
            trailPaint.color = (alpha shl 24) or 0x002EC4B6
            canvas.drawCircle(pt.x, pt.y, r, trailPaint)
            i++
        }

        val v = visibility
        val glowR = sizePx * 0.85f
        glowPaint.shader = RadialGradient(
            x,
            y,
            glowR,
            intArrayOf(
                ( (0x66 * v).toInt() shl 24) or 0x002EC4B6,
                ( (0x22 * v).toInt() shl 24) or 0x002EC4B6,
                0x00000000,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, glowR, glowPaint)
        glowPaint.shader = null

        // Soft arrow / pointer tip pointing up-left (classic cursor feel).
        val s = sizePx * 0.55f
        arrowPath.reset()
        arrowPath.moveTo(x - s * 0.15f, y - s * 0.55f)
        arrowPath.lineTo(x + s * 0.55f, y + s * 0.15f)
        arrowPath.lineTo(x + s * 0.05f, y + s * 0.20f)
        arrowPath.lineTo(x + s * 0.35f, y + s * 0.65f)
        arrowPath.lineTo(x + s * 0.05f, y + s * 0.55f)
        arrowPath.lineTo(x - s * 0.45f, y + s * 0.55f)
        arrowPath.close()
        arrowPaint.alpha = (255 * v).toInt()
        canvas.drawPath(arrowPath, arrowPaint)

        // Bright core dot.
        corePaint.alpha = (230 * v).toInt()
        canvas.drawCircle(x, y, sizePx * 0.14f, corePaint)

        if (pulseActive) {
            val expand = sizePx * (0.4f + pulseProgress * 1.8f)
            pulsePaint.alpha = ((1f - pulseProgress) * 220f * v).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, expand, pulsePaint)
            flashPaint.alpha = ((1f - pulseProgress) * 160f * v).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, sizePx * 0.35f * (1f - pulseProgress * 0.5f), flashPaint)
        }
    }

    private fun pushTrail(px: Float, py: Float) {
        val last = trail.lastOrNull()
        if (last != null && hypot(px - last.x, py - last.y) < 1.5f) return
        if (trail.size >= trailCapacity) {
            trail.removeFirst()
        }
        trail.addLast(TrailPoint(px, py))
    }

    private data class TrailPoint(val x: Float, val y: Float)

    companion object {
        /** Soft spring-like ease (slight overshoot). */
        val SPRING: Interpolator = PathInterpolator(0.22f, 1.35f, 0.36f, 1f)

        fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float =
            hypot(bx - ax, by - ay).toFloat()

        fun durationForDistance(ax: Float, ay: Float, bx: Float, by: Float, density: Float): Long {
            val d = distance(ax, ay, bx, by)
            // ~0.55ms per dp, clamped.
            val dp = d / density.coerceAtLeast(0.5f)
            return min(900L, (220L + dp * 0.55f).toLong())
        }
    }
}
