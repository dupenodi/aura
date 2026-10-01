package com.drishti.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** An action offered alongside a message — chips, never a dialog. */
data class BubbleChip(val label: String, val primary: Boolean, val onClick: () -> Unit)

/**
 * The glass message card the orb speaks through.
 *
 * One sentence at a time, never a chat log — so the card is rebuilt per message rather
 * than accumulating history. An optional mono tag above the text names the situation
 * ("which one?", "stuck at step 3"), which is what makes failures feel honest.
 */
class BubbleCardView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val background0 = BubbleBackground(density)
    private val tagView: TextView
    private val messageView: TextView
    private val chipRow: LinearLayout

    init {
        orientation = VERTICAL
        background = background0
        setPadding(dp(17f), dp(14f), dp(17f), dp(14f))
        // The card's glow and blur are software-only effects.
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        tagView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A78BFA"))
            typeface = OverlayFonts.medium(context)
            visibility = View.GONE
        }
        addView(tagView)

        messageView = TextView(context).apply {
            // Large and high-contrast: this is the instruction they are following.
            textSize = 17f
            setTextColor(Color.parseColor("#F4F4F5"))
            setLineSpacing(dp(3f).toFloat(), 1f)
            maxWidth = dp(280f)
            typeface = OverlayFonts.display(context)
        }
        addView(
            messageView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            },
        )

        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = View.GONE
        }
        addView(
            chipRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            },
        )
    }

    /** Which side of the card the tail points from, so it always aims at the orb. */
    fun setTail(onTop: Boolean, onRight: Boolean) = background0.setTail(onTop, onRight)

    fun setAccent(color: Int) {
        background0.setAccent(color)
        tagView.setTextColor(color)
    }

    fun bind(message: String, tag: String?, chips: List<BubbleChip>) {
        // One sentence at a time is a design rule, and it doubles as a safety net: no
        // stray provider payload can ever turn the bubble into a wall of text.
        messageView.text = message.trim().replace(Regex("\\s+"), " ").take(MAX_MESSAGE_CHARS)
        // Tags are for routing, not for show — only "listening" earns a visible label.
        if (tag != "listening") {
            tagView.visibility = View.GONE
        } else {
            tagView.text = tag
            tagView.visibility = View.VISIBLE
        }

        chipRow.removeAllViews()
        if (chips.isEmpty()) {
            chipRow.visibility = View.GONE
        } else {
            chipRow.visibility = View.VISIBLE
            chips.forEach { chip -> chipRow.addView(buildChip(chip)) }
        }
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 180
    }

    private fun buildChip(chip: BubbleChip): View = TextView(context).apply {
        text = chip.label
        textSize = 15f
        typeface = OverlayFonts.medium(context)
        gravity = Gravity.CENTER
        // At least 44dp tall: an easy target for an unsteady finger.
        minHeight = dp(44f)
        setPadding(dp(18f), dp(11f), dp(18f), dp(11f))
        setTextColor(if (chip.primary) Color.parseColor("#09090B") else Color.parseColor("#F4F4F5"))
        background = ChipBackground(
            density = density,
            fillColor = if (chip.primary) Color.parseColor("#F4F4F5") else Color.parseColor("#26262C"),
        )
        setOnClickListener { chip.onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        }
    }
}

/** Rounded glass panel with a tail, a hairline accent border and an outer glow. */
private class BubbleBackground(private val density: Float) : Drawable() {

    private val tailHalf = 8f * density
    private val tailHeight = 9f * density
    private val radius = 20f * density
    // The design's asymmetric corner: the corner nearest the orb is nearly square.
    private val tightRadius = 6f * density

    private var tailOnTop = false
    private var tailOnRight = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5141417")
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DA78BFA")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33818CF8")
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(
            14f * density,
            android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
    }
    private val path = Path()
    private val rect = RectF()

    fun setTail(onTop: Boolean, onRight: Boolean) {
        if (tailOnTop == onTop && tailOnRight == onRight) return
        tailOnTop = onTop
        tailOnRight = onRight
        invalidateSelf()
    }

    fun setAccent(color: Int) {
        stroke.color = (color and 0x00FFFFFF) or (0x66 shl 24)
        glow.color = (color and 0x00FFFFFF) or (0x40 shl 24)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return

        val top = if (tailOnTop) b.top + tailHeight else b.top.toFloat()
        val bottom = if (tailOnTop) b.bottom.toFloat() else b.bottom - tailHeight
        rect.set(b.left.toFloat(), top, b.right.toFloat(), bottom)

        // Corner radii, clockwise from top-left; the corner by the tail is tightened.
        val r = radius
        val t = tightRadius
        val radii = when {
            !tailOnTop && !tailOnRight -> floatArrayOf(r, r, r, r, r, r, t, t)
            !tailOnTop && tailOnRight -> floatArrayOf(r, r, r, r, t, t, r, r)
            tailOnTop && !tailOnRight -> floatArrayOf(t, t, r, r, r, r, r, r)
            else -> floatArrayOf(r, r, t, t, r, r, r, r)
        }

        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)

        val tailX = if (tailOnRight) b.right - radius else b.left + radius
        if (tailOnTop) {
            path.moveTo(tailX - tailHalf, top)
            path.lineTo(tailX, b.top.toFloat())
            path.lineTo(tailX + tailHalf, top)
        } else {
            path.moveTo(tailX - tailHalf, bottom)
            path.lineTo(tailX, b.bottom.toFloat())
            path.lineTo(tailX + tailHalf, bottom)
        }
        path.close()

        canvas.drawPath(path, glow)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = (alpha * 0.94f).toInt()
        stroke.alpha = (alpha * 0.32f).toInt()
        glow.alpha = (alpha * 0.25f).toInt()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Pill background for a chip. */
private class ChipBackground(private val density: Float, fillColor: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
