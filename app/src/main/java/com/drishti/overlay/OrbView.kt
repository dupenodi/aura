package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.drishti.data.AuraPrefs
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin

/**
 * Aura's floating orb.
 *
 * A radial-gradient sphere with the light source up and to the left, wrapped in a halo
 * that breathes. It carries the product's whole state vocabulary: idle breathing,
 * a sweep while working, and solid rings while it is recording.
 */
class OrbView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    private var breathe = 0.5f
    private var sweep = 0f
    private var listenPhase = 0f

    private var breatheAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null
    private var listenAnimator: ValueAnimator? = null

    var skin: OrbSkin = OrbSkin.Aurora
        set(value) {
            field = value
            rebuildShaders()
            invalidate()
        }

    var glow: GlowLevel = GlowLevel.Balanced
        set(value) {
            field = value
            invalidate()
        }

    /** Working on a task — shows the sweeping activity arc. */
    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSweep() else stopSweep()
            invalidate()
        }

    /** Recording — the mic ring is solid only while this is true. */
    var listening: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startListen() else stopListen()
            invalidate()
        }

    /**
     * Battery saver drops the glow and stops animation before it drops any capability.
     */
    var lowPower: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                stopBreathing()
            } else {
                startBreathing()
            }
            invalidate()
        }

    init {
        contentDescription = "Aura"
        startBreathing()
    }

    fun applyPrefs(prefs: AuraPrefs) {
        skin = prefs.orbSkin.value
        glow = prefs.glow.value
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = orbRadius()
        val cx = w / 2f
        val cy = h / 2f

        orbPaint.shader = RadialGradient(
            // Light source at 34%/28% of the sphere, sized to the farthest corner so the
            // colour stops land where the design's CSS puts them.
            cx - radius * 0.32f,
            cy - radius * 0.44f,
            radius * 1.95f,
            intArrayOf(skin.inner.toArgb(), skin.mid.toArgb(), skin.outer.toArgb()),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP,
        )
        rimPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.TRANSPARENT, Color.argb(40, 255, 255, 255)),
            floatArrayOf(0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun orbRadius(): Float = kotlin.math.min(width, height) / 2f - dp(9f)

    private fun startBreathing() {
        if (lowPower) return
        breatheAnimator?.cancel()
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                breathe = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathing() {
        breatheAnimator?.cancel()
        breatheAnimator = null
        breathe = 0.5f
    }

    private fun startSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = null
    }

    private fun startListen() {
        listenAnimator?.cancel()
        listenAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                listenPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopListen() {
        listenAnimator?.cancel()
        listenAnimator = null
        listenPhase = 0f
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        stopSweep()
        stopListen()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = orbRadius()
        if (radius <= 0f) return

        val glowFraction = if (lowPower) 0.15f else glow.fraction

        // Halo — wider and brighter while listening, so "I'm recording" reads instantly.
        val haloBoost = if (listening) 0.5f else 0f
        val haloAlpha = ((0.34f + 0.30f * breathe + haloBoost) * glowFraction * 255f)
            .coerceIn(0f, 255f).toInt()
        if (haloAlpha > 4) {
            val haloRadius = radius * (1.55f + 0.12f * breathe)
            haloPaint.shader = RadialGradient(
                cx,
                cy,
                haloRadius,
                intArrayOf(
                    Color.argb(haloAlpha, Color.red(skin.halo.toArgb()), Color.green(skin.halo.toArgb()), Color.blue(skin.halo.toArgb())),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, haloRadius, haloPaint)
        }

        // Expanding rings while recording.
        if (listening) {
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = dp(1.5f)
            listOf(listenPhase, (listenPhase + 0.5f) % 1f).forEach { phase ->
                val r = radius * (1.05f + phase * 0.95f)
                ringPaint.color = Color.argb(
                    ((1f - phase) * 140).toInt().coerceIn(0, 255),
                    0x7E, 0xF2, 0xFF,
                )
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        canvas.drawCircle(cx, cy, radius, orbPaint)
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // Activity sweep while a task is running.
        if (busy) {
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = dp(2.5f)
            ringPaint.color = skin.halo.toArgb()
            val inset = dp(3f)
            arcRect.set(
                cx - radius - inset,
                cy - radius - inset,
                cx + radius + inset,
                cy + radius + inset,
            )
            canvas.drawArc(arcRect, sweep, 92f, false, ringPaint)
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
