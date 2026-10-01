package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** An action offered alongside a message — chips, never a dialog. */
data class BubbleChip(val label: String, val primary: Boolean, val onClick: () -> Unit)

/**
 * The instruction dock: one wide card resting just above the navigation bar.
 *
 * It replaces the speech bubble that hung off the orb. Instructions are the thing people
 * read most, so they get the full width and large type, in the same place every time, and
 * never covering the control they point at (the service moves the dock to the top when the
 * ringed target is down here). One sentence at a time, never a chat log.
 */
class DockView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val tagView: TextView
    private val messageView: TextView
    private val chipRow: LinearLayout
    private val dot = AuraDot(context)

    init {
        orientation = VERTICAL
        background = DockBackground(density)
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))

        tagView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A78BFA"))
            typeface = OverlayFonts.medium(context)
            visibility = View.GONE
        }
        addView(tagView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(dot, LayoutParams(dp(12f), dp(12f)).apply {
            topMargin = dp(7f)
            rightMargin = dp(12f)
        })
        messageView = TextView(context).apply {
            // Large and high-contrast: this is the instruction they are following.
            textSize = 19f
            setTextColor(Color.parseColor("#F4F4F5"))
            setLineSpacing(dp(3f).toFloat(), 1f)
            typeface = OverlayFonts.display(context)
        }
        row.addView(messageView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = View.GONE
        }
        addView(
            chipRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14f)
                leftMargin = dp(24f)
            },
        )
    }

    /**
     * [tag] routes the message: "listening" shows the live transcript large under a label,
     * "helping" marks a live session (the dot turns), anything else is a plain note.
     */
    fun bind(message: String, tag: String?, chips: List<BubbleChip>) {
        // One sentence at a time is a design rule, and it doubles as a safety net: no
        // stray provider payload can ever turn the dock into a wall of text.
        messageView.text = message.trim().replace(Regex("\\s+"), " ").take(MAX_MESSAGE_CHARS)
        val listening = tag == "listening"
        messageView.textSize = if (listening) 22f else 19f
        if (listening) {
            tagView.text = "listening"
            tagView.visibility = View.VISIBLE
        } else {
            tagView.visibility = View.GONE
        }
        dot.active = listening || tag == "helping"

        chipRow.removeAllViews()
        if (chips.isEmpty()) {
            chipRow.visibility = View.GONE
        } else {
            chipRow.visibility = View.VISIBLE
            chips.forEach { chip -> chipRow.addView(buildChip(chip)) }
        }
    }

    private fun buildChip(chip: BubbleChip): View = TextView(context).apply {
        text = chip.label
        textSize = 15f
        typeface = OverlayFonts.medium(context)
        gravity = Gravity.CENTER
        // At least 44dp tall: an easy target for an unsteady finger.
        minHeight = dp(44f)
        setPadding(dp(20f), dp(11f), dp(20f), dp(11f))
        setTextColor(if (chip.primary) Color.parseColor("#09090B") else Color.parseColor("#F4F4F5"))
        background = ChipBackground(if (chip.primary) Color.parseColor("#F4F4F5") else Color.parseColor("#26262C"))
        setOnClickListener { chip.onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        }
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 180
    }
}

/** A small disc of the aura's colours; it turns slowly while aura is busy. */
private class AuraDot(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var angle = 0f
    private var animator: ValueAnimator? = null

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && isAttachedToWindow) start() else stop()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = SweepGradient(w / 2f, h / 2f, AURA_SPECTRUM, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2_400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        matrix.setRotate(angle, width / 2f, height / 2f)
        paint.shader?.setLocalMatrix(matrix)
        paint.alpha = if (active) 255 else 150
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
}

internal val AURA_SPECTRUM = intArrayOf(
    Color.parseColor("#A78BFA"),
    Color.parseColor("#60A5FA"),
    Color.parseColor("#5EEAD4"),
    Color.parseColor("#F0ABFC"),
    Color.parseColor("#A78BFA"),
)

/** Dark glass card with a hairline edge that fades through the aura's colours. */
private class DockBackground(private val density: Float) : Drawable() {
    private val radius = 24f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5141417") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        stroke.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            AURA_SPECTRUM.map { (it and 0x00FFFFFF) or (0x59 shl 24) }.toIntArray(),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = stroke.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = (alpha * 0.96f).toInt()
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Pill background for a chip. */
private class ChipBackground(fillColor: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, fill)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
