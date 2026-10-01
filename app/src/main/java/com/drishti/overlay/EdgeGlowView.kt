package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Light around the edge of the screen, in the aura's colours — the sign that aura is
 * listening or working, without covering anything.
 *
 * - [Mode.Listening]: the whole border comes alive, turning quickly, with a soft bloom
 *   rising from the bottom and the app dimmed a little behind it.
 * - [Mode.Working]: a thinner, steadier ring while it works out the next step.
 * - [Mode.Guiding]: a faint ring while the user follows a step — present, not in the way.
 * - [Mode.Off]: nothing at all. Nothing animates and nothing draws.
 *
 * Drawn on the GPU without blur: the glow is a few widening strokes at falling opacity,
 * which looks the same as a blur at a fraction of the cost on a full-screen window.
 */
class EdgeGlowView(context: Context) : View(context) {

    enum class Mode(val width: Float, val glow: Float, val speed: Float, val scrim: Float, val bloom: Float) {
        Off(0f, 0f, 0f, 0f, 0f),
        Listening(5f, 1f, 1.6f, 0.38f, 1f),
        Working(3f, 0.7f, 0.9f, 0f, 0f),
        Guiding(2f, 0.45f, 0.35f, 0f, 0f),
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val spectrum = intArrayOf(
        Color.parseColor("#A78BFA"),
        Color.parseColor("#60A5FA"),
        Color.parseColor("#5EEAD4"),
        Color.parseColor("#F0ABFC"),
        Color.parseColor("#A78BFA"),
    )

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scrimPaint = Paint().apply { color = Color.BLACK }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private val bloomMatrix = Matrix()
    private val bloomShaders = List(4) { i ->
        RadialGradient(0f, 0f, 1f, intArrayOf(spectrum[i], spectrum[i] and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    /** The display's own corner radius, so the light hugs the glass. Read once attached. */
    private var cornerRadius: Float = dp(36f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            corner?.radius?.takeIf { it > 0 }?.let { cornerRadius = it.toFloat() }
        }
    }

    var mode: Mode = Mode.Off
        set(value) {
            if (field == value) return
            field = value
            if (value != Mode.Off) {
                visibility = VISIBLE
                start()
            }
        }

    /** How loud they are speaking, 0..1, while listening — the light breathes with the voice. */
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Called once the light has completely faded after [Mode.Off]. */
    var onFaded: (() -> Unit)? = null

    /** Eased copies of the mode's numbers, so changes glide rather than jump. */
    private var ringWidth = 0f
    private var glow = 0f
    private var speed = 0f
    private var scrim = 0f
    private var bloom = 0f
    private var voice = 0f
    private var phase = 0f
    private var lastNs = 0L
    private var animator: ValueAnimator? = null

    init {
        visibility = GONE
    }

    private fun start() {
        if (animator != null) return
        lastNs = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        val k = min(1f, dt * 5f)
        val m = mode
        ringWidth += (m.width - ringWidth) * k
        glow += (m.glow - glow) * k
        speed += (m.speed - speed) * k
        scrim += (m.scrim - scrim) * k
        bloom += (m.bloom - bloom) * k
        // Rise with the voice quickly, settle slowly, like a level meter.
        val heard = if (m == Mode.Listening) level else 0f
        voice += (heard - voice) * min(1f, dt * (if (heard > voice) 14f else 4f))
        phase += dt * (0.4f + speed + voice * 1.2f)
        // Faded out completely: stop drawing and stop the clock.
        if (m == Mode.Off && glow < 0.01f && scrim < 0.005f && bloom < 0.01f) {
            animator?.cancel()
            animator = null
            visibility = GONE
            onFaded?.invoke()
            return
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sweep = SweepGradient(w / 2f, h / 2f, spectrum, null)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = getWidth().toFloat()
        val h = getHeight().toFloat()
        if (w <= 0f || h <= 0f || glow <= 0.01f && scrim <= 0.005f) return

        if (scrim > 0.005f) {
            scrimPaint.alpha = (255 * scrim).toInt()
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        }
        if (bloom > 0.01f) drawBloom(canvas, w, h)

        val shader = sweep ?: return
        matrix.setRotate(phase * 57.3f, w / 2f, h / 2f)
        shader.setLocalMatrix(matrix)
        ringPaint.shader = shader

        // Outer glow first, widest and faintest; the crisp line last.
        val core = dp(ringWidth * (1f + 0.6f * voice))
        for (pass in 3 downTo 0) {
            val stroke = core * (1 + pass * 2.4f)
            val lift = 1f + 0.5f * voice
            val alpha = if (pass == 0) glow else glow * lift * (0.28f / pass)
            ringPaint.strokeWidth = stroke
            ringPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            rect.set(0f, 0f, w, h)
            val r = cornerRadius
            canvas.drawRoundRect(rect, r, r, ringPaint)
        }
    }

    /** Soft coloured light rising from the bottom while they speak. */
    private fun drawBloom(canvas: Canvas, w: Float, h: Float) {
        val baseY = h * 1.02f
        for (i in 0 until 4) {
            val a = phase * (0.6f + i * 0.17f) + i * 1.7f
            val cx = w * (0.5f + 0.32f * cos(a))
            val cy = baseY - h * (0.10f + 0.05f * sin(a * 1.3f))
            val r = w * (0.55f + 0.08f * sin(a * 0.7f) + 0.12f * voice)
            // Unit gradients made once, placed with a matrix: nothing allocated per frame.
            bloomMatrix.setScale(r, r)
            bloomMatrix.postTranslate(cx, cy)
            val shader = bloomShaders[i]
            shader.setLocalMatrix(bloomMatrix)
            bloomPaint.shader = shader
            bloomPaint.alpha = (150 * bloom * (0.75f + 0.5f * voice)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, bloomPaint)
        }
    }
}
