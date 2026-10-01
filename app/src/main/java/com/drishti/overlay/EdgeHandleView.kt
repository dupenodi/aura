package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * The handle: a slim sliver of aura light resting against the edge of the screen.
 *
 * It replaces the floating orb. It covers nothing, sits where a thumb already is, and
 * still says "aura is here": tap it to type, hold it to talk, drag it along the edge.
 * The light inside drifts slowly; it brightens while aura works and widens while it listens.
 * The window is wider than the visible sliver so it is easy to hit.
 */
class EdgeHandleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val spectrum = intArrayOf(
        Color.parseColor("#A78BFA"),
        Color.parseColor("#60A5FA"),
        Color.parseColor("#5EEAD4"),
        Color.parseColor("#F0ABFC"),
        Color.parseColor("#A78BFA"),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null

    private var phase = 0f
    private var energy = IDLE
    private var target = IDLE
    private var lastNs = 0L
    private var animator: ValueAnimator? = null

    /** Which edge it rests on; the sliver is drawn against that edge. */
    var onRight: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            retarget()
        }

    var listening: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            retarget()
        }

    /** A finger is on it: brighten at once so the press is felt before anything happens. */
    var touched: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            retarget()
        }

    /** Battery saver: keep the colour, stop the motion. */
    var lowPower: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) stop() else start()
            invalidate()
        }

    init {
        contentDescription = "aura — tap to ask, hold to talk"
        // The window is wider than the light; say so to accessibility, too.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun retarget() {
        target = when {
            listening -> LISTENING
            touched -> TOUCHED
            busy -> BUSY
            else -> IDLE
        }
        if (lowPower) {
            energy = target
            invalidate()
        }
    }

    private fun start() {
        if (lowPower || animator != null) return
        lastNs = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
                lastNs = now
                energy += (target - energy) * min(1f, dt * 5f)
                phase += dt * (0.5f + 1.5f * energy)
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Twice as tall as the view so it can slide through the colours.
        shader = LinearGradient(0f, 0f, 0f, h * 2f, spectrum, null, Shader.TileMode.MIRROR)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val breathe = 0.5f + 0.5f * sin(phase * 1.6f)
        val barW = dp(5f + 3f * energy)
        val barH = h * (0.62f + 0.08f * breathe + 0.12f * energy)
        val inset = dp(3f)
        val left = if (onRight) w - inset - barW else inset
        val top = (h - barH) / 2f

        val s = shader ?: return
        matrix.setTranslate(0f, -phase * h * 0.35f)
        s.setLocalMatrix(matrix)

        // Glow: wider, fainter passes behind the bar.
        glowPaint.shader = s
        for (pass in 3 downTo 1) {
            val spread = dp(3f) * pass * (1f + energy)
            glowPaint.alpha = ((0.16f + 0.14f * energy) / pass * 255).toInt().coerceIn(0, 255)
            rect.set(left - spread, top - spread, left + barW + spread, top + barH + spread)
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, glowPaint)
        }
        fill.shader = s
        fill.alpha = (200 + 55 * maxOf(breathe * 0.4f, energy)).toInt().coerceIn(0, 255)
        rect.set(left, top, left + barW, top + barH)
        canvas.drawRoundRect(rect, barW / 2f, barW / 2f, fill)
    }

    private companion object {
        const val IDLE = 0.2f
        const val BUSY = 0.7f
        const val TOUCHED = 0.85f
        const val LISTENING = 1f
    }
}
