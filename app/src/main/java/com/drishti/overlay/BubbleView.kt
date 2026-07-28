package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Classic View floating bubble (NOT Compose).
 * Tap toggles listening (push-to-talk); long-press opens menu; drag moves window.
 *
 * While [BubbleState.Listening], draws a cohesive ChatGPT/Higgsfield-style voice organism:
 * soft blobby membrane + circular core sharing one center and teal family, driven by mic amplitude.
 * Hit-testing stays on the 64dp core; visuals extend into padding.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bubbleState: BubbleState = BubbleState.Idle
        set(value) {
            field = value
            if (value != BubbleState.Listening) {
                targetLevel = 0f
            }
            updateAnimators()
            invalidate()
        }

    /**
     * Instantaneous mic amplitude in 0..1. Smoothed on the render loop while listening
     * (fast attack, softer release).
     */
    var audioLevel: Float = 0f
        set(value) {
            targetLevel = value.coerceIn(0f, 1f)
            field = targetLevel
        }

    var onToggleListening: (() -> Unit)? = null
    var onLongPressMenu: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    /** Visible mascot core — kept at 64dp. */
    private val coreSizePx = (64f * density).toInt()
    /** Extra padding so the blobby membrane can extend past the core. */
    private val haloPadPx = (40f * density).toInt()
    private val viewSizePx = coreSizePx + haloPadPx * 2
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val membranePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val orbPath = Path()
    private val membranePath = Path()
    private val auraPath = Path()

    private var pulseScale = 1f
    private var pulseAlpha = 0.55f
    private var spinAngle = 0f
    private var phase = 0f
    private var breathe = 0f
    private var springScale = 1f
    private var springVel = 0f
    private var targetLevel = 0f
    private var smoothedLevel = 0f
    private var lastFrameNanos = 0L
    private var animFramesPosted = false

    // Soft lobe offsets fused into the membrane (same organism, not detached particles).
    private val lobeAngles = floatArrayOf(0.35f, 1.9f, 3.5f, 5.1f)
    private val lobePhases = FloatArray(lobeAngles.size)
    private val lobeRadii = FloatArray(lobeAngles.size)

    private var pulseAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onToggleListening?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!dragging) {
                    onLongPressMenu?.invoke()
                }
            }
        },
    )

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!needsAnimFrames()) {
                animFramesPosted = false
                lastFrameNanos = 0L
                return
            }
            val dt = if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.008f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos
            advanceAnimation(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        minimumWidth = viewSizePx
        minimumHeight = viewSizePx
        contentDescription = "Drishti bubble"
        setWillNotDraw(false)
        updateAnimators()
    }

    /** Full window size including halo padding. */
    fun preferredSizePx(): Int = viewSizePx

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewSizePx, viewSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        runCatching {
            val cx = width / 2f
            val cy = height / 2f
            val r = coreSizePx / 2f - 6f * density

            when (bubbleState) {
                BubbleState.Listening -> drawVoiceOrb(canvas, cx, cy, r)
                BubbleState.Processing -> drawProcessingAura(canvas, cx, cy, r)
                BubbleState.Idle -> drawIdleBreath(canvas, cx, cy, r)
                else -> Unit
            }

            // Circular gradient core — spring-scaled with the membrane while listening.
            val coreScale = if (bubbleState == BubbleState.Listening) {
                springScale
            } else {
                1f
            }
            val cr = r * coreScale
            blobPaint.shader = LinearGradient(
                cx - cr,
                cy - cr,
                cx + cr,
                cy + cr,
                intArrayOf(0xFF2EC4B6.toInt(), 0xFF1B8A80.toInt(), 0xFF0E3D45.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, cr, blobPaint)
            blobPaint.shader = null

            // Soft inner highlight.
            corePaint.alpha = 55
            canvas.drawCircle(cx - cr * 0.25f, cy - cr * 0.28f, cr * 0.35f, corePaint)
            corePaint.alpha = 255

            if (bubbleState == BubbleState.Thinking) {
                drawThinkingGlow(canvas, cx, cy, r)
                arcPaint.shader = SweepGradient(
                    cx,
                    cy,
                    intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                )
                canvas.save()
                canvas.rotate(spinAngle, cx, cy)
                val oval = RectF(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
                canvas.drawArc(oval, -40f, 110f, false, arcPaint)
                canvas.restore()
                arcPaint.shader = null
            }

            // Center glyph: small C / dot.
            corePaint.color = 0xEEFFFFFF.toInt()
            canvas.drawCircle(cx, cy, r * 0.16f, corePaint)
        }
    }

    /**
     * One cohesive voice organism: shared-center membrane + fused lobes + core.
     * Quiet = clear breathe; speaking = energetic morph with premium springs.
     */
    private fun drawVoiceOrb(canvas: Canvas, cx: Float, cy: Float, coreR: Float) {
        val level = smoothedLevel.coerceIn(0f, 1f)
        val breatheAmt = 0.5f + 0.5f * sin(breathe)
        // Quiet listening still has presence; speech drives strong energy.
        val energy = (0.18f + 0.22f * breatheAmt * (1f - level) + 0.82f * level).coerceIn(0f, 1f)
        val teal = 0x002EC4B6
        val tealBright = 0x005EEAD4
        val tealDeep = 0x001B8A80
        val scale = springScale

        // Outer soft glow — same center, same family as the core.
        val glowR = coreR * (1.55f + 1.15f * energy) * scale
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowR.coerceAtLeast(1f),
            intArrayOf(
                ((0x55 + (0x45 * energy).toInt()).coerceAtMost(0xA0) shl 24) or teal,
                ((0x30 + (0x35 * energy).toInt()).coerceAtMost(0x70) shl 24) or tealBright,
                0x00000000,
            ),
            floatArrayOf(0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null

        // Fused lobes hug the core (offsets stay inside the membrane envelope).
        for (i in lobeAngles.indices) {
            val ang = lobeAngles[i] + lobePhases[i]
            val reach = lobeRadii[i] * scale
            val ox = cx + cos(ang) * reach
            val oy = cy + sin(ang) * reach
            val blobR = coreR * (0.70f + 0.55f * energy) *
                (0.88f + 0.12f * sin(phase * 0.8f + i))
            val alpha = (0x38 + (0x68 * energy).toInt()).coerceIn(0x28, 0x98)
            orbPaint.shader = RadialGradient(
                ox,
                oy,
                blobR.coerceAtLeast(1f),
                intArrayOf(
                    (alpha shl 24) or if (i % 2 == 0) tealBright else teal,
                    ((alpha / 2) shl 24) or tealDeep,
                    0x00000000,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(ox, oy, blobR, orbPaint)
            orbPaint.shader = null
        }

        // Primary membrane — clearly attached, morphs hard with speech.
        buildOrganicBlob(
            path = membranePath,
            cx = cx,
            cy = cy,
            baseR = coreR * (1.12f + 0.48f * energy) * scale,
            energy = energy,
            phase = phase,
            steps = 80,
            deformScale = 1.35f,
        )
        membranePaint.shader = RadialGradient(
            cx,
            cy,
            (coreR * (1.55f + 0.7f * energy) * scale).coerceAtLeast(1f),
            intArrayOf(
                ((0x50 + (0x55 * energy).toInt()).coerceAtMost(0xA8) shl 24) or tealBright,
                ((0x28 + (0x30 * energy).toInt()).coerceAtMost(0x60) shl 24) or teal,
                0x00000000,
            ),
            floatArrayOf(0.32f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(membranePath, membranePaint)
        membranePaint.shader = null

        // Inner filled silhouette — bridges membrane into the solid core.
        buildOrganicBlob(
            path = orbPath,
            cx = cx,
            cy = cy,
            baseR = coreR * (1.02f + 0.28f * energy) * scale,
            energy = energy * 0.9f,
            phase = phase + 0.35f,
            steps = 72,
            deformScale = 1.1f,
        )
        auraPaint.shader = RadialGradient(
            cx,
            cy,
            (coreR * (1.25f + 0.4f * energy) * scale).coerceAtLeast(1f),
            intArrayOf(
                ((0x40 + (0x38 * energy).toInt()).coerceAtMost(0x78) shl 24) or teal,
                ((0x14 * energy).toInt() shl 24) or tealDeep,
                0x00000000,
            ),
            floatArrayOf(0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(orbPath, auraPaint)
        auraPaint.shader = null

        // Soft outer rim of the morph (not EQ rings).
        buildOrganicBlob(
            path = auraPath,
            cx = cx,
            cy = cy,
            baseR = coreR * (1.28f + 0.55f * energy) * scale,
            energy = energy * 0.9f,
            phase = phase + 0.7f,
            steps = 64,
            deformScale = 1.2f,
        )
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = (1.8f + 1.8f * energy) * density
        ringPaint.color = (((0x35 + (0x50 * energy).toInt()).coerceAtMost(0x88)) shl 24) or tealBright
        canvas.drawPath(auraPath, ringPaint)
    }

    private fun drawIdleBreath(canvas: Canvas, cx: Float, cy: Float, coreR: Float) {
        val t = (0.5f + 0.5f * sin(breathe)).coerceIn(0f, 1f)
        val glowR = coreR * (1.12f + 0.06f * t)
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowR,
            intArrayOf(
                ((0x22 + (0x12 * t).toInt()) shl 24) or 0x002EC4B6,
                0x00000000,
            ),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null
    }

    private fun drawProcessingAura(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val accent = 0x00F4A261
        val glowR = r * (1.2f + 0.22f * pulseScale)
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowR,
            intArrayOf(
                ((0x60 * pulseAlpha).toInt() shl 24) or accent,
                ((0x20 * pulseAlpha).toInt() shl 24) or accent,
                0x00000000,
            ),
            floatArrayOf(0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null

        buildOrganicBlob(
            path = orbPath,
            cx = cx,
            cy = cy,
            baseR = r * pulseScale * 1.08f,
            energy = 0.25f + 0.2f * pulseAlpha,
            phase = spinAngle * (Math.PI.toFloat() / 180f) * 0.4f,
            steps = 48,
        )
        ringPaint.color = ((0x90 * pulseAlpha).toInt() shl 24) or accent
        ringPaint.strokeWidth = 2.2f * density
        canvas.drawPath(orbPath, ringPaint)
    }

    private fun drawThinkingGlow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val glowR = r * 1.25f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowR,
            intArrayOf(
                0x332EC4B6,
                0x00000000,
            ),
            floatArrayOf(0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null
    }

    /**
     * Closed organic blob: base circle + low-frequency harmonics (2–5 lobes).
     * [deformScale] boosts speech morph without introducing high-frequency jitter.
     */
    private fun buildOrganicBlob(
        path: Path,
        cx: Float,
        cy: Float,
        baseR: Float,
        energy: Float,
        phase: Float,
        steps: Int,
        deformScale: Float = 1f,
    ) {
        path.reset()
        val amp = baseR * (0.10f + 0.32f * energy) * deformScale
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val ang = t * Math.PI.toFloat() * 2f
            val deform =
                amp * (
                    0.50f * sin(2f * ang + phase) +
                        0.32f * sin(3f * ang - phase * 0.75f) +
                        0.18f * sin(5f * ang + phase * 1.25f)
                    )
            val rr = (baseR + deform).coerceAtLeast(baseR * 0.68f)
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun advanceAnimation(dt: Float) {
        when (bubbleState) {
            BubbleState.Listening -> {
                // Fast attack so speech "pops"; softer release — no jitter.
                val follow = if (targetLevel > smoothedLevel) 14f else 3.8f
                smoothedLevel += (targetLevel - smoothedLevel) * (1f - exp(-follow * dt))

                phase = (phase + dt * (1.25f + 2.2f * smoothedLevel)) % (Math.PI.toFloat() * 2f)
                // Clear quiet breathe so mic-live is obvious.
                breathe += dt * (1.6f + 0.8f * smoothedLevel)

                val targetScale = 1f + 0.12f * smoothedLevel + 0.035f * sin(breathe)
                val stiffness = 36f
                val damping = 9.5f
                val force = (targetScale - springScale) * stiffness - springVel * damping
                springVel += force * dt
                springScale += springVel * dt
                springScale = springScale.coerceIn(0.92f, 1.22f)

                for (i in lobeAngles.indices) {
                    lobePhases[i] = (lobePhases[i] + dt * (0.4f + 0.18f * i + 0.55f * smoothedLevel)) %
                        (Math.PI.toFloat() * 2f)
                    val baseReach = coreSizePx * 0.18f
                    val stretch = coreSizePx * (0.12f + 0.38f * smoothedLevel)
                    lobeRadii[i] = baseReach + stretch * (0.65f + 0.35f * sin(phase + i * 1.25f))
                }
            }
            BubbleState.Idle -> {
                breathe += dt * 0.9f
                smoothedLevel += (0f - smoothedLevel) * (1f - exp(-3f * dt))
                springScale += (1f - springScale) * (1f - exp(-4f * dt))
            }
            BubbleState.Processing -> {
                phase += dt * 1.2f
            }
            else -> Unit
        }
    }

    private fun needsAnimFrames(): Boolean =
        bubbleState == BubbleState.Listening ||
            bubbleState == BubbleState.Idle ||
            bubbleState == BubbleState.Processing

    /** Touches only register on the 64dp core — halo padding is visual-only / pass-through. */
    private fun isInCore(x: Float, y: Float): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val hitR = coreSizePx / 2f
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= hitR * hitR
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return runCatching {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isInCore(event.x, event.y)) {
                return@runCatching false
            }
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!dragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDrag?.invoke(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onDragEnd?.invoke()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun updateAnimators() {
        pulseAnimator?.cancel()
        spinAnimator?.cancel()
        pulseAnimator = null
        spinAnimator = null
        stopAnimFrames()

        when (bubbleState) {
            BubbleState.Listening -> {
                startAnimFrames()
            }
            BubbleState.Processing -> {
                startAnimFrames()
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        pulseScale = 0.94f + 0.10f * t
                        pulseAlpha = 0.38f + 0.40f * t
                        invalidate()
                    }
                    start()
                }
            }
            BubbleState.Thinking -> {
                spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1100L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        spinAngle = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            BubbleState.Idle -> {
                pulseScale = 1f
                pulseAlpha = 0.55f
                startAnimFrames()
            }
        }
    }

    private fun startAnimFrames() {
        if (animFramesPosted) return
        animFramesPosted = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopAnimFrames() {
        if (!animFramesPosted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        animFramesPosted = false
        lastFrameNanos = 0L
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        spinAnimator?.cancel()
        stopAnimFrames()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * Snap [params] x/y so the bubble hugs the nearest horizontal screen edge.
         */
        fun snapToNearestEdge(
            params: WindowManager.LayoutParams,
            bubbleSize: Int,
            screenWidth: Int,
            screenHeight: Int,
        ) {
            val centerX = params.x + bubbleSize / 2
            params.x = if (centerX < screenWidth / 2) {
                0
            } else {
                (screenWidth - bubbleSize).coerceAtLeast(0)
            }
            params.y = params.y.coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
        }
    }
}
