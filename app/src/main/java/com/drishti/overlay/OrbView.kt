package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating aura: coloured light drifting inside a soft circle, with a slowly turning
 * spectrum rim and a glow that breathes.
 *
 * It carries Aura's state without words — calm when idle, quicker and brighter while it is
 * working, swelling while it listens — so "something is happening" is always visible.
 */
class OrbView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val spectrum = intArrayOf(
        Color.parseColor("#A78BFA"), // violet
        Color.parseColor("#60A5FA"), // blue
        Color.parseColor("#5EEAD4"), // teal
        Color.parseColor("#F0ABFC"), // pink
    )

    /** One paint per light, each mixing with the others like light rather than paint. */
    private val blobs: List<Pair<Int, Paint>> = spectrum.map { c ->
        c to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blendMode = BlendMode.SCREEN
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
        }
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C21") }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val matrix = Matrix()

    private var phase = 0f
    private var energy = ENERGY_IDLE
    private var targetEnergy = ENERGY_IDLE
    private var lastFrameNs = 0L
    private var animator: ValueAnimator? = null

    /** Working on a task: quicker, brighter. */
    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateTarget()
        }

    /** Recording: the aura swells while they talk. */
    var listening: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateTarget()
        }

    /** Battery saver: hold still, keep the colour. */
    var lowPower: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) stop() else start()
            invalidate()
        }

    init {
        contentDescription = "aura — tap to ask, hold to talk"
        // Glow and blend modes are cheaper and exact in software for a view this small.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        start()
    }

    private fun updateTarget() {
        targetEnergy = when {
            listening -> ENERGY_LISTENING
            busy -> ENERGY_BUSY
            else -> ENERGY_IDLE
        }
        if (lowPower) {
            energy = targetEnergy
            invalidate()
        }
    }

    private fun start() {
        if (lowPower || animator != null) return
        lastFrameNs = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.05f)
                lastFrameNs = now
                // Ease towards the target state, then advance at a state-dependent speed.
                energy += (targetEnergy - energy) * min(1f, dt * 4f)
                phase = (phase + dt * (0.45f + 1.6f * energy)) % (2f * PI.toFloat() * 100f)
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val outer = min(w, h) / 2f
        val swell = if (listening) 1f + 0.06f * sin(phase * 6f) else 1f
        val r = (outer - dp(8f)) * swell

        // Glow: the colour of whichever light is nearest the front, breathing.
        val front = spectrum[((phase / 1.6f).toInt() % spectrum.size + spectrum.size) % spectrum.size]
        val glowAlpha = (70 + 110 * energy).toInt().coerceIn(0, 255)
        haloPaint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(withAlpha(front, glowAlpha), withAlpha(front, 0)),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, outer, haloPaint)

        // The body: a dark disc with the four lights drifting inside it.
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawCircle(cx, cy, r, basePaint)
        val speeds = floatArrayOf(1f, -1.3f, 0.8f, -0.6f)
        blobs.forEachIndexed { i, (color, p) ->
            val a = phase * speeds[i] + i * (PI.toFloat() / 2f)
            val reach = r * (0.38f + 0.12f * energy)
            val bx = cx + cos(a) * reach
            val by = cy + sin(a * 1.3f) * reach
            val br = r * (0.95f + 0.1f * sin(phase * 2f + i))
            p.shader = RadialGradient(
                bx, by, br,
                intArrayOf(withAlpha(color, (150 + 90 * energy).toInt().coerceIn(0, 255)), withAlpha(color, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(bx, by, br, p)
        }
        corePaint.shader = RadialGradient(
            cx, cy, r * 0.6f,
            intArrayOf(Color.argb((40 + 60 * energy).toInt(), 255, 255, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 0.6f, corePaint)
        canvas.restore()

        // Rim: a slowly turning ring of the whole spectrum.
        rimPaint.strokeWidth = dp(1.5f + 1f * energy)
        val sweep = SweepGradient(cx, cy, spectrum + spectrum[0], null)
        matrix.setRotate(phase * 40f, cx, cy)
        sweep.setLocalMatrix(matrix)
        rimPaint.shader = sweep
        rimPaint.alpha = (150 + 100 * energy).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, rimPaint)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val ENERGY_IDLE = 0.25f
        const val ENERGY_BUSY = 0.75f
        const val ENERGY_LISTENING = 1f
    }
}
