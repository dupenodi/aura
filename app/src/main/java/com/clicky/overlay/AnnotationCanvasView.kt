package com.clicky.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.clicky.debug.RingBufferLogger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen annotation + cursor layer.
 *
 * HARD RULE: this window MUST use FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE so it
 * NEVER intercepts touches. Asserted again when attached by [OverlayService].
 */
class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    val cursor: CursorSprite = CursorSprite(context)

    private val annotations = mutableListOf<Annotation>()
    private val density = resources.displayMetrics.density
    private val labelTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        14f,
        resources.displayMetrics,
    )

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Annotation.LABEL_BG
        setShadowLayer(4f * density, 0f, 2f * density, 0x88000000.toInt())
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Annotation.LABEL_FG
        textSize = labelTextSizePx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Annotation.LABEL_FG
        textSize = labelTextSizePx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arrowHeadPath = Path()
    private val calloutPath = Path()
    private val tmpRect = RectF()

    private val tickRunnable = object : Runnable {
        override fun run() {
            runCatching {
                pruneExpired()
                invalidate()
                if (annotations.isNotEmpty() || cursor.visibility > 0.01f) {
                    postOnAnimation(this)
                }
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadowLayer on labels
        setWillNotDraw(false)
        // Confirm we never consume touches even if someone forgets window flags.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun addAnnotation(annotation: Annotation) {
        runCatching {
            synchronized(annotations) {
                annotations.add(annotation)
            }
            ensureTicking()
            RingBufferLogger.log("overlay", "annotation+ ${annotation::class.simpleName}")
        }
    }

    fun clearAnnotations() {
        runCatching {
            synchronized(annotations) {
                annotations.clear()
            }
            invalidate()
        }
    }

    fun ensureTicking() {
        removeCallbacks(tickRunnable)
        postOnAnimation(tickRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        runCatching {
            val now = System.currentTimeMillis()
            val snapshot: List<Annotation>
            synchronized(annotations) {
                snapshot = annotations.toList()
            }
            for (ann in snapshot) {
                val alpha = alphaFor(ann, now)
                if (alpha <= 0.01f) continue
                when (ann) {
                    is Annotation.Circle -> drawCircle(canvas, ann, alpha)
                    is Annotation.Arrow -> drawArrow(canvas, ann, alpha)
                    is Annotation.Highlight -> drawHighlight(canvas, ann, alpha)
                    is Annotation.Callout -> drawCallout(canvas, ann, alpha)
                    is Annotation.Ripple -> drawRipple(canvas, ann, alpha, now)
                }
            }
            cursor.draw(canvas)
        }
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val cutoff = Annotation.EXPIRE_MS + Annotation.FADE_OUT_MS
        synchronized(annotations) {
            annotations.removeAll { now - it.createdAtMs > cutoff }
        }
    }

    private fun alphaFor(ann: Annotation, now: Long): Float {
        val age = now - ann.createdAtMs
        if (age < 0) return 0f
        val fadeIn = when {
            age < Annotation.FADE_IN_MS -> age.toFloat() / Annotation.FADE_IN_MS
            else -> 1f
        }
        val remaining = Annotation.EXPIRE_MS - age
        val fadeOut = when {
            remaining >= 0 -> 1f
            else -> {
                val outAge = -remaining
                (1f - outAge.toFloat() / Annotation.FADE_OUT_MS).coerceIn(0f, 1f)
            }
        }
        return (fadeIn * fadeOut).coerceIn(0f, 1f)
    }

    private fun drawCircle(canvas: Canvas, ann: Annotation.Circle, alpha: Float) {
        strokePaint.color = withAlpha(ann.color, alpha)
        strokePaint.strokeWidth = ann.strokeWidth
        canvas.drawOval(ann.rect, strokePaint)
        ann.label?.let { drawLabelChip(canvas, it, ann.rect.centerX(), ann.rect.top - 8f * density, alpha) }
    }

    private fun drawArrow(canvas: Canvas, ann: Annotation.Arrow, alpha: Float) {
        strokePaint.color = withAlpha(ann.color, alpha)
        strokePaint.strokeWidth = 3.5f * density
        canvas.drawLine(ann.fromX, ann.fromY, ann.toX, ann.toY, strokePaint)

        val angle = atan2((ann.toY - ann.fromY).toDouble(), (ann.toX - ann.fromX).toDouble())
        val head = 12f * density
        arrowHeadPath.reset()
        arrowHeadPath.moveTo(ann.toX, ann.toY)
        arrowHeadPath.lineTo(
            (ann.toX - head * cos(angle - 0.45)).toFloat(),
            (ann.toY - head * sin(angle - 0.45)).toFloat(),
        )
        arrowHeadPath.lineTo(
            (ann.toX - head * cos(angle + 0.45)).toFloat(),
            (ann.toY - head * sin(angle + 0.45)).toFloat(),
        )
        arrowHeadPath.close()
        fillPaint.color = withAlpha(ann.color, alpha)
        canvas.drawPath(arrowHeadPath, fillPaint)

        ann.label?.let {
            val mx = (ann.fromX + ann.toX) / 2f
            val my = (ann.fromY + ann.toY) / 2f - 10f * density
            drawLabelChip(canvas, it, mx, my, alpha)
        }
    }

    private fun drawHighlight(canvas: Canvas, ann: Annotation.Highlight, alpha: Float) {
        fillPaint.color = withAlpha(Annotation.HIGHLIGHT_COLOR, alpha)
        val r = 6f * density
        canvas.drawRoundRect(ann.rect, r, r, fillPaint)
        strokePaint.color = withAlpha(0xCCF9A825.toInt(), alpha)
        strokePaint.strokeWidth = 2f * density
        canvas.drawRoundRect(ann.rect, r, r, strokePaint)
    }

    private fun drawCallout(canvas: Canvas, ann: Annotation.Callout, alpha: Float) {
        val padH = 12f * density
        val padV = 8f * density
        val maxWidth = width * 0.55f
        val textWidth = min(calloutTextPaint.measureText(ann.text), maxWidth)
        val fm = calloutTextPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val boxW = textWidth + padH * 2
        val boxH = textHeight + padV * 2
        val tail = 10f * density

        // Auto-flip: prefer below target; flip above if near bottom. Prefer right; flip left if near right edge.
        val placeBelow = ann.y + boxH + tail + 16f * density < height
        val placeRight = ann.x + boxW / 2f + 8f * density < width
        val placeLeft = ann.x - boxW / 2f - 8f * density > 0f

        val boxLeft = when {
            placeRight && placeLeft -> ann.x - boxW / 2f
            placeRight -> ann.x - 16f * density
            else -> ann.x - boxW + 16f * density
        }.coerceIn(8f * density, max(8f * density, width - boxW - 8f * density))

        val boxTop = if (placeBelow) {
            ann.y + tail
        } else {
            ann.y - tail - boxH
        }.coerceIn(8f * density, max(8f * density, height - boxH - 8f * density))

        tmpRect.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        val radius = 12f * density

        calloutPath.reset()
        calloutPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        // Tail toward anchor point.
        val tipX = ann.x.coerceIn(tmpRect.left + radius, tmpRect.right - radius)
        if (placeBelow) {
            calloutPath.moveTo(tipX - tail * 0.55f, tmpRect.top)
            calloutPath.lineTo(ann.x, ann.y)
            calloutPath.lineTo(tipX + tail * 0.55f, tmpRect.top)
        } else {
            calloutPath.moveTo(tipX - tail * 0.55f, tmpRect.bottom)
            calloutPath.lineTo(ann.x, ann.y)
            calloutPath.lineTo(tipX + tail * 0.55f, tmpRect.bottom)
        }
        calloutPath.close()

        labelBgPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(calloutPath, labelBgPaint)

        calloutTextPaint.alpha = (255 * alpha).toInt()
        val textX = tmpRect.left + padH
        val textY = tmpRect.top + padV - fm.ascent
        canvas.drawText(ann.text, textX, textY, calloutTextPaint)
    }

    private fun drawRipple(canvas: Canvas, ann: Annotation.Ripple, alpha: Float, now: Long) {
        val age = (now - ann.createdAtMs).coerceAtLeast(0L)
        val cycle = 900L
        val t = ((age % cycle).toFloat() / cycle)
        val radius = (18f + t * 42f) * density
        strokePaint.color = withAlpha(Annotation.DEFAULT_ACCENT, alpha * (1f - t))
        strokePaint.strokeWidth = 3f * density
        canvas.drawCircle(ann.x, ann.y, radius, strokePaint)
        fillPaint.color = withAlpha(Annotation.DEFAULT_ACCENT, alpha * 0.25f * (1f - t))
        canvas.drawCircle(ann.x, ann.y, radius * 0.35f, fillPaint)
    }

    private fun drawLabelChip(canvas: Canvas, text: String, cx: Float, top: Float, alpha: Float) {
        val padH = 10f * density
        val padV = 5f * density
        val tw = labelTextPaint.measureText(text)
        val fm = labelTextPaint.fontMetrics
        val th = fm.descent - fm.ascent
        val w = tw + padH * 2
        val h = th + padV * 2
        var left = cx - w / 2f
        var t = top - h
        left = left.coerceIn(4f * density, max(4f * density, width - w - 4f * density))
        t = t.coerceIn(4f * density, max(4f * density, height - h - 4f * density))
        tmpRect.set(left, t, left + w, t + h)
        labelBgPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(tmpRect, h / 2f, h / 2f, labelBgPaint)
        labelTextPaint.alpha = (255 * alpha).toInt()
        canvas.drawText(text, left + padH, t + padV - fm.ascent, labelTextPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tickRunnable)
        cursor.cancelAnimations()
        super.onDetachedFromWindow()
    }
}
